package dev.agenor.examples.observability;

import dev.agenor.adapters.telemetry.OtelTelemetryFactory;
import dev.agenor.core.guardrail.GuardrailContext;
import dev.agenor.core.guardrail.GuardrailResult;
import dev.agenor.core.guardrail.GuardrailViolationException;
import dev.agenor.core.llm.LLMProvider;
import dev.agenor.core.llm.LLMRequest;
import dev.agenor.core.llm.LLMResponse;
import dev.agenor.core.telemetry.AgenorTelemetry;
import dev.agenor.runtime.AgenorRuntime;
import dev.agenor.runtime.llm.LLMAgent;
import dev.agenor.runtime.llm.ScriptedLLMProvider;
import dev.agenor.runtime.guardrail.GuardrailChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Observability Example — demonstrates OpenTelemetry integration (ADR-019).
 *
 * <p>Shows how to enable distributed tracing by adding a single
 * {@link AgenorTelemetry} instance to the runtime:
 *
 * <pre>{@code
 * AgenorTelemetry telemetry = OtelTelemetryFactory.builder()
 *     .serviceName("my-agent")
 *     .exporter("otlp-http")
 *     .endpoint("http://localhost:4318")
 *     .build();
 *
 * AgenorRuntime runtime = AgenorRuntime.builder()
 *     .telemetry(telemetry)
 *     .build();
 * }</pre>
 *
 * <p>What this example traces: {@code guardrail.evaluate}, one span per input or output guardrail
 * evaluation, four in all. It does not trace its LLM calls: the agent calls the provider it was
 * given, and the runtime instruments only a copy that a subclass of {@link LLMAgent} cannot reach,
 * so no {@code llm.chat} span is emitted. {@code docs/observability.md} describes the gap and the
 * workaround. The agent has no behavior, so there is no {@code behavior.execute} either.
 *
 * <p>Prerequisites for viewing traces:
 * <pre>
 *   cd agenor-examples/src/main/resources/observability
 *   docker compose up -d         # starts Jaeger all-in-one; the root compose.yml does not
 *   # then open http://localhost:16686, service "agenor-example"
 * </pre>
 *
 * <p>When no OTel collector is running, set exporter to {@code "none"} (or omit it):
 * the runtime falls back to noop telemetry with zero overhead.
 *
 * @see OtelTelemetryFactory
 * @see AgenorTelemetry
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

        AgenorTelemetry telemetry = OtelTelemetryFactory.builder()
                .serviceName(serviceName)
                .exporter(exporterType)
                .endpoint(endpoint)
                .build();

        log.info("Telemetry configured: exporter={}, endpoint={}", exporterType, endpoint);

        // ------------------------------------------------------------------
        // 2. A scripted LLM provider: no network, no API key. It reports
        //    estimated token counts, which an llm.chat span would carry.
        // ------------------------------------------------------------------
        LLMProvider provider = ScriptedLLMProvider.create()
                .orElse(request -> "Scripted response for: " + request.messages().get(0).content());

        // ------------------------------------------------------------------
        // 3. Build the runtime with telemetry
        //    LLMAgent.installTelemetry() wraps the agent's provider in
        //    InstrumentedLLMProvider at registration, but handleQuery() below
        //    calls the unwrapped one, so its calls are not traced.
        // ------------------------------------------------------------------
        AgenorRuntime runtime = AgenorRuntime.builder()
                .telemetry(telemetry)
                .build();

        runtime.start().join();

        // ------------------------------------------------------------------
        // 4. Create and register an LLM agent with a guardrail chain
        // ------------------------------------------------------------------
        ObservableAgent agent = new ObservableAgent(provider);
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
        // 6. Graceful shutdown — runtime.stop() closes OtelAgenorTelemetry
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
                    .model(ScriptedLLMProvider.MODEL)
                    .userMessage(safeInput)
                    .build();

            LLMResponse response = provider.chat(request).join();
            String safeOutput = applyOutputGuardrails(response.content(), ctx);

            log.info("Response: {}", safeOutput);
        }
    }
}
