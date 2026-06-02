package dev.agenor.examples.observability;

import dev.agenor.adapters.telemetry.OtelTelemetryFactory;
import dev.agenor.core.guardrail.GuardrailContext;
import dev.agenor.core.guardrail.GuardrailResult;
import dev.agenor.core.guardrail.GuardrailViolationException;
import dev.agenor.core.llm.LLMMessage;
import dev.agenor.core.llm.LLMProvider;
import dev.agenor.core.llm.LLMRequest;
import dev.agenor.core.llm.LLMResponse;
import dev.agenor.core.telemetry.JenticTelemetry;
import dev.agenor.runtime.JenticRuntime;
import dev.agenor.runtime.agent.LLMAgent;
import dev.agenor.runtime.guardrail.GuardrailChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Observability Example — demonstrates OpenTelemetry integration (ADR-019).
 *
 * <p>Shows how to enable distributed tracing by adding a single
 * {@link JenticTelemetry} instance to the runtime:
 *
 * <pre>{@code
 * JenticTelemetry telemetry = OtelTelemetryFactory.builder()
 *     .serviceName("my-agent")
 *     .exporter("otlp-http")
 *     .endpoint("http://localhost:4318")
 *     .build();
 *
 * JenticRuntime runtime = JenticRuntime.builder()
 *     .telemetry(telemetry)
 *     .build();
 * }</pre>
 *
 * <p>What this example traces:
 * <ul>
 *   <li>{@code llm.chat} — every LLM call with provider, model, token counts and latency</li>
 *   <li>{@code guardrail.evaluate} — every input/output guardrail evaluation</li>
 *   <li>{@code behavior.execute} — every behavior execution tick</li>
 * </ul>
 *
 * <p>Prerequisites for viewing traces:
 * <pre>
 *   docker compose up            # starts Jaeger all-in-one
 *   # then open http://localhost:16686
 * </pre>
 *
 * <p>When no OTel collector is running, set exporter to {@code "none"} (or omit it):
 * the runtime falls back to noop telemetry with zero overhead.
 *
 * @see OtelTelemetryFactory
 * @see JenticTelemetry
 * @since 0.19.0
 */
public class ObservabilityExample {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityExample.class);

    public static void main(String[] args) throws InterruptedException {

        // ------------------------------------------------------------------
        // 1. Build telemetry
        //    - "none" → noop (safe default, no collector required)
        //    - "otlp-http" → exports to Jaeger / any OTLP collector
        // ------------------------------------------------------------------
        String exporterType = System.getenv().getOrDefault("OTEL_EXPORTER_TYPE", "otlp-http");
        String endpoint     = System.getenv().getOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT",
                                                           "http://localhost:4318");
        String serviceName  = System.getenv().getOrDefault("OTEL_SERVICE_NAME", "agenor-example");

        JenticTelemetry telemetry = OtelTelemetryFactory.builder()
                .serviceName(serviceName)
                .exporter(exporterType)
                .endpoint(endpoint)
                .build();

        log.info("Telemetry configured: exporter={}, endpoint={}", exporterType, endpoint);

        // ------------------------------------------------------------------
        // 2. Build a mock LLM provider (no real API key required)
        // ------------------------------------------------------------------
        LLMProvider mockProvider = new MockLLMProvider();

        // ------------------------------------------------------------------
        // 3. Build the runtime with telemetry
        //    InstrumentedLLMProvider is applied automatically when an LLMAgent
        //    is registered (via LLMAgent.installTelemetry()).
        // ------------------------------------------------------------------
        JenticRuntime runtime = JenticRuntime.builder()
                .telemetry(telemetry)
                .build();

        runtime.start().join();

        // ------------------------------------------------------------------
        // 4. Create and register an LLM agent with a guardrail chain
        // ------------------------------------------------------------------
        ObservableAgent agent = new ObservableAgent(mockProvider);
        runtime.registerAgent(agent);

        // ------------------------------------------------------------------
        // 5. Trigger some LLM calls and guardrail evaluations
        // ------------------------------------------------------------------
        log.info("Simulating agent interactions...");

        for (int i = 1; i <= 3; i++) {
            String userInput = "Tell me about topic number " + i;
            log.info("Calling agent (iteration {}): '{}'", i, userInput);
            agent.handleQuery(userInput);
        }

        // Also demonstrate a blocked guardrail
        log.info("Triggering guardrail block...");
        try {
            agent.handleQuery("my SSN is 123-45-6789");
        } catch (GuardrailViolationException e) {
            log.warn("Guardrail blocked: {}", e.getMessage());
        }

        // ------------------------------------------------------------------
        // 6. Graceful shutdown — runtime.stop() closes OtelJenticTelemetry
        //    which calls OpenTelemetrySdk.close(), forcing the BatchSpanProcessor
        //    to flush all buffered spans before the process exits.
        // ------------------------------------------------------------------
        runtime.stop().join();

        log.info("Done. Open http://localhost:16686 to view traces in Jaeger.");
    }

    // -------------------------------------------------------------------------
    // Observable agent
    // -------------------------------------------------------------------------

    static class ObservableAgent extends LLMAgent {

        private final LLMProvider provider;
        private final GuardrailChain guardrailChain;

        ObservableAgent(LLMProvider provider) {
            super("observable-agent", "ObservableAgent");
            this.provider = provider;
            this.setLLMProvider(provider);

            // Guardrail: block inputs that contain SSN-like patterns
            this.guardrailChain = GuardrailChain.builder()
                    .addInput((input, ctx) -> {
                        if (input.matches(".*\\d{3}-\\d{2}-\\d{4}.*")) {
                            return CompletableFuture.completedFuture(
                                    new GuardrailResult.Blocked("SSN detected in input"));
                        }
                        return CompletableFuture.completedFuture(new GuardrailResult.Passed());
                    })
                    .build();
            this.setGuardrailChain(guardrailChain);
        }

        @Override
        public String getAgentId() { return "observable-agent"; }

        @Override
        public String getAgentName() { return "ObservableAgent"; }

        /**
         * Handles a user query: runs guardrails, calls LLM, logs the response.
         */
        void handleQuery(String userInput) {
            GuardrailContext ctx = GuardrailContext.of(getAgentId(), "query");
            String safeInput = applyInputGuardrails(userInput, ctx);

            LLMRequest request = LLMRequest.builder()
                    .model("mock-model")
                    .userMessage(safeInput)
                    .build();

            LLMResponse response = provider.chat(request).join();
            String safeOutput = applyOutputGuardrails(response.content(), ctx);

            log.info("Response: {}", safeOutput);
        }
    }

    // -------------------------------------------------------------------------
    // Mock LLM provider (no network calls)
    // -------------------------------------------------------------------------

    static class MockLLMProvider implements LLMProvider {

        private final AtomicInteger callCount = new AtomicInteger();

        @Override
        public CompletableFuture<LLMResponse> chat(LLMRequest request) {
            int call = callCount.incrementAndGet();
            String content = "Mock response #" + call + " for: " + extractUserMessage(request);
            LLMResponse response = LLMResponse.builder("mock-" + call, "mock-model")
                    .content(content)
                    .usage(10 * call, 20 * call, 30 * call)
                    .finishReason("stop")
                    .build();
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public CompletableFuture<Void> chatStream(LLMRequest request,
                java.util.function.Consumer<dev.agenor.core.llm.StreamingChunk> handler) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public String getProviderName() { return "mock"; }

        @Override
        public String getDefaultModel() { return "mock-model"; }

        @Override
        public boolean supportsFunctionCalling() { return false; }

        @Override
        public boolean supportsStreaming() { return false; }

        @Override
        public CompletableFuture<List<String>> getAvailableModels() {
            return CompletableFuture.completedFuture(List.of("mock-model"));
        }

        private static String extractUserMessage(LLMRequest req) {
            return req.messages().stream()
                    .filter(m -> m.role() == LLMMessage.Role.USER)
                    .map(LLMMessage::content)
                    .findFirst()
                    .orElse("(empty)");
        }
    }
}
