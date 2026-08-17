package dev.agenor.examples.distributed;

import dev.agenor.adapters.messaging.redis.RedisMessagingFactory;
import dev.agenor.adapters.persistence.directory.JdbcAgentDirectory;
import dev.agenor.adapters.persistence.directory.JdbcDirectoryConfig;
import dev.agenor.core.AgentEndpoint;
import dev.agenor.core.annotations.Agent;
import dev.agenor.core.dialogue.DialogueHandler;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.runtime.AgenorRuntime;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.dialogue.DialogueCapability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Cross-runtime dialogue — two independent {@link AgenorRuntime} instances exchanging a typed
 * REQUEST/INFORM over Redis Streams, finding each other through a shared PostgreSQL directory
 * (ADR-021, ADR-023, ADR-030).
 *
 * <p>Every other messaging example here runs on a single runtime, where {@code sendTo} never
 * leaves the JVM: the dispatcher finds the recipient in its local handler map and calls it
 * directly. This is the first example whose messages genuinely travel, and therefore the first
 * that can fail the way a distributed deployment fails.
 *
 * <h2>What is deliberately not shared</h2>
 * The two runtimes have only the two backends in common. Each owns a separate
 * {@link RedisMessagingFactory} (its own connection, its own {@code nodeId}), a separate
 * {@link JdbcAgentDirectory} (its own pool), a separate dispatcher and a separate agent set.
 * Nothing inside the JVM links them. Running both in one process keeps the example to a single
 * {@code mvn exec:java}, but the delivery path is the real one: the request is serialised,
 * written to {@code agenor:node:node-2}, read back by runtime 2's consumer loop, and
 * deserialised there.
 *
 * <h2>The two things this demonstrates</h2>
 * <ol>
 *   <li><b>The directory is the rendezvous.</b> Runtime 1 has never heard of
 *       {@code pricing-service}; it reaches it because runtime 2 wrote an endpoint naming its own
 *       {@code nodeId} into Postgres when registering. Before 0.26.0 nothing wrote that endpoint:
 *       a cross-runtime {@code sendTo} resolved to an empty node id, wrote to a stream no consumer
 *       was reading, and returned a <em>successfully completed</em> future. The message vanished
 *       with no error anywhere. That is why this example waits for the endpoint and prints it —
 *       see {@code LocalEndpointProvider}.</li>
 *   <li><b>Payloads survive as types, not as casts.</b> The content is a {@link QuoteRequest}
 *       record. Once it crosses Redis it arrives as a {@code LinkedHashMap}, so a cast to
 *       {@code QuoteRequest} throws — the defect ADR-030 closed.
 *       {@link DialogueMessage#contentAs(Class)} converts it back. Both agents log the wire type
 *       they actually received, so the difference is visible rather than claimed.</li>
 * </ol>
 *
 * <p>Prerequisites — a PostgreSQL and a Valkey (or Redis-compatible) server:
 * <pre>
 *   docker compose up -d postgres valkey      # compose.yml at the repository root
 *
 *   # or directly:
 *   docker run -d -p 5432:5432 -e POSTGRES_DB=agenor -e POSTGRES_USER=agenor \
 *              -e POSTGRES_PASSWORD=agenor postgres:16-alpine
 *   docker run -d -p 6379:6379 valkey/valkey:8
 * </pre>
 *
 * <p>Then run:
 * <pre>
 *   mvn exec:java -pl agenor-examples \
 *       -Dexec.mainClass="dev.agenor.examples.distributed.CrossRuntimeExample"
 * </pre>
 *
 * <p>Overridable via environment: {@code REDIS_URI}, {@code POSTGRES_URL},
 * {@code POSTGRES_USER}, {@code POSTGRES_PASSWORD}.
 *
 * @since 0.26.0
 */
public class CrossRuntimeExample {

    private static final Logger log = LoggerFactory.getLogger(CrossRuntimeExample.class);

    private static final String REQUESTER_ID = "quote-requester";
    private static final String PRICING_ID = "pricing-service";

    /** Bounds both the wait for cross-runtime discovery and the dialogue itself. */
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    public static void main(String[] args) throws Exception {

        var redisUri = env("REDIS_URI", "redis://localhost:6379");
        var jdbcUrl = env("POSTGRES_URL", "jdbc:postgresql://localhost:5432/agenor");
        var jdbcUser = env("POSTGRES_USER", "agenor");
        var jdbcPassword = env("POSTGRES_PASSWORD", "agenor");

        log.info("=== Cross-Runtime Dialogue Example ===");
        log.info("Redis    : {}", redisUri);
        log.info("Postgres : {}", jdbcUrl);

        var directoryConfig = JdbcDirectoryConfig.of(jdbcUrl, jdbcUser, jdbcPassword);

        // Two of everything. The only things the runtimes share are the two URIs above.
        try (var factory1 = redisFactory(redisUri, "node-1");
             var factory2 = redisFactory(redisUri, "node-2");
             var directory1 = JdbcAgentDirectory.create(directoryConfig);
             var directory2 = JdbcAgentDirectory.create(directoryConfig)) {

            // The resolver is read on every sendTo rather than captured now, so a dispatcher can
            // be built before the directory it consults. Here both already exist.
            var runtime1 = AgenorRuntime.builder()
                    .messageDispatcher(factory1.messageDispatcher(directory1::resolver))
                    .agentRegistry(directory1.registry())
                    .agentDiscovery(directory1.discovery())
                    .agentResolver(directory1.resolver())
                    .build();

            var runtime2 = AgenorRuntime.builder()
                    .messageDispatcher(factory2.messageDispatcher(directory2::resolver))
                    .agentRegistry(directory2.registry())
                    .agentDiscovery(directory2.discovery())
                    .agentResolver(directory2.resolver())
                    .build();

            var requester = new QuoteRequesterAgent();
            runtime1.registerAgent(requester);
            runtime2.registerAgent(new PricingServiceAgent());

            runtime1.start().join();
            runtime2.start().join();
            log.info("Both runtimes started — node-1 hosts {}, node-2 hosts {}",
                    REQUESTER_ID, PRICING_ID);

            // registerAgent writes to the directory fire-and-forget, so wait for the endpoint to
            // appear rather than for a fixed delay — and print it, because an endpoint naming the
            // wrong node is the exact failure this example exists to rule out.
            var endpoint = awaitEndpoint(runtime1, PRICING_ID);
            log.info("Runtime 1 resolved {} → node '{}' over transport '{}'",
                    PRICING_ID, endpoint.nodeId(), endpoint.transportType());

            // ---------------------------------------------------------------------
            // The exchange
            // ---------------------------------------------------------------------
            var request = new QuoteRequest("SKU-42", 3, "EUR");
            log.info("[node-1/{}] REQUEST → {} : {}", REQUESTER_ID, PRICING_ID, request);

            var started = Instant.now();
            DialogueMessage reply = requester.requestQuote(request)
                    .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            if (reply.performative() != Performative.INFORM) {
                throw new IllegalStateException("Expected INFORM but got "
                        + reply.performative() + ": " + reply.content());
            }

            // Written as (Quote) reply.content(), this line would throw: the payload crossed a
            // serialising transport. ADR-030 is what makes the next line work.
            log.info("[node-1/{}] reply content arrived on the wire as a {}",
                    REQUESTER_ID, reply.content().getClass().getSimpleName());

            Quote quote = reply.contentAs(Quote.class);
            log.info("[node-1/{}] INFORM ← {} : {} x{} @ {} = {} {} (round trip {} ms)",
                    REQUESTER_ID, PRICING_ID, quote.sku(), quote.quantity(), quote.unitPrice(),
                    quote.total(), quote.currency(),
                    Duration.between(started, Instant.now()).toMillis());

            log.info("=== Exchange completed across two runtimes ===");

            runtime1.stop().join();
            runtime2.stop().join();
        }

        log.info("=== Example completed ===");
    }

    // -------------------------------------------------------------------------
    // Wiring helpers
    // -------------------------------------------------------------------------

    private static RedisMessagingFactory redisFactory(String uri, String nodeId) {
        return RedisMessagingFactory.builder()
                .uri(uri)
                .nodeId(nodeId)
                // Same prefix on both nodes on purpose: it is the node id, not the prefix, that
                // separates agenor:node:node-1 from agenor:node:node-2.
                .consumerGroupPrefix("agenor")
                .build();
    }

    /**
     * Blocks until {@code agentId} is resolvable through the given runtime's directory with a
     * non-blank node id.
     *
     * @param runtime the runtime doing the looking up
     * @param agentId the agent to wait for
     * @return the resolved endpoint
     * @throws IllegalStateException if it never appears within {@link #TIMEOUT}, which means the
     *         other runtime either failed to register or registered without an endpoint
     * @throws InterruptedException if interrupted while polling
     */
    private static AgentEndpoint awaitEndpoint(AgenorRuntime runtime, String agentId)
            throws InterruptedException {
        var deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            var found = runtime.getAgentDirectory().resolveEndpoint(agentId).join();
            if (found.isPresent() && !found.get().nodeId().isBlank()) {
                return found.get();
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("Agent '" + agentId + "' never became resolvable with a "
                + "non-blank node id. Either its runtime failed to register it, or its dispatcher "
                + "advertised no endpoint — see LocalEndpointProvider.");
    }

    private static String env(String name, String fallback) {
        return System.getenv().getOrDefault(name, fallback);
    }

    // -------------------------------------------------------------------------
    // Agents
    // -------------------------------------------------------------------------

    /** Lives on node-1. Asks node-2 for a price and reads the answer back as a record. */
    @Agent(value = REQUESTER_ID, type = "sales", capabilities = {"quoting"}, autoStart = true)
    public static class QuoteRequesterAgent extends BaseAgent {

        private final DialogueCapability dialogue = new DialogueCapability(this);

        public QuoteRequesterAgent() {
            super(REQUESTER_ID, "Quote Requester");
        }

        CompletableFuture<DialogueMessage> requestQuote(QuoteRequest request) {
            return dialogue.request(PRICING_ID, request, TIMEOUT);
        }
    }

    /** Lives on node-2. Prices whatever arrives and replies with a typed INFORM. */
    @Agent(value = PRICING_ID, type = "pricing", capabilities = {"pricing"}, autoStart = true)
    public static class PricingServiceAgent extends BaseAgent {

        private static final double UNIT_PRICE = 19.99;

        private final DialogueCapability dialogue = new DialogueCapability(this);

        public PricingServiceAgent() {
            super(PRICING_ID, "Pricing Service");
        }

        /**
         * Replies with a single INFORM rather than AGREE-then-INFORM. Both are valid under
         * {@code RequestProtocol}, but only the final INFORM resolves the future returned by
         * {@code request(...)} — see ADR-026.
         *
         * @param msg the incoming REQUEST
         */
        @DialogueHandler(performatives = Performative.REQUEST)
        public void handleQuoteRequest(DialogueMessage msg) {
            log.info("[node-2/{}] REQUEST ← {} arrived on the wire as a {}",
                    PRICING_ID, msg.senderId(), msg.content().getClass().getSimpleName());

            QuoteRequest request;
            try {
                request = msg.contentAs(QuoteRequest.class);
            } catch (IllegalArgumentException e) {
                log.warn("[node-2/{}] unreadable payload from {}: {}",
                        PRICING_ID, msg.senderId(), e.getMessage());
                dialogue.failure(msg, "Unreadable quote request: " + e.getMessage());
                return;
            }

            if (request.sku() == null || request.quantity() <= 0) {
                dialogue.refuse(msg, "sku is required and quantity must be positive");
                return;
            }

            var quote = new Quote(request.sku(), request.quantity(), UNIT_PRICE,
                    round(UNIT_PRICE * request.quantity()), request.currency());
            log.info("[node-2/{}] INFORM → {} : {}", PRICING_ID, msg.senderId(), quote);
            dialogue.inform(msg, quote);
        }

        private static double round(double value) {
            return Math.round(value * 100.0) / 100.0;
        }
    }

    // -------------------------------------------------------------------------
    // Payloads — records, not Strings: that is the point (ADR-005, ADR-030)
    // -------------------------------------------------------------------------

    /**
     * A request for a price.
     *
     * @param sku      the article being priced
     * @param quantity how many units
     * @param currency ISO code the answer should be expressed in
     */
    public record QuoteRequest(String sku, int quantity, String currency) {}

    /**
     * A priced quote.
     *
     * @param sku       the article priced
     * @param quantity  how many units
     * @param unitPrice price of a single unit
     * @param total     {@code unitPrice * quantity}
     * @param currency  ISO code
     */
    public record Quote(String sku, int quantity, double unitPrice, double total, String currency) {}
}
