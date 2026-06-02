package dev.agenor.runtime.guardrail;

import dev.agenor.core.guardrail.GuardrailContext;
import dev.agenor.core.guardrail.GuardrailResult;
import dev.agenor.core.guardrail.InputGuardrail;
import dev.agenor.core.guardrail.OutputGuardrail;
import dev.agenor.core.guardrail.WithGuardrails;
import dev.agenor.runtime.agent.LLMAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;

@DisplayName("GuardrailAnnotationProcessor — @WithGuardrails wiring")
class GuardrailAnnotationProcessorTest {

    // -------------------------------------------------------------------------
    // Test guardrail implementations (require no-arg constructors)
    // -------------------------------------------------------------------------

    public static class AlwaysPassedInput implements InputGuardrail {
        @Override
        public CompletableFuture<GuardrailResult> apply(String input, GuardrailContext ctx) {
            return CompletableFuture.completedFuture(new GuardrailResult.Passed());
        }
    }

    public static class AlwaysPassedOutput implements OutputGuardrail {
        @Override
        public CompletableFuture<GuardrailResult> apply(String output, GuardrailContext ctx) {
            return CompletableFuture.completedFuture(new GuardrailResult.Passed());
        }
    }

    public static class AlwaysBlockedInput implements InputGuardrail {
        @Override
        public CompletableFuture<GuardrailResult> apply(String input, GuardrailContext ctx) {
            return CompletableFuture.completedFuture(new GuardrailResult.Blocked("blocked by annotation"));
        }
    }

    /** Guardrail with NO no-arg constructor — must trigger GuardrailWiringException. */
    public static class NoNoArgConstructorGuardrail implements InputGuardrail {
        @SuppressWarnings("unused")
        public NoNoArgConstructorGuardrail(String required) {}

        @Override
        public CompletableFuture<GuardrailResult> apply(String input, GuardrailContext ctx) {
            return CompletableFuture.completedFuture(new GuardrailResult.Passed());
        }
    }

    // -------------------------------------------------------------------------
    // Test agent subclasses
    // -------------------------------------------------------------------------

    /** Agent with no annotation. */
    private static class UnannotatedAgent extends LLMAgent {
        UnannotatedAgent() { super("unannotated-agent"); }
    }

    /** Agent with only input guardrails. */
    @WithGuardrails(input = { AlwaysPassedInput.class })
    private static class InputOnlyAgent extends LLMAgent {
        InputOnlyAgent() { super("input-only-agent"); }
    }

    /** Agent with only output guardrails. */
    @WithGuardrails(output = { AlwaysPassedOutput.class })
    private static class OutputOnlyAgent extends LLMAgent {
        OutputOnlyAgent() { super("output-only-agent"); }
    }

    /** Agent with both input and output guardrails. */
    @WithGuardrails(
            input  = { AlwaysPassedInput.class },
            output = { AlwaysPassedOutput.class }
    )
    private static class BothGuardrailsAgent extends LLMAgent {
        BothGuardrailsAgent() { super("both-agent"); }
    }

    /** Agent annotated with a guardrail that has no no-arg constructor. */
    @WithGuardrails(input = { NoNoArgConstructorGuardrail.class })
    private static class BrokenAnnotationAgent extends LLMAgent {
        BrokenAnnotationAgent() { super("broken-agent"); }
    }

    /** Agent with a blocking input guardrail via annotation. */
    @WithGuardrails(input = { AlwaysBlockedInput.class })
    private static class BlockingAnnotationAgent extends LLMAgent {
        BlockingAnnotationAgent() { super("blocking-agent"); }
    }

    // -------------------------------------------------------------------------
    // Tests: no annotation
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("No @WithGuardrails annotation")
    class NoAnnotation {

        @Test
        @DisplayName("agent without annotation receives no chain (null)")
        void noAnnotation_chainRemainsNull() {
            UnannotatedAgent agent = new UnannotatedAgent();
            GuardrailAnnotationProcessor.process(agent);
            assertThat(agent.getGuardrailChain()).isNull();
        }
    }

    // -------------------------------------------------------------------------
    // Tests: annotation present
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("@WithGuardrails annotation present")
    class WithAnnotation {

        @Test
        @DisplayName("input-only annotation → chain has input guardrail, no output")
        void inputOnly_chainHasInputOnly() {
            InputOnlyAgent agent = new InputOnlyAgent();
            GuardrailAnnotationProcessor.process(agent);

            GuardrailChain chain = agent.getGuardrailChain();
            assertThat(chain).isNotNull();
            assertThat(chain.hasNoInputGuardrails()).isFalse();
            assertThat(chain.hasNoOutputGuardrails()).isTrue();
        }

        @Test
        @DisplayName("output-only annotation → chain has output guardrail, no input")
        void outputOnly_chainHasOutputOnly() {
            OutputOnlyAgent agent = new OutputOnlyAgent();
            GuardrailAnnotationProcessor.process(agent);

            GuardrailChain chain = agent.getGuardrailChain();
            assertThat(chain).isNotNull();
            assertThat(chain.hasNoInputGuardrails()).isTrue();
            assertThat(chain.hasNoOutputGuardrails()).isFalse();
        }

        @Test
        @DisplayName("both input+output annotation → chain has both")
        void both_chainHasBoth() {
            BothGuardrailsAgent agent = new BothGuardrailsAgent();
            GuardrailAnnotationProcessor.process(agent);

            GuardrailChain chain = agent.getGuardrailChain();
            assertThat(chain).isNotNull();
            assertThat(chain.hasNoInputGuardrails()).isFalse();
            assertThat(chain.hasNoOutputGuardrails()).isFalse();
        }

        @Test
        @DisplayName("injected chain has correct guardrail instance type")
        void injectedChain_correctTypes() {
            InputOnlyAgent agent = new InputOnlyAgent();
            GuardrailAnnotationProcessor.process(agent);

            assertThat(agent.getGuardrailChain().inputGuardrails())
                    .hasSize(1)
                    .first()
                    .isInstanceOf(AlwaysPassedInput.class);
        }
    }

    // -------------------------------------------------------------------------
    // Tests: merge with existing programmatic chain
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Merge annotation + programmatic chain")
    class MergeTests {

        @Test
        @DisplayName("annotation chain prepended to programmatic chain (inputs)")
        void merge_annotationPrependedToExisting_input() {
            InputOnlyAgent agent = new InputOnlyAgent();

            // Pre-configure programmatic chain with an output guardrail
            GuardrailChain programmatic = GuardrailChain.builder()
                    .addOutput(new AlwaysPassedOutput())
                    .build();
            agent.setGuardrailChain(programmatic);

            // Process annotation — should merge
            GuardrailAnnotationProcessor.process(agent);

            GuardrailChain merged = agent.getGuardrailChain();
            assertThat(merged.hasNoInputGuardrails()).isFalse();  // from annotation
            assertThat(merged.hasNoOutputGuardrails()).isFalse(); // from programmatic
        }

        @Test
        @DisplayName("merge preserves order: annotation inputs first, programmatic inputs second")
        void merge_annotationInputsFirst() {
            InputOnlyAgent agent = new InputOnlyAgent();

            // Programmatic: also add an input guardrail
            InputGuardrail programmaticInput = (input, ctx) ->
                    CompletableFuture.completedFuture(new GuardrailResult.Passed());
            agent.setGuardrailChain(GuardrailChain.builder()
                    .addInput(programmaticInput)
                    .build());

            GuardrailAnnotationProcessor.process(agent);

            GuardrailChain merged = agent.getGuardrailChain();
            // annotation guardrail comes first
            assertThat(merged.inputGuardrails()).hasSize(2);
            assertThat(merged.inputGuardrails().get(0)).isInstanceOf(AlwaysPassedInput.class);
            assertThat(merged.inputGuardrails().get(1)).isSameAs(programmaticInput);
        }

        @Test
        @DisplayName("merge helper produces correct combined guardrail lists")
        void mergeHelper_combinedLists() {
            GuardrailChain annotationChain = GuardrailChain.builder()
                    .addInput(new AlwaysPassedInput())
                    .build();
            GuardrailChain programmaticChain = GuardrailChain.builder()
                    .addOutput(new AlwaysPassedOutput())
                    .build();

            GuardrailChain merged = GuardrailAnnotationProcessor.merge(
                    annotationChain, programmaticChain);

            assertThat(merged.inputGuardrails()).hasSize(1);
            assertThat(merged.outputGuardrails()).hasSize(1);
        }
    }

    // -------------------------------------------------------------------------
    // Tests: error cases
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Error cases")
    class ErrorCases {

        @Test
        @DisplayName("non-existent no-arg constructor → GuardrailWiringException at bootstrap")
        void noNoArgConstructor_throwsAtBootstrap() {
            BrokenAnnotationAgent agent = new BrokenAnnotationAgent();

            assertThatThrownBy(() -> GuardrailAnnotationProcessor.process(agent))
                    .isInstanceOf(GuardrailAnnotationProcessor.GuardrailWiringException.class)
                    .hasMessageContaining("NoNoArgConstructorGuardrail")
                    .hasMessageContaining("no-arg constructor");
        }

        @Test
        @DisplayName("wiring exception message guides developer toward GuardrailChain.builder()")
        void errorMessage_mentionsBuilderAlternative() {
            BrokenAnnotationAgent agent = new BrokenAnnotationAgent();

            assertThatThrownBy(() -> GuardrailAnnotationProcessor.process(agent))
                    .hasMessageContaining("GuardrailChain.builder()");
        }
    }

    // -------------------------------------------------------------------------
    // Integration: annotated agent has a working chain after wiring
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("annotated blocking agent: chain is injected and contains the blocking guardrail")
    void annotatedBlockingAgent_chainInjectedAndFunctional() throws Exception {
        BlockingAnnotationAgent agent = new BlockingAnnotationAgent();
        GuardrailAnnotationProcessor.process(agent);

        GuardrailChain chain = agent.getGuardrailChain();
        assertThat(chain).isNotNull();
        assertThat(chain.hasNoInputGuardrails()).isFalse();

        // Verify the chain itself blocks — tested directly on GuardrailChain
        // (avoids calling the protected LLMAgent hook from a different package)
        assertThatThrownBy(() ->
                chain.applyInput("any input", GuardrailContext.of("blocking-agent")))
                .hasMessageContaining("blocked by annotation");
    }
}
