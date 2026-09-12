package dev.agenor.runtime.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.agenor.core.annotations.Agent;
import dev.agenor.runtime.AgenorRuntime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for F-20: {@code @Agent("id")} did not give the agent that identity.
 *
 * <p>The no-arg {@code BaseAgent()} constructor generated a UUID and never read the annotation,
 * so the README's first agent — annotated {@code @Agent("hello-agent")} and registered manually —
 * published as {@code Hello from 02adb7cb-8a4a-…} and was reachable only at that UUID. Every
 * example in the repository repeated its id in a {@code super(...)} call, so nothing failed.
 *
 * <p>Each test here fails on the pre-0.34.0 constructor, which is the only reason to trust them.
 */
@DisplayName("BaseAgent identity from @Agent")
class BaseAgentAnnotatedIdentityTest {

    @Nested
    @DisplayName("the annotation supplies the id")
    class AnnotationSupplied {

        @Test
        @DisplayName("a no-arg annotated agent is identified by the annotation, not a UUID")
        void annotatedAgentUsesAnnotationValue() {
            var agent = new AnnotatedAgent();

            assertThat(agent.getAgentId()).isEqualTo("hello-agent");
            assertThat(agent.getAgentName()).isEqualTo("hello-agent");
        }

        @Test
        @DisplayName("the descriptor built in the constructor carries the same id")
        void descriptorAgreesWithTheId() {
            var agent = new AnnotatedAgent();

            // The descriptor is built during construction, so an id resolved too late would
            // leave these two disagreeing — which is how the directory could list one identity
            // while the agent answered to another. (The field is protected; this test shares
            // BaseAgent's package.)
            assertThat(agent.agentDescriptor.agentId()).isEqualTo("hello-agent");
        }

        @Test
        @DisplayName("a surrounding value is trimmed")
        void annotationValueIsTrimmed() {
            assertThat(new PaddedAgent().getAgentId()).isEqualTo("padded-agent");
        }

        @Test
        @DisplayName("the runtime registers it at the annotated id")
        void runtimeResolvesTheAnnotatedId() {
            var runtime = AgenorRuntime.builder().build();

            runtime.registerAgent(new AnnotatedAgent());

            // The symptom F-20 was found by: this is the address a sender uses.
            assertThat(runtime.getAgent("hello-agent")).isPresent();
        }
    }

    @Nested
    @DisplayName("an explicit id still wins")
    class ExplicitWins {

        @Test
        @DisplayName("super(id) overrides the annotation")
        void explicitIdBeatsTheAnnotation() {
            var agent = new ExplicitlyIdentifiedAgent("instance-7");

            assertThat(agent.getAgentId()).isEqualTo("instance-7");
        }

        @Test
        @DisplayName("two instances of the same annotated class can still differ")
        void multiInstanceAgentsKeepDistinctIds() {
            // The reason the annotation must not win over super(...): AgentDescriptors
            // documents instance identity as authoritative for exactly this case.
            assertThat(new ExplicitlyIdentifiedAgent("a").getAgentId())
                    .isNotEqualTo(new ExplicitlyIdentifiedAgent("b").getAgentId());
        }
    }

    @Nested
    @DisplayName("without a declared id, nothing changes")
    class GeneratedFallback {

        @Test
        @DisplayName("an unannotated agent still gets a UUID")
        void unannotatedAgentGetsUuid() {
            assertIsUuid(new PlainAgent().getAgentId());
        }

        @Test
        @DisplayName("an empty annotation value gets a UUID, not the class name")
        void blankAnnotationValueGetsUuid() {
            // The annotation's Javadoc promised a kebab-case class-name default until 0.34.0.
            // Nothing implemented it, and it would give every instance one address.
            assertIsUuid(new UnnamedAgent().getAgentId());
        }

        @Test
        @DisplayName("@Agent is not @Inherited, so a subclass gets a UUID")
        void subclassOfAnnotatedAgentGetsUuid() {
            assertIsUuid(new SubclassOfAnnotatedAgent().getAgentId());
        }

        private void assertIsUuid(String agentId) {
            assertThatCode(() -> UUID.fromString(agentId)).doesNotThrowAnyException();
        }
    }

    @Agent("hello-agent")
    static class AnnotatedAgent extends BaseAgent {
    }

    @Agent("  padded-agent  ")
    static class PaddedAgent extends BaseAgent {
    }

    @Agent("declared-but-overridden")
    static class ExplicitlyIdentifiedAgent extends BaseAgent {
        ExplicitlyIdentifiedAgent(String agentId) {
            super(agentId);
        }
    }

    @Agent
    static class UnnamedAgent extends BaseAgent {
    }

    static class PlainAgent extends BaseAgent {
    }

    static class SubclassOfAnnotatedAgent extends AnnotatedAgent {
    }
}
