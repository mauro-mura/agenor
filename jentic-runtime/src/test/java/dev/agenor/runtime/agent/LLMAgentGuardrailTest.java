package dev.agenor.runtime.agent;

import dev.agenor.core.guardrail.GuardrailContext;
import dev.agenor.core.guardrail.GuardrailResult;
import dev.agenor.core.guardrail.GuardrailViolationException;
import dev.agenor.core.guardrail.InputGuardrail;
import dev.agenor.core.guardrail.OutputGuardrail;
import dev.agenor.core.llm.LLMMessage;
import dev.agenor.core.llm.LLMProvider;
import dev.agenor.core.llm.LLMRequest;
import dev.agenor.core.llm.LLMResponse;
import dev.agenor.runtime.guardrail.GuardrailChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for the guardrail integration in {@link LLMAgent}
 *
 * <p>LLMAgent does not define {@code callLLM} — it exposes
 * {@link LLMAgent#applyInputGuardrails} and {@link LLMAgent#applyOutputGuardrails}
 * as protected hooks. Subclasses call these hooks around their own
 * {@code llmProvider.chat()} invocations.
 *
 * <p>The inner {@code TestAgent} simulates this exact pattern.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LLMAgent — Guardrail Integration")
class LLMAgentGuardrailTest {

    @Mock
    private LLMProvider mockProvider;

    // -------------------------------------------------------------------------
    // Concrete subclass that simulates real callLLM with guardrail hooks
    // -------------------------------------------------------------------------

    private class TestAgent extends LLMAgent {

        private final LLMProvider provider;

        TestAgent(LLMProvider provider) {
            super("guardrail-test-agent");
            this.provider = provider;
        }

        /**
         * Mirrors the pattern documented in LLMAgent Javadoc:
         * 1. applyInputGuardrails (may throw or modify)
         * 2. provider.chat() — called only if input was not blocked
         * 3. applyOutputGuardrails (may throw or modify)
         */
        String callWithGuardrails(String userPrompt) throws GuardrailViolationException {
            GuardrailContext ctx = GuardrailContext.of(getAgentId());

            String safeInput = applyInputGuardrails(userPrompt, ctx);

            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user(safeInput))
                    .build();
            String rawOutput = provider.chat(request).join().content();

            return applyOutputGuardrails(rawOutput, ctx);
        }
    }

    // -------------------------------------------------------------------------
    // Setup helpers
    // -------------------------------------------------------------------------

    private TestAgent agent;

    @BeforeEach
    void setUp() {
        agent = new TestAgent(mockProvider);
    }

    private void mockLLMResponse(String content) {
        LLMResponse response = LLMResponse.builder("mock-id", "test-model")
                .content(content)
                .role(LLMMessage.Role.ASSISTANT)
                .build();
        when(mockProvider.chat(any(LLMRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(response));
    }

    private static InputGuardrail inputBlocking(String reason) {
        return (in, ctx) -> CompletableFuture.completedFuture(new GuardrailResult.Blocked(reason));
    }

    private static InputGuardrail inputModifying(String replacement) {
        return (in, ctx) -> CompletableFuture.completedFuture(new GuardrailResult.Modified(replacement));
    }

    private static OutputGuardrail outputBlocking(String reason) {
        return (out, ctx) -> CompletableFuture.completedFuture(new GuardrailResult.Blocked(reason));
    }

    private static OutputGuardrail outputModifying(String replacement) {
        return (out, ctx) -> CompletableFuture.completedFuture(new GuardrailResult.Modified(replacement));
    }

    // -------------------------------------------------------------------------
    // No chain configured — backward compat
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("No guardrail chain configured")
    class NoGuardrailChain {

        @Test
        @DisplayName("pipeline works unchanged when no chain is set")
        void noChain_pipelineUnchanged() throws GuardrailViolationException {
            mockLLMResponse("LLM answer");
            assertThat(agent.callWithGuardrails("user prompt")).isEqualTo("LLM answer");
            verify(mockProvider, times(1)).chat(any());
        }

        @Test
        @DisplayName("getGuardrailChain() returns null when unset")
        void noChain_getterReturnsNull() {
            assertThat(agent.getGuardrailChain()).isNull();
        }
    }

    // -------------------------------------------------------------------------
    // Input blocked → LLM not called
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Input guardrail blocks")
    class InputBlocked {

        @Test
        @DisplayName("blocked input → GuardrailViolationException, LLM not called")
        void inputBlocked_llmNotCalled() {
            agent.setGuardrailChain(GuardrailChain.builder()
                    .addInput(inputBlocking("PII detected"))
                    .build());

            assertThatThrownBy(() -> agent.callWithGuardrails("user@example.com"))
                    .isInstanceOf(GuardrailViolationException.class)
                    .satisfies(ex -> assertThat(((GuardrailViolationException) ex).reason())
                            .isEqualTo("PII detected"));

            verify(mockProvider, never()).chat(any());
        }

        @Test
        @DisplayName("exception carries non-blank blockedBy")
        void inputBlocked_hasBlockedBy() {
            agent.setGuardrailChain(GuardrailChain.builder()
                    .addInput(inputBlocking("policy"))
                    .build());

            assertThatThrownBy(() -> agent.callWithGuardrails("bad"))
                    .isInstanceOf(GuardrailViolationException.class)
                    .satisfies(ex -> assertThat(((GuardrailViolationException) ex).blockedBy())
                            .isNotBlank());
        }
    }

    // -------------------------------------------------------------------------
    // Input modified → LLM receives modified content
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Input guardrail modifies")
    class InputModified {

        @Test
        @DisplayName("modified input → LLM request contains modified content")
        void inputModified_llmReceivesModified() throws GuardrailViolationException {
            mockLLMResponse("response");
            agent.setGuardrailChain(GuardrailChain.builder()
                    .addInput(inputModifying("REDACTED INPUT"))
                    .build());

            agent.callWithGuardrails("original input");

            verify(mockProvider).chat(argThat(req ->
                    req.messages().stream().anyMatch(m ->
                            m.role() == LLMMessage.Role.USER
                            && "REDACTED INPUT".equals(m.content()))));
        }
    }

    // -------------------------------------------------------------------------
    // Output modified → consumer receives modified content
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Output guardrail modifies")
    class OutputModified {

        @Test
        @DisplayName("modified output → consumer receives the modified content")
        void outputModified_consumerReceivesModified() throws GuardrailViolationException {
            mockLLMResponse("raw LLM output");
            agent.setGuardrailChain(GuardrailChain.builder()
                    .addOutput(outputModifying("[SANITIZED]"))
                    .build());

            assertThat(agent.callWithGuardrails("prompt")).isEqualTo("[SANITIZED]");
        }
    }

    // -------------------------------------------------------------------------
    // Output blocked
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Output guardrail blocks")
    class OutputBlocked {

        @Test
        @DisplayName("blocked output → GuardrailViolationException thrown")
        void outputBlocked_exceptionThrown() {
            mockLLMResponse("unsafe output");
            agent.setGuardrailChain(GuardrailChain.builder()
                    .addOutput(outputBlocking("unsafe content"))
                    .build());

            assertThatThrownBy(() -> agent.callWithGuardrails("prompt"))
                    .isInstanceOf(GuardrailViolationException.class)
                    .satisfies(ex -> assertThat(((GuardrailViolationException) ex).reason())
                            .isEqualTo("unsafe content"));
        }
    }

    // -------------------------------------------------------------------------
    // Helper methods directly
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("applyInputGuardrails / applyOutputGuardrails")
    class HelpersTests {

        @Test
        @DisplayName("applyInputGuardrails — no chain → input unchanged")
        void applyInput_noChain() throws GuardrailViolationException {
            assertThat(agent.applyInputGuardrails("hello", GuardrailContext.of("x")))
                    .isEqualTo("hello");
        }

        @Test
        @DisplayName("applyOutputGuardrails — no chain → output unchanged")
        void applyOutput_noChain() throws GuardrailViolationException {
            assertThat(agent.applyOutputGuardrails("response", GuardrailContext.of("x")))
                    .isEqualTo("response");
        }

        @Test
        @DisplayName("applyInputGuardrails — chain with modifier → modified value")
        void applyInput_modified() throws GuardrailViolationException {
            agent.setGuardrailChain(GuardrailChain.builder().addInput(inputModifying("SAFE")).build());
            assertThat(agent.applyInputGuardrails("raw", GuardrailContext.of("x"))).isEqualTo("SAFE");
        }

        @Test
        @DisplayName("applyOutputGuardrails — chain with modifier → modified value")
        void applyOutput_modified() throws GuardrailViolationException {
            agent.setGuardrailChain(GuardrailChain.builder().addOutput(outputModifying("[OK]")).build());
            assertThat(agent.applyOutputGuardrails("dirty", GuardrailContext.of("x"))).isEqualTo("[OK]");
        }
    }

    // -------------------------------------------------------------------------
    // setGuardrailChain / getGuardrailChain
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("setGuardrailChain(null) removes chain — pipeline unchanged")
    void setChainNull_removesChain() throws GuardrailViolationException {
        agent.setGuardrailChain(GuardrailChain.builder().addInput(inputBlocking("x")).build());
        agent.setGuardrailChain(null);

        mockLLMResponse("answer");
        assertThat(agent.callWithGuardrails("prompt")).isEqualTo("answer");
    }

    @Test
    @DisplayName("getGuardrailChain() returns the injected chain")
    void getChain_returnsInjected() {
        GuardrailChain chain = GuardrailChain.builder().build();
        agent.setGuardrailChain(chain);
        assertThat(agent.getGuardrailChain()).isSameAs(chain);
    }

    // -------------------------------------------------------------------------
    // GuardrailContext carries agentId
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GuardrailContext passed to guardrails contains the agent ID")
    void guardrailContext_carriesAgentId() throws GuardrailViolationException {
        AtomicInteger callCount = new AtomicInteger();
        agent.setGuardrailChain(GuardrailChain.builder()
                .addInput((input, ctx) -> {
                    assertThat(ctx.agentId()).isEqualTo("guardrail-test-agent");
                    callCount.incrementAndGet();
                    return CompletableFuture.completedFuture(new GuardrailResult.Passed());
                })
                .build());
        mockLLMResponse("ok");

        agent.callWithGuardrails("hello");
        assertThat(callCount.get()).isEqualTo(1);
    }
}
