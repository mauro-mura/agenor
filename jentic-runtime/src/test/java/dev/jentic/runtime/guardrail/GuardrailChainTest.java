package dev.jentic.runtime.guardrail;

import dev.jentic.core.guardrail.GuardrailContext;
import dev.jentic.core.guardrail.GuardrailResult;
import dev.jentic.core.guardrail.GuardrailViolationException;
import dev.jentic.core.guardrail.InputGuardrail;
import dev.jentic.core.guardrail.OutputGuardrail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

@DisplayName("GuardrailChain")
class GuardrailChainTest {

    private static final GuardrailContext CTX = GuardrailContext.of("test-agent", "test");

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static InputGuardrail inputPassed() {
        return (input, ctx) -> CompletableFuture.completedFuture(new GuardrailResult.Passed());
    }

    private static InputGuardrail inputModified(String replacement) {
        return (input, ctx) -> CompletableFuture.completedFuture(new GuardrailResult.Modified(replacement));
    }

    private static InputGuardrail inputBlocked(String reason) {
        return (input, ctx) -> CompletableFuture.completedFuture(new GuardrailResult.Blocked(reason));
    }

    private static OutputGuardrail outputPassed() {
        return (output, ctx) -> CompletableFuture.completedFuture(new GuardrailResult.Passed());
    }

    private static OutputGuardrail outputModified(String replacement) {
        return (output, ctx) -> CompletableFuture.completedFuture(new GuardrailResult.Modified(replacement));
    }

    private static OutputGuardrail outputBlocked(String reason) {
        return (output, ctx) -> CompletableFuture.completedFuture(new GuardrailResult.Blocked(reason));
    }

    /** Guardrail that records every content value it receives. */
    private static class CapturingInputGuardrail implements InputGuardrail {
        final List<String> received = Collections.synchronizedList(new ArrayList<>());

        @Override
        public CompletableFuture<GuardrailResult> apply(String input, GuardrailContext ctx) {
            received.add(input);
            return CompletableFuture.completedFuture(new GuardrailResult.Passed());
        }
    }

    // -------------------------------------------------------------------------
    // Input chain tests
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Input chain")
    class InputChainTests {

        @Test
        @DisplayName("empty chain returns content unchanged")
        void emptyChain_passThrough() throws GuardrailViolationException {
            GuardrailChain chain = GuardrailChain.builder().build();
            assertThat(chain.applyInput("hello", CTX)).isEqualTo("hello");
        }

        @Test
        @DisplayName("3 Passed guardrails — content unchanged")
        void allPassed_contentUnchanged() throws GuardrailViolationException {
            GuardrailChain chain = GuardrailChain.builder()
                    .addInput(inputPassed())
                    .addInput(inputPassed())
                    .addInput(inputPassed())
                    .build();
            assertThat(chain.applyInput("original", CTX)).isEqualTo("original");
        }

        @Test
        @DisplayName("second guardrail returns Modified — third receives modified content")
        void secondModified_thirdReceivesNewContent() throws GuardrailViolationException {
            var capturing = new CapturingInputGuardrail();
            GuardrailChain chain = GuardrailChain.builder()
                    .addInput(inputPassed())
                    .addInput(inputModified("MODIFIED"))
                    .addInput(capturing)
                    .build();
            String result = chain.applyInput("original", CTX);
            assertThat(result).isEqualTo("MODIFIED");
            assertThat(capturing.received).containsExactly("MODIFIED");
        }

        @Test
        @DisplayName("second guardrail returns Blocked — third is NOT invoked, exception thrown")
        void secondBlocked_thirdNotInvoked() {
            AtomicInteger thirdInvocations = new AtomicInteger(0);
            InputGuardrail third = (input, ctx) -> {
                thirdInvocations.incrementAndGet();
                return CompletableFuture.completedFuture(new GuardrailResult.Passed());
            };

            GuardrailChain chain = GuardrailChain.builder()
                    .addInput(inputPassed())
                    .addInput(inputBlocked("policy violation"))
                    .addInput(third)
                    .build();

            assertThatThrownBy(() -> chain.applyInput("bad content", CTX))
                    .isInstanceOf(GuardrailViolationException.class)
                    .satisfies(ex -> {
                        var gve = (GuardrailViolationException) ex;
                        assertThat(gve.reason()).isEqualTo("policy violation");
                    });

            assertThat(thirdInvocations.get()).isZero();
        }

        @Test
        @DisplayName("Blocked exception carries correct blockedBy class name")
        void blocked_carriesGuardrailClassName() {
            GuardrailChain chain = GuardrailChain.builder()
                    .addInput(inputBlocked("pii detected"))
                    .build();

            assertThatThrownBy(() -> chain.applyInput("email@example.com", CTX))
                    .isInstanceOf(GuardrailViolationException.class)
                    .satisfies(ex -> {
                        var gve = (GuardrailViolationException) ex;
                        assertThat(gve.blockedBy()).isNotBlank();
                    });
        }

        @Test
        @DisplayName("chain with only Passed + Modified applies all transformations in order")
        void chainedModifications_appliedInOrder() throws GuardrailViolationException {
            GuardrailChain chain = GuardrailChain.builder()
                    .addInput((input, ctx) -> CompletableFuture.completedFuture(
                            new GuardrailResult.Modified(input + "-A")))
                    .addInput((input, ctx) -> CompletableFuture.completedFuture(
                            new GuardrailResult.Modified(input + "-B")))
                    .build();
            assertThat(chain.applyInput("X", CTX)).isEqualTo("X-A-B");
        }
    }

    // -------------------------------------------------------------------------
    // Output chain tests
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Output chain")
    class OutputChainTests {

        @Test
        @DisplayName("empty chain returns content unchanged")
        void emptyChain_passThrough() throws GuardrailViolationException {
            GuardrailChain chain = GuardrailChain.builder().build();
            assertThat(chain.applyOutput("llm response", CTX)).isEqualTo("llm response");
        }

        @Test
        @DisplayName("3 Passed guardrails — content unchanged")
        void allPassed_contentUnchanged() throws GuardrailViolationException {
            GuardrailChain chain = GuardrailChain.builder()
                    .addOutput(outputPassed())
                    .addOutput(outputPassed())
                    .addOutput(outputPassed())
                    .build();
            assertThat(chain.applyOutput("response", CTX)).isEqualTo("response");
        }

        @Test
        @DisplayName("Modified output — consumer receives modified content")
        void modifiedOutput_consumerReceivesNew() throws GuardrailViolationException {
            GuardrailChain chain = GuardrailChain.builder()
                    .addOutput(outputModified("[SANITIZED]"))
                    .build();
            assertThat(chain.applyOutput("raw llm output", CTX)).isEqualTo("[SANITIZED]");
        }

        @Test
        @DisplayName("Blocked output — GuardrailViolationException thrown")
        void blockedOutput_exceptionThrown() {
            GuardrailChain chain = GuardrailChain.builder()
                    .addOutput(outputBlocked("unsafe content"))
                    .build();
            assertThatThrownBy(() -> chain.applyOutput("dangerous output", CTX))
                    .isInstanceOf(GuardrailViolationException.class)
                    .satisfies(ex -> assertThat(((GuardrailViolationException) ex).reason())
                            .isEqualTo("unsafe content"));
        }
    }

    // -------------------------------------------------------------------------
    // Builder tests
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("null input guardrail throws NullPointerException")
        void nullInput_throws() {
            assertThatThrownBy(() -> GuardrailChain.builder().addInput(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null output guardrail throws NullPointerException")
        void nullOutput_throws() {
            assertThatThrownBy(() -> GuardrailChain.builder().addOutput(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("hasNoInputGuardrails / hasNoOutputGuardrails reflect chain state")
        void emptyChainFlags() {
            GuardrailChain chain = GuardrailChain.builder().build();
            assertThat(chain.hasNoInputGuardrails()).isTrue();
            assertThat(chain.hasNoOutputGuardrails()).isTrue();

            GuardrailChain withInput = GuardrailChain.builder().addInput(inputPassed()).build();
            assertThat(withInput.hasNoInputGuardrails()).isFalse();
            assertThat(withInput.hasNoOutputGuardrails()).isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // Concurrency test
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Concurrency")
    class ConcurrencyTests {

        @Test
        @DisplayName("two threads execute the same chain concurrently without interference")
        void sameChain_concurrentExecution() throws Exception {
            GuardrailChain chain = GuardrailChain.builder()
                    .addInput((input, ctx) -> CompletableFuture.completedFuture(
                            new GuardrailResult.Modified(input.toUpperCase())))
                    .addInput(inputPassed())
                    .build();

            int threads = 2;
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<String> results = Collections.synchronizedList(new ArrayList<>());

            ExecutorService executor = Executors.newFixedThreadPool(threads);
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < threads; i++) {
                String input = "thread-" + i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        results.add(chain.applyInput(input, CTX));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }

            ready.await();
            start.countDown();
            for (Future<?> f : futures) f.get();
            executor.shutdown();

            assertThat(results).hasSize(threads);
            assertThat(results).allMatch(r -> r.equals(r.toUpperCase()));
        }
    }
}