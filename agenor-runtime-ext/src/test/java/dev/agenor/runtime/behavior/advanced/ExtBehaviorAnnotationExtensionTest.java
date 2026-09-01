package dev.agenor.runtime.behavior.advanced;

import java.util.Optional;

import dev.agenor.core.*;
import dev.agenor.core.annotations.Behavior;
import dev.agenor.core.messaging.Subscription;
import dev.agenor.core.messaging.TopicSubscriber;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.annotation.AgentAnnotationProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Round-trip tests for {@link ExtBehaviorAnnotationExtension}, exercised through the real
 * {@link AgentAnnotationProcessor}. The extension covered seven {@code @Behavior} types until
 * 0.30.0 removed six of the constants that reached them; FSM is what is left. Core types and
 * {@code @AgenorMessageHandler} are covered by {@code AgentAnnotationProcessorTest} in
 * {@code agenor-runtime}.
 */
class ExtBehaviorAnnotationExtensionTest {

    @Mock
    private TopicSubscriber topicSubscriber;

    @Mock
    private Subscription subscription;

    private AgentAnnotationProcessor processor;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        processor = new AgentAnnotationProcessor(topicSubscriber, Optional.of(new ExtBehaviorAnnotationExtension()));

        when(subscription.subscriptionId()).thenReturn("subscription-id");
        when(topicSubscriber.subscribeTopic(anyString(), any(MessageHandler.class)))
            .thenReturn(subscription);
    }

    // =========================================================================
    // supports()
    // =========================================================================

    @Test
    @DisplayName("Should report support for exactly the ext behavior types")
    void shouldSupportExtOnlyTypes() {
        ExtBehaviorAnnotationExtension extension = new ExtBehaviorAnnotationExtension();

        assertThat(extension.supports(BehaviorType.FSM)).isTrue();

        assertThat(extension.supports(BehaviorType.ONE_SHOT)).isFalse();
        assertThat(extension.supports(BehaviorType.CYCLIC)).isFalse();
    }

    // =========================================================================
    // FSM
    // =========================================================================

    @Test
    @DisplayName("Should create FSM behavior")
    void shouldCreateFsmBehavior() {
        FsmAgent agent = spy(new FsmAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(dev.agenor.core.Behavior.class));
    }

    // =========================================================================
    // TEST HELPER CLASSES
    // =========================================================================

    static class FsmAgent extends BaseAgent {
        public FsmAgent() {
            super("fsm", "FSM Agent");
        }

        @Behavior(type = BehaviorType.FSM, fsmInitialState = "IDLE", stateTimeout = "10s")
        public void fsmAction() {
        }
    }
}
