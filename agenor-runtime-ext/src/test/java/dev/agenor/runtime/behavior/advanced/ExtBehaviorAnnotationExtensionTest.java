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
 * Round-trip tests for {@link ExtBehaviorAnnotationExtension}, exercised through the
 * real {@link AgentAnnotationProcessor} — covers the {@code @Behavior} types whose
 * implementation classes live in {@code agenor-runtime-ext} (CONDITIONAL, THROTTLED,
 * BATCH, RETRY, SEQUENTIAL, PARALLEL, FSM). Core types and
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
    @DisplayName("Should report support for exactly the seven ext behavior types")
    void shouldSupportExtOnlyTypes() {
        ExtBehaviorAnnotationExtension extension = new ExtBehaviorAnnotationExtension();

        assertThat(extension.supports(BehaviorType.CONDITIONAL)).isTrue();
        assertThat(extension.supports(BehaviorType.THROTTLED)).isTrue();
        assertThat(extension.supports(BehaviorType.BATCH)).isTrue();
        assertThat(extension.supports(BehaviorType.RETRY)).isTrue();
        assertThat(extension.supports(BehaviorType.SEQUENTIAL)).isTrue();
        assertThat(extension.supports(BehaviorType.PARALLEL)).isTrue();
        assertThat(extension.supports(BehaviorType.FSM)).isTrue();

        assertThat(extension.supports(BehaviorType.ONE_SHOT)).isFalse();
        assertThat(extension.supports(BehaviorType.CYCLIC)).isFalse();
    }

    // =========================================================================
    // CONDITIONAL BEHAVIOR TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create Conditional behavior with system condition")
    void shouldCreateConditionalBehaviorWithSystemCondition() {
        ConditionalSystemAgent agent = spy(new ConditionalSystemAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(dev.agenor.core.Behavior.class));
    }

    @Test
    @DisplayName("Should create Conditional behavior with time condition")
    void shouldCreateConditionalBehaviorWithTimeCondition() {
        ConditionalTimeAgent agent = spy(new ConditionalTimeAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(dev.agenor.core.Behavior.class));
    }

    @Test
    @DisplayName("Should create Conditional behavior with agent condition")
    void shouldCreateConditionalBehaviorWithAgentCondition() {
        ConditionalAgentAgent agent = spy(new ConditionalAgentAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(dev.agenor.core.Behavior.class));
    }

    @Test
    @DisplayName("Should parse AND compound conditions")
    void shouldParseAndConditions() {
        ConditionalAndAgent agent = spy(new ConditionalAndAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(dev.agenor.core.Behavior.class));
    }

    @Test
    @DisplayName("Should parse OR compound conditions")
    void shouldParseOrConditions() {
        ConditionalOrAgent agent = spy(new ConditionalOrAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(dev.agenor.core.Behavior.class));
    }

    @Test
    @DisplayName("Should skip conditional behavior without condition")
    void shouldSkipConditionalWithoutCondition() {
        ConditionalNoConditionAgent agent = spy(new ConditionalNoConditionAgent());

        processor.processAnnotations(agent);

        verify(agent, never()).addBehavior(any(dev.agenor.core.Behavior.class));
    }

    // =========================================================================
    // THROTTLED BEHAVIOR TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create Throttled behavior")
    void shouldCreateThrottledBehavior() {
        ThrottledAgent agent = spy(new ThrottledAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(dev.agenor.core.Behavior.class));
    }

    @Test
    @DisplayName("Should skip throttled behavior without rate limit")
    void shouldSkipThrottledWithoutRateLimit() {
        ThrottledNoRateLimitAgent agent = spy(new ThrottledNoRateLimitAgent());

        processor.processAnnotations(agent);

        verify(agent, never()).addBehavior(any(dev.agenor.core.Behavior.class));
    }

    // =========================================================================
    // BATCH BEHAVIOR TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create Batch behavior")
    void shouldCreateBatchBehavior() {
        BatchAgent agent = spy(new BatchAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(dev.agenor.core.Behavior.class));
    }

    @Test
    @DisplayName("Should use default batch size when invalid")
    void shouldUseDefaultBatchSizeWhenInvalid() {
        BatchInvalidSizeAgent agent = spy(new BatchInvalidSizeAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(dev.agenor.core.Behavior.class));
    }

    // =========================================================================
    // RETRY BEHAVIOR TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create Retry behavior")
    void shouldCreateRetryBehavior() {
        RetryAgent agent = spy(new RetryAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(dev.agenor.core.Behavior.class));
    }

    @Test
    @DisplayName("Should use default retry count when invalid")
    void shouldUseDefaultRetryCountWhenInvalid() {
        RetryInvalidCountAgent agent = spy(new RetryInvalidCountAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(dev.agenor.core.Behavior.class));
    }

    // =========================================================================
    // SEQUENTIAL BEHAVIOR TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create Sequential behavior")
    void shouldCreateSequentialBehavior() {
        SequentialAgent agent = spy(new SequentialAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(dev.agenor.core.Behavior.class));
    }

    // =========================================================================
    // PARALLEL BEHAVIOR TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create Parallel behavior")
    void shouldCreateParallelBehavior() {
        ParallelAgent agent = spy(new ParallelAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(dev.agenor.core.Behavior.class));
    }

    // =========================================================================
    // FSM BEHAVIOR TESTS
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

    static class ConditionalSystemAgent extends BaseAgent {
        public ConditionalSystemAgent() {
            super("cond-sys", "Conditional System");
        }

        @dev.agenor.core.annotations.Behavior(type = BehaviorType.CONDITIONAL, condition = "system.cpu < 80")
        public void whenLowCpu() {
        }
    }

    static class ConditionalTimeAgent extends BaseAgent {
        public ConditionalTimeAgent() {
            super("cond-time", "Conditional Time");
        }

        @dev.agenor.core.annotations.Behavior(type = BehaviorType.CONDITIONAL, condition = "time.businesshours")
        public void duringBusinessHours() {
        }
    }

    static class ConditionalAgentAgent extends BaseAgent {
        public ConditionalAgentAgent() {
            super("cond-agent", "Conditional Agent");
        }

        @Behavior(type = BehaviorType.CONDITIONAL, condition = "agent.running")
        public void whenRunning() {
        }
    }

    static class ConditionalAndAgent extends BaseAgent {
        public ConditionalAndAgent() {
            super("cond-and", "Conditional AND");
        }

        @dev.agenor.core.annotations.Behavior(type = BehaviorType.CONDITIONAL, condition = "system.healthy AND time.weekday")
        public void whenHealthyAndWeekday() {
        }
    }

    static class ConditionalOrAgent extends BaseAgent {
        public ConditionalOrAgent() {
            super("cond-or", "Conditional OR");
        }

        @dev.agenor.core.annotations.Behavior(type = BehaviorType.CONDITIONAL, condition = "time.weekend OR time.businesshours")
        public void whenWeekendOrBusiness() {
        }
    }

    static class ConditionalNoConditionAgent extends BaseAgent {
        public ConditionalNoConditionAgent() {
            super("cond-none", "Conditional None");
        }

        @dev.agenor.core.annotations.Behavior(type = BehaviorType.CONDITIONAL)
        public void noCondition() {
        }
    }

    static class ThrottledAgent extends BaseAgent {
        public ThrottledAgent() {
            super("throttled", "Throttled Agent");
        }

        @dev.agenor.core.annotations.Behavior(type = BehaviorType.THROTTLED, rateLimit = "10/s")
        public void throttledAction() {
        }
    }

    static class ThrottledNoRateLimitAgent extends BaseAgent {
        public ThrottledNoRateLimitAgent() {
            super("throttled-none", "Throttled None");
        }

        @Behavior(type = BehaviorType.THROTTLED)
        public void noRateLimit() {
        }
    }

    static class BatchAgent extends BaseAgent {
        public BatchAgent() {
            super("batch", "Batch Agent");
        }

        @dev.agenor.core.annotations.Behavior(type = BehaviorType.BATCH, batchSize = 5, maxWaitTime = "10s")
        public void processBatch() {
        }
    }

    static class BatchInvalidSizeAgent extends BaseAgent {
        public BatchInvalidSizeAgent() {
            super("batch-invalid", "Batch Invalid");
        }

        @dev.agenor.core.annotations.Behavior(type = BehaviorType.BATCH, batchSize = -1)
        public void processBatch() {
        }
    }

    static class RetryAgent extends BaseAgent {
        public RetryAgent() {
            super("retry", "Retry Agent");
        }

        @dev.agenor.core.annotations.Behavior(type = BehaviorType.RETRY, maxRetries = 3, backoff = "exponential", initialDelay = "1s")
        public void retryAction() {
        }
    }

    static class RetryInvalidCountAgent extends BaseAgent {
        public RetryInvalidCountAgent() {
            super("retry-invalid", "Retry Invalid");
        }

        @Behavior(type = BehaviorType.RETRY, maxRetries = -1)
        public void retryAction() {
        }
    }

    static class SequentialAgent extends BaseAgent {
        public SequentialAgent() {
            super("sequential", "Sequential Agent");
        }

        @dev.agenor.core.annotations.Behavior(type = BehaviorType.SEQUENTIAL, interval = "200ms", stepTimeout = "5s")
        public void sequentialAction() {
        }
    }

    static class ParallelAgent extends BaseAgent {
        public ParallelAgent() {
            super("parallel", "Parallel Agent");
        }

        @dev.agenor.core.annotations.Behavior(type = BehaviorType.PARALLEL, parallelStrategy = "all", requiredCompletions = 2)
        public void parallelAction() {
        }
    }

    static class FsmAgent extends BaseAgent {
        public FsmAgent() {
            super("fsm", "FSM Agent");
        }

        @Behavior(type = BehaviorType.FSM, fsmInitialState = "IDLE", stateTimeout = "10s")
        public void fsmAction() {
        }
    }
}
