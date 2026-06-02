package dev.agenor.examples.guardrail;

import dev.agenor.core.guardrail.GuardrailContext;
import dev.agenor.core.guardrail.GuardrailResult;
import dev.agenor.core.guardrail.GuardrailViolationException;
import dev.agenor.core.guardrail.InputGuardrail;
import dev.agenor.core.guardrail.OutputGuardrail;
import dev.agenor.core.guardrail.WithGuardrails;
import dev.agenor.core.llm.LLMMessage;
import dev.agenor.core.llm.LLMProvider;
import dev.agenor.core.llm.LLMRequest;
import dev.agenor.core.llm.LLMResponse;
import dev.agenor.runtime.AgenorRuntime;
import dev.agenor.runtime.agent.LLMAgent;
import dev.agenor.runtime.guardrail.ContentPolicyGuardrail;
import dev.agenor.runtime.guardrail.GuardrailChain;
import dev.agenor.runtime.guardrail.PiiRedactionGuardrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;

/**
 * Guardrails Layer example — demonstrates the Guardrails Layer (ADR-014).
 *
 * <p>Scenarios shown:
 * <ol>
 *   <li><b>Input PII redaction</b> — user sends email and IBAN; both are redacted
 *       before reaching the LLM.</li>
 *   <li><b>Output content policy</b> — LLM response containing a blocked keyword
 *       is intercepted and the consumer receives a {@link GuardrailViolationException}.</li>
 *   <li><b>Declarative wiring via {@code @WithGuardrails}</b> — annotation-configured
 *       agent receives its chain automatically from {@code AgenorRuntime}.</li>
 *   <li><b>Programmatic chain builder</b> — explicit builder API for agents that
 *       need constructor-configured guardrails (e.g. YAML policy path).</li>
 * </ol>
 *
 * <p>Run:
 * <pre>{@code
 * mvn exec:java -pl agenor-examples \
 *     -Dexec.mainClass=dev.agenor.examples.GuardrailsExample
 * }</pre>
 *
 * <p>No real LLM API key required — a stub provider simulates LLM responses.
 *
 * @since 0.13.0
 */
public class GuardrailsExample {

    private static final Logger log = LoggerFactory.getLogger(GuardrailsExample.class);

    // -------------------------------------------------------------------------
    // Stub LLM provider — no API key needed
    // -------------------------------------------------------------------------

    /** Returns a fixed response regardless of the input. */
    private static LLMProvider stubProvider(String fixedResponse) {
        return new LLMProvider() {
            @Override
            public CompletableFuture<LLMResponse> chat(LLMRequest request) {
                return CompletableFuture.completedFuture(
                        LLMResponse.builder("stub-id", "stub-model")
                                .content(fixedResponse)
                                .role(LLMMessage.Role.ASSISTANT)
                                .build());
            }
            @Override
            public CompletableFuture<Void> chatStream(
                    LLMRequest req,
                    java.util.function.Consumer<dev.agenor.core.llm.StreamingChunk> h) {
                return CompletableFuture.completedFuture(null);
            }
            @Override
            public CompletableFuture<java.util.List<String>> getAvailableModels() {
                return CompletableFuture.completedFuture(java.util.List.of("stub-model"));
            }
            @Override
            public String getProviderName() { return "Stub"; }
        };
    }

    // -------------------------------------------------------------------------
    // Finance agent (annotation-based wiring)
    // -------------------------------------------------------------------------

    /**
     * Finance agent that declares its guardrails via {@code @WithGuardrails}.
     *
     * <p>{@link PiiRedactionGuardrail} is applied to inputs (email, phone, IBAN, CC, CF).
     * {@code AgenorRuntime} instantiates and injects the chain automatically at
     * registration time.
     *
     * <p>Note: {@link ContentPolicyGuardrail} requires a YAML path constructor —
     * it cannot be used in {@code @WithGuardrails} and must be added programmatically.
     */
    @WithGuardrails(input = { PiiRedactionGuardrail.class })
    static class FinanceAgent extends LLMAgent {

        private final LLMProvider provider;

        FinanceAgent(LLMProvider provider) {
            super("finance-agent");
            this.provider = provider;
        }

        String ask(String userInput) throws GuardrailViolationException {
            GuardrailContext ctx = GuardrailContext.of(getAgentId(), "finance");

            // Pre-input guardrail hook — PII is redacted here
            String safeInput = applyInputGuardrails(userInput, ctx);

            // LLM call
            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user(safeInput))
                    .build();
            String rawOutput = provider.chat(request).join().content();

            // Post-output guardrail hook — content policy check
            return applyOutputGuardrails(rawOutput, ctx);
        }
    }

    // -------------------------------------------------------------------------
    // Main
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        log.info("=== Guardrails Layer Example ===\n");

        scenario1_piiRedaction();
        scenario2_outputPolicyBlock();
        scenario3_declarativeWiring();
        scenario4_programmaticChain();

        log.info("\n=== Example complete ===");
    }

    // -------------------------------------------------------------------------
    // Scenario 1: PII redaction on input
    // -------------------------------------------------------------------------

    private static void scenario1_piiRedaction() throws GuardrailViolationException {
        log.info("--- Scenario 1: PII redaction on input ---");

        PiiRedactionGuardrail pii = new PiiRedactionGuardrail(
                EnumSet.of(PiiRedactionGuardrail.PiiType.EMAIL,
                           PiiRedactionGuardrail.PiiType.IBAN));

        GuardrailChain chain = GuardrailChain.builder()
                .addInput(pii)
                .build();

        FinanceAgent agent = new FinanceAgent(stubProvider("Thank you for your query."));
        agent.setGuardrailChain(chain);

        String userInput = "Please check my account. My email is mario@example.it "
                + "and IBAN IT60X0542811101000000123456.";

        log.info("User input : {}", userInput);

        String response = agent.ask(userInput);
        log.info("LLM response: {}\n", response);

        // Verify redaction happened — input to LLM should not contain the originals
        // (in a real test this is asserted; here we log for demonstration)
        log.info(">>> PII in user input was redacted before reaching the LLM.\n");
    }

    // -------------------------------------------------------------------------
    // Scenario 2: Output blocked by content policy
    // -------------------------------------------------------------------------

    private static void scenario2_outputPolicyBlock() throws IOException {
        log.info("--- Scenario 2: Output content policy block ---");

        // Write a temporary policy YAML
        Path policyFile = Files.createTempFile("agenor-policy-", ".yaml");
        Files.writeString(policyFile, """
                content-policy:
                  blocked-patterns:
                    - pattern: "(?i)guaranteed return"
                      reason: "Prohibited financial promise — regulatory compliance"
                """);

        try {
            ContentPolicyGuardrail contentPolicy =
                    new ContentPolicyGuardrail(policyFile.toString());

            GuardrailChain chain = GuardrailChain.builder()
                    .addOutput(contentPolicy)
                    .build();

            // Stub LLM that returns a response violating policy
            FinanceAgent agent = new FinanceAgent(
                    stubProvider("Our fund offers a guaranteed return of 15% per year."));
            agent.setGuardrailChain(chain);

            try {
                String response = agent.ask("Tell me about your investment products.");
                log.info("Response (should not reach here): {}", response);
            } catch (GuardrailViolationException e) {
                log.info("Output BLOCKED by '{}': {}", e.blockedBy(), e.reason());
                log.info(">>> Consumer never receives the policy-violating response.\n");
            }

        } finally {
            Files.deleteIfExists(policyFile);
        }
    }

    // -------------------------------------------------------------------------
    // Scenario 3: Declarative @WithGuardrails wiring via AgenorRuntime
    // -------------------------------------------------------------------------

    private static void scenario3_declarativeWiring() throws GuardrailViolationException {
        log.info("--- Scenario 3: @WithGuardrails declarative wiring ---");

        AgenorRuntime runtime = AgenorRuntime.builder().build();

        FinanceAgent agent = new FinanceAgent(
                stubProvider("Your request has been processed."));
        runtime.registerAgent(agent);

        // AgenorRuntime has automatically injected the PiiRedactionGuardrail
        // declared on @WithGuardrails(input = { PiiRedactionGuardrail.class })
        boolean chainInjected = agent.getGuardrailChain() != null
                && !agent.getGuardrailChain().hasNoInputGuardrails();

        log.info("GuardrailChain injected by runtime: {}", chainInjected);

        String response = agent.ask("My CF is RSSMRA85M01H501Z, please verify.");
        log.info("Response: {}", response);
        log.info(">>> Codice Fiscale was redacted before reaching the LLM.\n");

        runtime.stop();
    }

    // -------------------------------------------------------------------------
    // Scenario 4: Programmatic GuardrailChain builder
    // -------------------------------------------------------------------------

    private static void scenario4_programmaticChain() throws GuardrailViolationException {
        log.info("--- Scenario 4: Programmatic GuardrailChain builder ---");

        // Custom inline guardrail: block requests longer than 200 chars
        InputGuardrail lengthLimit = (input, ctx) -> {
            if (input.length() > 200) {
                return CompletableFuture.completedFuture(
                        new GuardrailResult.Blocked("Input exceeds 200 characters"));
            }
            return CompletableFuture.completedFuture(new GuardrailResult.Passed());
        };

        // Custom inline output guardrail: append a disclaimer
        OutputGuardrail disclaimer = (output, ctx) ->
                CompletableFuture.completedFuture(
                        new GuardrailResult.Modified(
                                output + " [Not financial advice]"));

        GuardrailChain chain = GuardrailChain.builder()
                .addInput(new PiiRedactionGuardrail())
                .addInput(lengthLimit)
                .addOutput(disclaimer)
                .build();

        FinanceAgent agent = new FinanceAgent(stubProvider("Stocks can be risky."));
        agent.setGuardrailChain(chain);

        // Short input — passes
        String response = agent.ask("What is a stock?");
        log.info("Short input response: {}", response);

        // Long input — blocked
        String longInput = "Tell me about ".repeat(20);
        try {
            agent.ask(longInput);
        } catch (GuardrailViolationException e) {
            log.info("Long input BLOCKED: {}", e.reason());
        }

        log.info(">>> Output modified with disclaimer; long input blocked.\n");
    }
}
