package dev.agenor.runtime.annotation;

import java.util.Optional;

import dev.agenor.core.*;
import dev.agenor.core.annotations.Behavior;
import dev.agenor.core.annotations.AgenorMessageHandler;
import dev.agenor.core.messaging.Subscription;
import dev.agenor.core.messaging.TopicSubscriber;
import dev.agenor.core.spi.BehaviorAnnotationExtension;
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
 * Tests for {@link AgentAnnotationProcessor} covering {@code @AgenorMessageHandler} and
 * the {@code @Behavior} types handled directly by {@code agenor-runtime} (ONE_SHOT,
 * CYCLIC, WAKER, EVENT_DRIVEN, CUSTOM) — constructed with no
 * {@link BehaviorAnnotationExtension}, proving these work with zero optional modules
 * present. Ext-only behavior types (CONDITIONAL, THROTTLED, BATCH, RETRY, SEQUENTIAL,
 * PARALLEL, FSM) are covered separately by {@code ExtBehaviorAnnotationExtensionTest}
 * in {@code agenor-runtime-ext}.
 */
class AgentAnnotationProcessorTest {

    @Mock
    private TopicSubscriber topicSubscriber;

    @Mock
    private Subscription subscription;

    private AgentAnnotationProcessor processor;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        processor = new AgentAnnotationProcessor(topicSubscriber, Optional.empty());

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
        verify(agent, atLeastOnce()).addBehavior(any(dev.agenor.core.Behavior.class));

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

        ArgumentCaptor<dev.agenor.core.Behavior> captor = ArgumentCaptor.forClass(dev.agenor.core.Behavior.class);
        verify(agent).addBehavior(captor.capture());

        dev.agenor.core.Behavior behavior = captor.getValue();
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

        ArgumentCaptor<dev.agenor.core.Behavior> captor = ArgumentCaptor.forClass(dev.agenor.core.Behavior.class);
        verify(agent).addBehavior(captor.capture());

        assertThat(captor.getValue()).isNotNull();
    }

    @Test
    @DisplayName("Should parse milliseconds interval")
    void shouldParseMillisecondsInterval() {
        MillisecondIntervalAgent agent = spy(new MillisecondIntervalAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(dev.agenor.core.Behavior.class));
    }

    @Test
    @DisplayName("Should parse minutes interval")
    void shouldParseMinutesInterval() {
        MinutesIntervalAgent agent = spy(new MinutesIntervalAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(dev.agenor.core.Behavior.class));
    }

    @Test
    @DisplayName("Should parse hours interval")
    void shouldParseHoursInterval() {
        HoursIntervalAgent agent = spy(new HoursIntervalAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(dev.agenor.core.Behavior.class));
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

        verify(agent).addBehavior(any(dev.agenor.core.Behavior.class));
    }

    // =========================================================================
    // EVENT_DRIVEN BEHAVIOR TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create EventDriven behavior")
    void shouldCreateEventDrivenBehavior() {
        EventDrivenTestAgent agent = spy(new EventDrivenTestAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(dev.agenor.core.Behavior.class));
    }

    // =========================================================================
    // CUSTOM BEHAVIOR TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create Custom behavior")
    void shouldCreateCustomBehavior() {
        CustomBehaviorAgent agent = spy(new CustomBehaviorAgent());

        processor.processAnnotations(agent);

        verify(agent).addBehavior(any(dev.agenor.core.Behavior.class));
    }

    // =========================================================================
    // EXT-ONLY BEHAVIOR TYPE WITHOUT EXTENSION TESTS
    // =========================================================================

    @Test
    @DisplayName("Should fail loudly for an ext-only behavior type when no extension is present")
    void shouldThrowIllegalStateForExtOnlyTypeWithoutExtension() {
        FsmWithoutExtensionAgent agent = spy(new FsmWithoutExtensionAgent());

        assertThatThrownBy(() -> processor.processAnnotations(agent))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("agenor-runtime-ext");
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
        verify(agent, never()).addBehavior(any(dev.agenor.core.Behavior.class));
    }

    @Test
    @DisplayName("Should skip behavior without autoStart")
    void shouldSkipBehaviorWithoutAutoStart() {
        NoAutoStartAgent agent = spy(new NoAutoStartAgent());

        processor.processAnnotations(agent);

        verify(agent, never()).addBehavior(any(dev.agenor.core.Behavior.class));
    }

    // =========================================================================
    // INHERITED ANNOTATIONS
    // =========================================================================

    @Test
    @DisplayName("Should register an @AgenorMessageHandler declared on a base class")
    void shouldRegisterInheritedMessageHandler() {
        // Given an agent whose only handler is declared on its abstract base
        InheritingAgent agent = spy(new InheritingAgent());

        // When
        processor.processAnnotations(agent);

        // Then it is subscribed — scanning only the declared methods found nothing here,
        // and an agent that receives nothing is a silent failure
        verify(topicSubscriber).subscribeTopic(eq("inherited.topic"), any(MessageHandler.class));
    }

    @Test
    @DisplayName("Should register a @Behavior declared on a base class")
    void shouldRegisterInheritedBehavior() {
        // Given
        InheritingAgent agent = spy(new InheritingAgent());

        // When
        processor.processAnnotations(agent);

        // Then
        verify(agent).addBehavior(any(dev.agenor.core.Behavior.class));
    }

    @Test
    @DisplayName("Should register an overridden handler once, at its most-derived declaration")
    void shouldRegisterOverriddenHandlerOnce() {
        // Given an agent that overrides its base class's annotated handler and re-annotates it
        OverridingAgent agent = spy(new OverridingAgent());

        // When
        processor.processAnnotations(agent);

        // Then one subscription, not two: the override and the method it overrides are the
        // same handler, and registering both would deliver every message twice
        verify(topicSubscriber, times(1))
            .subscribeTopic(eq("inherited.topic"), any(MessageHandler.class));

        // And the subclass's version is the one invoked, since dispatch stays virtual
        ArgumentCaptor<MessageHandler> captor = ArgumentCaptor.forClass(MessageHandler.class);
        verify(topicSubscriber).subscribeTopic(eq("inherited.topic"), captor.capture());
        captor.getValue().handle(Message.builder().topic("inherited.topic").content("x").build()).join();
        assertThat(agent.handledBy).isEqualTo("subclass");
    }

    @Test
    @DisplayName("Should register an @AgenorMessageHandler declared as an interface default method")
    void shouldRegisterHandlerFromInterfaceDefaultMethod() {
        // Given an agent whose handler comes from an interface it implements
        InterfaceImplementingAgent agent = spy(new InterfaceImplementingAgent());

        // When
        processor.processAnnotations(agent);

        // Then
        verify(topicSubscriber).subscribeTopic(eq("interface.topic"), any(MessageHandler.class));
    }

    @Test
    @DisplayName("Should register a @Behavior declared as an interface default method")
    void shouldRegisterBehaviorFromInterfaceDefaultMethod() {
        // Given
        InterfaceImplementingAgent agent = spy(new InterfaceImplementingAgent());

        // When
        processor.processAnnotations(agent);

        // Then
        verify(agent).addBehavior(any(dev.agenor.core.Behavior.class));
    }

    @Test
    @DisplayName("A class declaration wins over the interface default method it overrides")
    void classDeclarationWinsOverInterfaceDefault() {
        // Given an agent that overrides the interface's default handler and re-annotates it
        InterfaceOverridingAgent agent = spy(new InterfaceOverridingAgent());

        // When
        processor.processAnnotations(agent);

        // Then one subscription, and it is the class's implementation that runs — the same
        // most-derived-declaration rule that governs a subclass override
        ArgumentCaptor<MessageHandler> captor = ArgumentCaptor.forClass(MessageHandler.class);
        verify(topicSubscriber, times(1)).subscribeTopic(eq("interface.topic"), captor.capture());
        captor.getValue().handle(Message.builder().topic("interface.topic").content("x").build()).join();
        assertThat(agent.handledBy).isEqualTo("class");
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

        @dev.agenor.core.annotations.Behavior(type = BehaviorType.ONE_SHOT)
        public void oneShotMethod() {
            // Test method
        }

        @AgenorMessageHandler("test.topic")
        public void handleMessage(Message msg) {
            // Test handler
        }
    }

    static class OneShotTestAgent extends BaseAgent {
        public OneShotTestAgent() {
            super("oneshot", "OneShot Agent");
        }

        @dev.agenor.core.annotations.Behavior(type = BehaviorType.ONE_SHOT)
        public void doOnce() {
        }
    }

    static class CyclicTestAgent extends BaseAgent {
        public CyclicTestAgent() {
            super("cyclic", "Cyclic Agent");
        }

        @Behavior(type = BehaviorType.CYCLIC, interval = "5s")
        public void periodic() {
        }
    }

    static class MillisecondIntervalAgent extends BaseAgent {
        public MillisecondIntervalAgent() {
            super("ms", "MS Agent");
        }

        @dev.agenor.core.annotations.Behavior(type = BehaviorType.CYCLIC, interval = "500ms")
        public void periodic() {
        }
    }

    static class MinutesIntervalAgent extends BaseAgent {
        public MinutesIntervalAgent() {
            super("min", "Min Agent");
        }

        @Behavior(type = BehaviorType.CYCLIC, interval = "2min")
        public void periodic() {
        }
    }

    static class HoursIntervalAgent extends BaseAgent {
        public HoursIntervalAgent() {
            super("hours", "Hours Agent");
        }

        @dev.agenor.core.annotations.Behavior(type = BehaviorType.CYCLIC, interval = "1h")
        public void periodic() {
        }
    }

    static class InvalidDurationAgent extends BaseAgent {
        public InvalidDurationAgent() {
            super("invalid", "Invalid Agent");
        }

        @dev.agenor.core.annotations.Behavior(type = BehaviorType.CYCLIC, interval = "invalid")
        public void periodic() {
        }
    }

    static class WakerTestAgent extends BaseAgent {
        public WakerTestAgent() {
            super("waker", "Waker Agent");
        }

        @dev.agenor.core.annotations.Behavior(type = BehaviorType.WAKER, initialDelay = "10s")
        public void wakeUp() {
        }
    }

    static class EventDrivenTestAgent extends BaseAgent {
        public EventDrivenTestAgent() {
            super("event", "Event Agent");
        }

        @Behavior(type = BehaviorType.EVENT_DRIVEN)
        public void onEvent() {
        }
    }

    static class CustomBehaviorAgent extends BaseAgent {
        public CustomBehaviorAgent() {
            super("custom", "Custom Agent");
        }

        @Behavior(type = BehaviorType.CUSTOM, interval = "3s")
        public void customAction() {
        }
    }

    static class FsmWithoutExtensionAgent extends BaseAgent {
        public FsmWithoutExtensionAgent() {
            super("fsm-no-ext", "FSM Without Extension");
        }

        @Behavior(type = BehaviorType.FSM, fsmInitialState = "IDLE")
        public void fsmAction() {
        }
    }

    static class MessageHandlerTestAgent extends BaseAgent {
        public MessageHandlerTestAgent() {
            super("handler", "Handler Agent");
        }

        @AgenorMessageHandler("test.topic")
        public void handleMessage(Message msg) {
        }
    }

    static class NoAutoSubscribeAgent extends BaseAgent {
        public NoAutoSubscribeAgent() {
            super("no-auto", "No Auto Agent");
        }

        @AgenorMessageHandler(value = "test.topic", autoSubscribe = false)
        public void handleMessage(Message msg) {
        }
    }

    static class InvalidMessageHandlerAgent extends BaseAgent {
        public InvalidMessageHandlerAgent() {
            super("invalid-handler", "Invalid Handler");
        }

        @AgenorMessageHandler("test.topic")
        public void wrongSignature() {
            // Wrong signature - no Message parameter
        }
    }

    static class InvalidBehaviorMethodAgent extends BaseAgent {
        public InvalidBehaviorMethodAgent() {
            super("invalid-behavior", "Invalid Behavior");
        }

        @Behavior(type = BehaviorType.ONE_SHOT)
        public void wrongSignature(String param) {
            // Wrong signature - has parameters
        }
    }

    static class NoAutoStartAgent extends BaseAgent {
        public NoAutoStartAgent() {
            super("no-auto-start", "No Auto Start");
        }

        @dev.agenor.core.annotations.Behavior(type = BehaviorType.ONE_SHOT, autoStart = false)
        public void notAutoStarted() {
        }
    }

    static class ExceptionThrowingAgent extends BaseAgent {
        public ExceptionThrowingAgent() {
            super("exception", "Exception Agent");
        }

        @dev.agenor.core.annotations.Behavior(type = BehaviorType.ONE_SHOT)
        private void privateMethod() {
            // Private method will cause issues
        }
    }

    /** Declares both annotations, so a subclass inherits them. */
    abstract static class AnnotatedBaseAgent extends BaseAgent {
        String handledBy = "none";

        protected AnnotatedBaseAgent(String id) {
            super(id, id);
        }

        @dev.agenor.core.annotations.Behavior(type = BehaviorType.ONE_SHOT)
        public void inheritedBehaviour() {
            // Test method
        }

        @AgenorMessageHandler("inherited.topic")
        public void handleInherited(Message msg) {
            handledBy = "base";
        }
    }

    /** Adds nothing: everything it has is inherited. */
    static class InheritingAgent extends AnnotatedBaseAgent {
        public InheritingAgent() {
            super("inheriting-agent");
        }
    }

    /**
     * Overrides the inherited handler and re-annotates it, as {@code DialogueHandlerRegistry}'s
     * {@code OverridingWorker} does. Java does not inherit method annotations, so the
     * annotation has to be on the most-derived declaration for the scan to see it at all.
     */
    static class OverridingAgent extends AnnotatedBaseAgent {
        public OverridingAgent() {
            super("overriding-agent");
        }

        @Override
        @AgenorMessageHandler("inherited.topic")
        public void handleInherited(Message msg) {
            handledBy = "subclass";
        }
    }

    /** Carries both annotations on default methods — shared behaviour without a base class. */
    interface AnnotatedCapability {

        void record(String who);

        @dev.agenor.core.annotations.Behavior(type = BehaviorType.ONE_SHOT)
        default void interfaceBehaviour() {
            // Test method
        }

        @AgenorMessageHandler("interface.topic")
        default void handleFromInterface(Message msg) {
            record("interface");
        }
    }

    /** Takes both from the interface, declaring neither itself. */
    static class InterfaceImplementingAgent extends BaseAgent implements AnnotatedCapability {
        String handledBy = "none";

        public InterfaceImplementingAgent() {
            super("interface-agent", "Interface Agent");
        }

        @Override
        public void record(String who) {
            handledBy = who;
        }
    }

    /** Overrides the interface's default handler and re-annotates it. */
    static class InterfaceOverridingAgent extends BaseAgent implements AnnotatedCapability {
        String handledBy = "none";

        public InterfaceOverridingAgent() {
            super("interface-overriding-agent", "Interface Overriding Agent");
        }

        @Override
        public void record(String who) {
            handledBy = who;
        }

        @Override
        @AgenorMessageHandler("interface.topic")
        public void handleFromInterface(Message msg) {
            handledBy = "class";
        }
    }
}
