package dev.agenor.runtime.discovery;

import dev.agenor.core.*;
import dev.agenor.core.annotations.JenticBehavior;
import dev.agenor.core.annotations.JenticMessageHandler;
import dev.agenor.core.messaging.Subscription;
import dev.agenor.core.messaging.TopicSubscriber;
import dev.agenor.runtime.agent.BaseAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for AnnotationProcessor
 */
class AnnotationProcessorTest {

    @Mock
    private TopicSubscriber topicSubscriber;

    @Mock
    private Subscription subscription;

    private AnnotationProcessor processor;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        processor = new AnnotationProcessor(topicSubscriber);

        when(subscription.subscriptionId()).thenReturn("subscription-id");
        when(topicSubscriber.subscribeTopic(anyString(), any(MessageHandler.class)))
            .thenReturn(subscription);
    }

    // =========================================================================
    // BASIC ANNOTATION PROCESSING TESTS
    // =========================================================================

    @Test
    @DisplayName("Should process all annotations on agent")
    void shouldProcessAllAnnotations() {
        TestAgentWithAnnotations agent = spy(new TestAgentWithAnnotations());

        processor.processAnnotations(agent);

        // Verify behavior was added
        verify(agent, atLeastOnce()).addBehavior(any(Behavior.class));

        // Verify message handler subscription
        verify(topicSubscriber).subscribeTopic(eq("test.topic"), any(MessageHandler.class));
    }

    // =========================================================================
    // ONE_SHOT BEHAVIOR TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create OneShot behavior from annotation")
    void shouldCreateOneShotBehavior() {
        OneShotTestAgent agent = spy(new OneShotTestAgent());

        processor.processAnnotations(agent);

        ArgumentCaptor<Behavior> captor = ArgumentCaptor.forClass(Behavior.class);
        verify(agent).addBehavior(captor.capture());

        Behavior behavior = captor.getValue();
        assertThat(behavior).isNotNull();
    }

    // =========================================================================
    // CYCLIC BEHAVIOR TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create Cyclic behavior with interval")
    void shouldCreateCyclicBehaviorWithInterval() {
        CyclicTestAgent agent = spy(new CyclicTestAgent());

        processor.processAnnotations(agent);

        ArgumentCaptor<Behavior> captor = ArgumentCaptor.forClass(Behavior.class);
        verify(agent).addBehavior(captor.capture());

        assertThat(captor.getValue()).isNotNull();
    }

    @Test
    @DisplayName("Should parse milliseconds interval")
    void shouldParseMillisecondsInterval() {
        MillisecondIntervalAgent agent = spy(new MillisecondIntervalAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(Behavior.class));
    }

    @Test
    @DisplayName("Should parse minutes interval")
    void shouldParseMinutesInterval() {
        MinutesIntervalAgent agent = spy(new MinutesIntervalAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(Behavior.class));
    }

    @Test
    @DisplayName("Should parse hours interval")
    void shouldParseHoursInterval() {
        HoursIntervalAgent agent = spy(new HoursIntervalAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(Behavior.class));
    }

    @Test
    @DisplayName("Should handle invalid duration format")
    void shouldHandleInvalidDurationFormat() {
        InvalidDurationAgent agent = spy(new InvalidDurationAgent());

        // Should not throw, should use default
        assertThatCode(() -> processor.processAnnotations(agent))
            .doesNotThrowAnyException();
    }

    // =========================================================================
    // WAKER BEHAVIOR TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create Waker behavior with delay")
    void shouldCreateWakerBehaviorWithDelay() {
        WakerTestAgent agent = spy(new WakerTestAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(Behavior.class));
    }

    // =========================================================================
    // EVENT_DRIVEN BEHAVIOR TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create EventDriven behavior")
    void shouldCreateEventDrivenBehavior() {
        EventDrivenTestAgent agent = spy(new EventDrivenTestAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(Behavior.class));
    }

    // =========================================================================
    // CUSTOM BEHAVIOR TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create Custom behavior")
    void shouldCreateCustomBehavior() {
        CustomBehaviorAgent agent = spy(new CustomBehaviorAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(Behavior.class));
    }

    // =========================================================================
    // CONDITIONAL BEHAVIOR TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create Conditional behavior with system condition")
    void shouldCreateConditionalBehaviorWithSystemCondition() {
        ConditionalSystemAgent agent = spy(new ConditionalSystemAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(Behavior.class));
    }

    @Test
    @DisplayName("Should create Conditional behavior with time condition")
    void shouldCreateConditionalBehaviorWithTimeCondition() {
        ConditionalTimeAgent agent = spy(new ConditionalTimeAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(Behavior.class));
    }

    @Test
    @DisplayName("Should create Conditional behavior with agent condition")
    void shouldCreateConditionalBehaviorWithAgentCondition() {
        ConditionalAgentAgent agent = spy(new ConditionalAgentAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(Behavior.class));
    }

    @Test
    @DisplayName("Should parse AND compound conditions")
    void shouldParseAndConditions() {
        ConditionalAndAgent agent = spy(new ConditionalAndAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(Behavior.class));
    }

    @Test
    @DisplayName("Should parse OR compound conditions")
    void shouldParseOrConditions() {
        ConditionalOrAgent agent = spy(new ConditionalOrAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(Behavior.class));
    }

    @Test
    @DisplayName("Should skip conditional behavior without condition")
    void shouldSkipConditionalWithoutCondition() {
        ConditionalNoConditionAgent agent = spy(new ConditionalNoConditionAgent());

        processor.processAnnotations(agent);

        verify(agent, never()).addBehavior(any(Behavior.class));
    }

    // =========================================================================
    // THROTTLED BEHAVIOR TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create Throttled behavior")
    void shouldCreateThrottledBehavior() {
        ThrottledAgent agent = spy(new ThrottledAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(Behavior.class));
    }

    @Test
    @DisplayName("Should skip throttled behavior without rate limit")
    void shouldSkipThrottledWithoutRateLimit() {
        ThrottledNoRateLimitAgent agent = spy(new ThrottledNoRateLimitAgent());

        processor.processAnnotations(agent);

        verify(agent, never()).addBehavior(any(Behavior.class));
    }

    // =========================================================================
    // BATCH BEHAVIOR TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create Batch behavior")
    void shouldCreateBatchBehavior() {
        BatchAgent agent = spy(new BatchAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(Behavior.class));
    }

    @Test
    @DisplayName("Should use default batch size when invalid")
    void shouldUseDefaultBatchSizeWhenInvalid() {
        BatchInvalidSizeAgent agent = spy(new BatchInvalidSizeAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(Behavior.class));
    }

    // =========================================================================
    // RETRY BEHAVIOR TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create Retry behavior")
    void shouldCreateRetryBehavior() {
        RetryAgent agent = spy(new RetryAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(Behavior.class));
    }

    @Test
    @DisplayName("Should use default retry count when invalid")
    void shouldUseDefaultRetryCountWhenInvalid() {
        RetryInvalidCountAgent agent = spy(new RetryInvalidCountAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(Behavior.class));
    }

    // =========================================================================
    // SEQUENTIAL BEHAVIOR TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create Sequential behavior")
    void shouldCreateSequentialBehavior() {
        SequentialAgent agent = spy(new SequentialAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(Behavior.class));
    }

    // =========================================================================
    // PARALLEL BEHAVIOR TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create Parallel behavior")
    void shouldCreateParallelBehavior() {
        ParallelAgent agent = spy(new ParallelAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(Behavior.class));
    }

    // =========================================================================
    // FSM BEHAVIOR TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create FSM behavior")
    void shouldCreateFsmBehavior() {
        FsmAgent agent = spy(new FsmAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(Behavior.class));
    }

    // =========================================================================
    // MESSAGE HANDLER TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create message handler and subscribe")
    void shouldCreateMessageHandler() {
        MessageHandlerTestAgent agent = new MessageHandlerTestAgent();

        processor.processAnnotations(agent);

        verify(topicSubscriber).subscribeTopic(eq("test.topic"), any(MessageHandler.class));
    }

    @Test
    @DisplayName("Should skip message handler without autoSubscribe")
    void shouldSkipMessageHandlerWithoutAutoSubscribe() {
        NoAutoSubscribeAgent agent = new NoAutoSubscribeAgent();

        processor.processAnnotations(agent);

        verify(topicSubscriber, never()).subscribeTopic(anyString(), any(MessageHandler.class));
    }

    @Test
    @DisplayName("Should skip invalid message handler signature")
    void shouldSkipInvalidMessageHandler() {
        InvalidMessageHandlerAgent agent = new InvalidMessageHandlerAgent();

        processor.processAnnotations(agent);

        verify(topicSubscriber, never()).subscribeTopic(anyString(), any(MessageHandler.class));
    }

    // =========================================================================
    // BEHAVIOR VALIDATION TESTS
    // =========================================================================

    @Test
    @DisplayName("Should skip behavior with invalid method signature")
    void shouldSkipInvalidBehaviorMethod() {
        InvalidBehaviorMethodAgent agent = spy(new InvalidBehaviorMethodAgent());

        processor.processAnnotations(agent);

        // Should not add any behavior
        verify(agent, never()).addBehavior(any(Behavior.class));
    }

    @Test
    @DisplayName("Should skip behavior without autoStart")
    void shouldSkipBehaviorWithoutAutoStart() {
        NoAutoStartAgent agent = spy(new NoAutoStartAgent());

        processor.processAnnotations(agent);

        verify(agent, never()).addBehavior(any(Behavior.class));
    }

    // =========================================================================
    // ERROR HANDLING TESTS
    // =========================================================================

    @Test
    @DisplayName("Should handle exception in behavior creation gracefully")
    void shouldHandleExceptionInBehaviorCreation() {
        ExceptionThrowingAgent agent = spy(new ExceptionThrowingAgent());

        // Should not propagate exception
        assertThatCode(() -> processor.processAnnotations(agent))
            .doesNotThrowAnyException();
    }

    // =========================================================================
    // TEST HELPER CLASSES
    // =========================================================================

    static class TestAgentWithAnnotations extends BaseAgent {
        public TestAgentWithAnnotations() {
            super("test-agent", "Test Agent");
        }

        @JenticBehavior(type = BehaviorType.ONE_SHOT)
        public void oneShotMethod() {
            // Test method
        }

        @JenticMessageHandler("test.topic")
        public void handleMessage(Message msg) {
            // Test handler
        }
    }

    static class OneShotTestAgent extends BaseAgent {
        public OneShotTestAgent() {
            super("oneshot", "OneShot Agent");
        }

        @JenticBehavior(type = BehaviorType.ONE_SHOT)
        public void doOnce() {
        }
    }

    static class CyclicTestAgent extends BaseAgent {
        public CyclicTestAgent() {
            super("cyclic", "Cyclic Agent");
        }

        @JenticBehavior(type = BehaviorType.CYCLIC, interval = "5s")
        public void periodic() {
        }
    }

    static class MillisecondIntervalAgent extends BaseAgent {
        public MillisecondIntervalAgent() {
            super("ms", "MS Agent");
        }

        @JenticBehavior(type = BehaviorType.CYCLIC, interval = "500ms")
        public void periodic() {
        }
    }

    static class MinutesIntervalAgent extends BaseAgent {
        public MinutesIntervalAgent() {
            super("min", "Min Agent");
        }

        @JenticBehavior(type = BehaviorType.CYCLIC, interval = "2min")
        public void periodic() {
        }
    }

    static class HoursIntervalAgent extends BaseAgent {
        public HoursIntervalAgent() {
            super("hours", "Hours Agent");
        }

        @JenticBehavior(type = BehaviorType.CYCLIC, interval = "1h")
        public void periodic() {
        }
    }

    static class InvalidDurationAgent extends BaseAgent {
        public InvalidDurationAgent() {
            super("invalid", "Invalid Agent");
        }

        @JenticBehavior(type = BehaviorType.CYCLIC, interval = "invalid")
        public void periodic() {
        }
    }

    static class WakerTestAgent extends BaseAgent {
        public WakerTestAgent() {
            super("waker", "Waker Agent");
        }

        @JenticBehavior(type = BehaviorType.WAKER, initialDelay = "10s")
        public void wakeUp() {
        }
    }

    static class EventDrivenTestAgent extends BaseAgent {
        public EventDrivenTestAgent() {
            super("event", "Event Agent");
        }

        @JenticBehavior(type = BehaviorType.EVENT_DRIVEN)
        public void onEvent() {
        }
    }

    static class CustomBehaviorAgent extends BaseAgent {
        public CustomBehaviorAgent() {
            super("custom", "Custom Agent");
        }

        @JenticBehavior(type = BehaviorType.CUSTOM, interval = "3s")
        public void customAction() {
        }
    }

    static class ConditionalSystemAgent extends BaseAgent {
        public ConditionalSystemAgent() {
            super("cond-sys", "Conditional System");
        }

        @JenticBehavior(type = BehaviorType.CONDITIONAL, condition = "system.cpu < 80")
        public void whenLowCpu() {
        }
    }

    static class ConditionalTimeAgent extends BaseAgent {
        public ConditionalTimeAgent() {
            super("cond-time", "Conditional Time");
        }

        @JenticBehavior(type = BehaviorType.CONDITIONAL, condition = "time.businesshours")
        public void duringBusinessHours() {
        }
    }

    static class ConditionalAgentAgent extends BaseAgent {
        public ConditionalAgentAgent() {
            super("cond-agent", "Conditional Agent");
        }

        @JenticBehavior(type = BehaviorType.CONDITIONAL, condition = "agent.running")
        public void whenRunning() {
        }
    }

    static class ConditionalAndAgent extends BaseAgent {
        public ConditionalAndAgent() {
            super("cond-and", "Conditional AND");
        }

        @JenticBehavior(type = BehaviorType.CONDITIONAL, condition = "system.healthy AND time.weekday")
        public void whenHealthyAndWeekday() {
        }
    }

    static class ConditionalOrAgent extends BaseAgent {
        public ConditionalOrAgent() {
            super("cond-or", "Conditional OR");
        }

        @JenticBehavior(type = BehaviorType.CONDITIONAL, condition = "time.weekend OR time.businesshours")
        public void whenWeekendOrBusiness() {
        }
    }

    static class ConditionalNoConditionAgent extends BaseAgent {
        public ConditionalNoConditionAgent() {
            super("cond-none", "Conditional None");
        }

        @JenticBehavior(type = BehaviorType.CONDITIONAL)
        public void noCondition() {
        }
    }

    static class ThrottledAgent extends BaseAgent {
        public ThrottledAgent() {
            super("throttled", "Throttled Agent");
        }

        @JenticBehavior(type = BehaviorType.THROTTLED, rateLimit = "10/s")
        public void throttledAction() {
        }
    }

    static class ThrottledNoRateLimitAgent extends BaseAgent {
        public ThrottledNoRateLimitAgent() {
            super("throttled-none", "Throttled None");
        }

        @JenticBehavior(type = BehaviorType.THROTTLED)
        public void noRateLimit() {
        }
    }

    static class BatchAgent extends BaseAgent {
        public BatchAgent() {
            super("batch", "Batch Agent");
        }

        @JenticBehavior(type = BehaviorType.BATCH, batchSize = 5, maxWaitTime = "10s")
        public void processBatch() {
        }
    }

    static class BatchInvalidSizeAgent extends BaseAgent {
        public BatchInvalidSizeAgent() {
            super("batch-invalid", "Batch Invalid");
        }

        @JenticBehavior(type = BehaviorType.BATCH, batchSize = -1)
        public void processBatch() {
        }
    }

    static class RetryAgent extends BaseAgent {
        public RetryAgent() {
            super("retry", "Retry Agent");
        }

        @JenticBehavior(type = BehaviorType.RETRY, maxRetries = 3, backoff = "exponential", initialDelay = "1s")
        public void retryAction() {
        }
    }

    static class RetryInvalidCountAgent extends BaseAgent {
        public RetryInvalidCountAgent() {
            super("retry-invalid", "Retry Invalid");
        }

        @JenticBehavior(type = BehaviorType.RETRY, maxRetries = -1)
        public void retryAction() {
        }
    }

    static class SequentialAgent extends BaseAgent {
        public SequentialAgent() {
            super("sequential", "Sequential Agent");
        }

        @JenticBehavior(type = BehaviorType.SEQUENTIAL, interval = "200ms", stepTimeout = "5s")
        public void sequentialAction() {
        }
    }

    static class ParallelAgent extends BaseAgent {
        public ParallelAgent() {
            super("parallel", "Parallel Agent");
        }

        @JenticBehavior(type = BehaviorType.PARALLEL, parallelStrategy = "all", requiredCompletions = 2)
        public void parallelAction() {
        }
    }

    static class FsmAgent extends BaseAgent {
        public FsmAgent() {
            super("fsm", "FSM Agent");
        }

        @JenticBehavior(type = BehaviorType.FSM, fsmInitialState = "IDLE", stateTimeout = "10s")
        public void fsmAction() {
        }
    }

    static class MessageHandlerTestAgent extends BaseAgent {
        public MessageHandlerTestAgent() {
            super("handler", "Handler Agent");
        }

        @JenticMessageHandler("test.topic")
        public void handleMessage(Message msg) {
        }
    }

    static class NoAutoSubscribeAgent extends BaseAgent {
        public NoAutoSubscribeAgent() {
            super("no-auto", "No Auto Agent");
        }

        @JenticMessageHandler(value = "test.topic", autoSubscribe = false)
        public void handleMessage(Message msg) {
        }
    }

    static class InvalidMessageHandlerAgent extends BaseAgent {
        public InvalidMessageHandlerAgent() {
            super("invalid-handler", "Invalid Handler");
        }

        @JenticMessageHandler("test.topic")
        public void wrongSignature() {
            // Wrong signature - no Message parameter
        }
    }

    static class InvalidBehaviorMethodAgent extends BaseAgent {
        public InvalidBehaviorMethodAgent() {
            super("invalid-behavior", "Invalid Behavior");
        }

        @JenticBehavior(type = BehaviorType.ONE_SHOT)
        public void wrongSignature(String param) {
            // Wrong signature - has parameters
        }
    }

    static class NoAutoStartAgent extends BaseAgent {
        public NoAutoStartAgent() {
            super("no-auto-start", "No Auto Start");
        }

        @JenticBehavior(type = BehaviorType.ONE_SHOT, autoStart = false)
        public void notAutoStarted() {
        }
    }

    static class ExceptionThrowingAgent extends BaseAgent {
        public ExceptionThrowingAgent() {
            super("exception", "Exception Agent");
        }

        @JenticBehavior(type = BehaviorType.ONE_SHOT)
        private void privateMethod() {
            // Private method will cause issues
        }
    }
}
