package dev.agenor.runtime.dialogue;

import dev.agenor.core.Agent;
import dev.agenor.core.Message;
import dev.agenor.core.MessageHandler;
import dev.agenor.core.messaging.MessageDispatcher;
import dev.agenor.core.messaging.Subscription;
import dev.agenor.core.dialogue.Commitment;
import dev.agenor.core.dialogue.CommitmentState;
import dev.agenor.core.dialogue.ConversationManager;
import dev.agenor.core.dialogue.DialogueHandler;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.core.dialogue.protocol.Protocol;
import dev.agenor.core.dialogue.protocol.ProtocolState;
import dev.agenor.core.telemetry.AgenorTelemetry;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.dialogue.protocol.ProtocolRegistry;
import dev.agenor.runtime.directory.InMemoryAgentDirectory;
import dev.agenor.runtime.messaging.InMemoryMessageDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DialogueCapabilityTest {

    private TestDialogueAgent agent;
    private MessageDispatcher messageService;
    private DialogueCapability capability;
    private MessageHandler messageHandler;

    @BeforeEach
    void setUp() {
        agent = new TestDialogueAgent("test-agent");
        messageService = mock(MessageDispatcher.class);

        var stubSub = Subscription.of("sub-1", () -> {});
        when(messageService.subscribeRecipient(eq("test-agent"), any(MessageHandler.class)))
            .thenAnswer(invocation -> {
                messageHandler = invocation.getArgument(1);
                return stubSub;
            });

        when(messageService.sendTo(any(Message.class)))
            .thenReturn(CompletableFuture.completedFuture(null));

        capability = new DialogueCapability(agent);
    }

    @Test
    void shouldInitializeAndScanHandlers() {
        capability.initialize(messageService);

        verify(messageService).subscribeRecipient(eq("test-agent"), any());
    }

    @Test
    void shouldDispatchIncomingMessages() {
        capability.initialize(messageService);

        // Simulate incoming message
        var incomingMsg = Message.builder()
            .id("msg-1")
            .senderId("remote-agent")
            .receiverId("test-agent")
            .content("do task")
            .header("conversationId", "conv-1")
            .header("performative", "REQUEST")
            .build();

        // Handler should have been captured during initialize
        assertThat(messageHandler).isNotNull();
        messageHandler.handle(incomingMsg);

        assertThat(agent.lastRequest.get()).isNotNull();
        assertThat(agent.lastRequest.get().performative()).isEqualTo(Performative.REQUEST);
    }

    @Test
    void shouldIgnorePlainMessagesInsteadOfFabricatingAConversation() {
        capability.initialize(messageService);

        // Given a plain sendTo() message: no performative, no conversationId
        var plainMsg = Message.builder()
            .id("plain-1")
            .senderId("remote-agent")
            .receiverId("test-agent")
            .content("just a notification")
            .build();

        // When it arrives on the recipient channel the dialogue capability shares with the agent
        messageHandler.handle(plainMsg);

        // Then no phantom conversation is created — this is the assertion that covers the leak,
        // since such a conversation is never terminal and the retention sweep cannot reach it
        assertThat(capability.getActiveConversations()).isEmpty();
        // ...and no dialogue handler runs on traffic that is not dialogue
        assertThat(agent.informCount.get()).isZero();
    }

    @Test
    void shouldIgnoreMessagesCarryingAnUnknownPerformative() {
        capability.initialize(messageService);

        // Given a peer speaking a dialect this runtime does not know
        var futureMsg = Message.builder()
            .id("future-1")
            .senderId("remote-agent")
            .receiverId("test-agent")
            .header("conversationId", "conv-future")
            .header("performative", "TELEPATHY")
            .content("data")
            .build();

        messageHandler.handle(futureMsg);

        // Then it is not executed as a fabricated INFORM
        assertThat(capability.getActiveConversations()).isEmpty();
        assertThat(agent.informCount.get()).isZero();
    }

    @Test
    void shouldSendRequest() {
        capability.initialize(messageService);

        capability.request("remote-agent", "do something");

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageService).sendTo(captor.capture());

        Message sent = captor.getValue();
        assertThat(sent.receiverId()).isEqualTo("remote-agent");
        assertThat(sent.headers().get("performative")).isEqualTo("REQUEST");
    }

    @Test
    void shouldSendQuery() {
        capability.initialize(messageService);

        capability.query("remote-agent", "what time?");

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageService).sendTo(captor.capture());

        Message sent = captor.getValue();
        assertThat(sent.headers().get("performative")).isEqualTo("QUERY");
    }

    @Test
    void shouldReplyToMessage() {
        capability.initialize(messageService);

        var original = DialogueMessage.builder()
            .id("msg-1")
            .conversationId("conv-1")
            .senderId("remote-agent")
            .receiverId("test-agent")
            .performative(Performative.REQUEST)
            .content("do task")
            .build();

        capability.agree(original);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageService).sendTo(captor.capture());

        Message reply = captor.getValue();
        assertThat(reply.receiverId()).isEqualTo("remote-agent");
        assertThat(reply.headers().get("performative")).isEqualTo("AGREE");
    }

    @Test
    void shouldProvideConvenienceReplyMethods() {
        capability.initialize(messageService);

        var original = DialogueMessage.builder()
            .senderId("remote").receiverId("test-agent")
            .performative(Performative.REQUEST).build();

        // Test various reply methods
        capability.refuse(original, "too busy");
        capability.inform(original, "result");
        capability.failure(original, "error");

        verify(messageService, times(3)).sendTo(any(Message.class));
    }

    @Test
    void shouldShutdownCleanly() {
        capability.initialize(messageService);
        capability.shutdown();
        // subscription.unsubscribe() was called on the Subscription returned by subscribeRecipient
        verify(messageService).subscribeRecipient(eq("test-agent"), any());
    }

    @Test
    void shouldTrackActiveConversations() {
        capability.initialize(messageService);

        capability.request("agent-1", "task1");
        capability.request("agent-2", "task2");

        assertThat(capability.getActiveConversations()).hasSize(2);
    }

    @Test
    void shouldStartReaperOnInitializeAndStopItOnShutdown() {
        assertThat(capability.isReaperRunning()).isFalse();

        capability.initialize(messageService);
        assertThat(capability.isReaperRunning()).isTrue();

        capability.shutdown();
        assertThat(capability.isReaperRunning()).isFalse();
    }

    @Test
    void shouldNotStartASecondReaperWhenInitializedTwice() {
        capability.initialize(messageService);
        capability.initialize(messageService);

        capability.shutdown();

        assertThat(capability.isReaperRunning()).isFalse();
    }

    @Test
    void shouldSweepTerminatedConversationsPeriodically() {
        // Given a capability whose sweep runs every 20ms with no retention window
        var sweeping = DialogueCapability.builder(agent)
            .retention(Duration.ZERO)
            .sweepInterval(Duration.ofMillis(20))
            .build();
        sweeping.initialize(messageService);
        try {
            sweeping.request("agent-1", "task");
            var conversationId = sweeping.getActiveConversations().get(0).getId();
            sweeping.getConversationManager().cancel(conversationId);

            // When the sweep runs, the terminated conversation is dropped
            long deadline = System.currentTimeMillis() + 2_000;
            while (sweeping.getConversation(conversationId).isPresent()
                    && System.currentTimeMillis() < deadline) {
                Thread.onSpinWait();
            }

            assertThat(sweeping.getConversation(conversationId)).isEmpty();
        } finally {
            sweeping.shutdown();
        }
    }

    @Test
    void shouldMarkOverdueCommitmentsViolatedFromTheSweep() {
        // Given a tracker whose commitments are overdue almost as soon as they are created,
        // and a capability sweeping every 20ms
        var tracker = new DefaultCommitmentTracker(Duration.ofMillis(1));
        var sweeping = DialogueCapability.builder(agent)
            .commitmentTracker(tracker)
            .sweepInterval(Duration.ofMillis(20))
            .build();
        sweeping.initialize(messageService);
        try {
            var commitment = tracker.createFromMessage(DialogueMessage.builder()
                .senderId("requester")
                .receiverId("test-agent")
                .performative(Performative.REQUEST)
                .content("deliver the report")
                .build());
            assertThat(commitment.getState()).isEqualTo(CommitmentState.PENDING);

            // When the sweep runs — nobody ever calls checkViolations() by hand
            long deadline = System.currentTimeMillis() + 2_000;
            while (commitment.getState() != CommitmentState.VIOLATED
                    && System.currentTimeMillis() < deadline) {
                Thread.onSpinWait();
            }

            // Then the commitment is observable as violated through the tracker
            assertThat(sweeping.getCommitmentTracker().get(commitment.getId()))
                .get()
                .extracting(Commitment::getState)
                .isEqualTo(CommitmentState.VIOLATED);
        } finally {
            sweeping.shutdown();
        }
    }

    // =========================================================================
    // LIFECYCLE AUTO-REGISTRATION AND FACTORY SEAM
    // =========================================================================

    @Test
    void shouldInitializeAutomaticallyOnStartForLifecycleHooksAgent() throws Exception {
        // Given a BaseAgent-backed agent with no manual dialogue wiring at all
        var hookAgent = new HookedDialogueAgent("hooked-agent");
        var dispatcher = new InMemoryMessageDispatcher(
            new InMemoryAgentDirectory(), AgenorTelemetry.noop());
        hookAgent.setMessageDispatcher(dispatcher);

        // When it starts
        hookAgent.start().join();

        try {
            // Then dialogue works without initialize() ever being called by hand
            assertThat(hookAgent.dialogue.getActiveConversations()).isEmpty();
            hookAgent.dialogue.request("someone", "task", Duration.ofMillis(50));
            assertThat(hookAgent.dialogue.getActiveConversations()).hasSize(1);
        } finally {
            hookAgent.stop().join();
        }
    }

    @Test
    void shouldRouteEachInboundMessageToOnlyOnePathOnARealAgent() {
        // Given a BaseAgent that both handles direct messages and speaks dialogue. It holds two
        // subscriptions on its own recipient channel (its own, plus the capability's), which the
        // mocked-dispatcher tests above cannot reproduce.
        var mixed = new MixedTrafficAgent("mixed-agent");
        var dispatcher = new InMemoryMessageDispatcher(
            new InMemoryAgentDirectory(), AgenorTelemetry.noop());
        mixed.setMessageDispatcher(dispatcher);
        mixed.start().join();

        try {
            // When one plain message and one dialogue message arrive
            dispatcher.sendTo(Message.builder()
                .senderId("someone")
                .receiverId("mixed-agent")
                .content("plain payload")
                .build());
            dispatcher.sendTo(DialogueMessage.builder()
                .conversationId("conv-real")
                .senderId("someone")
                .receiverId("mixed-agent")
                .performative(Performative.REQUEST)
                .content("do the thing")
                .build()
                .toMessage());

            // deliverToReceiver() starts a virtual thread per handler without awaiting them, so
            // sendTo()'s future completes before the handlers have run: wait for the counters.
            awaitUntil(() -> mixed.directMessages.get() == 2 && mixed.dialogueMessages.get() == 1);

            // Then both reach the direct path — BaseAgent deliberately does not filter (D3)...
            assertThat(mixed.directMessages.get()).isEqualTo(2);
            // ...only the dialogue one reaches a @DialogueHandler...
            assertThat(mixed.dialogueMessages.get()).isEqualTo(1);
            // ...and exactly one conversation exists: the plain message fabricated none. This is
            // the assertion that covers the leak — a phantom conversation is never terminal, so
            // the retention sweep could not reclaim it.
            assertThat(mixed.dialogue.getActiveConversations()).hasSize(1);
            assertThat(mixed.dialogue.getConversation("conv-real")).isPresent();
        } finally {
            mixed.stop().join();
        }
    }

    /** Bounded wait: no Awaitility on the classpath, and no fixed sleep in tests. */
    private static void awaitUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 2_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
    }

    @Test
    void shouldShutdownAutomaticallyOnStopForLifecycleHooksAgent() {
        var hookAgent = new HookedDialogueAgent("hooked-agent-2");
        hookAgent.setMessageDispatcher(
            new InMemoryMessageDispatcher(new InMemoryAgentDirectory(), AgenorTelemetry.noop()));

        hookAgent.start().join();
        assertThat(hookAgent.dialogue.isReaperRunning()).isTrue();

        hookAgent.stop().join();
        assertThat(hookAgent.dialogue.isReaperRunning()).isFalse();
    }

    @Test
    void shouldConstructWithoutNpeWhenAgentIdIsAssignedAfterSuper() {
        // The MultiInstanceWorker shape: getAgentId() reads a field set after super(),
        // so it returns null while the field initializer below is running.
        var worker = new LateIdAgent("late-id");

        assertThat(worker.getAgentId()).isEqualTo("late-id");
        assertThat(worker.dialogue).isNotNull();
    }

    @Test
    @SuppressWarnings("deprecation")
    void shouldStillHonourTheDispatcherPassedToTheDeprecatedInitialize() {
        // TestDialogueAgent.getMessageDispatcher() returns null, so the deprecated overload
        // cannot delegate to the no-arg initialize() - it must use what it was given.
        // Every other call in this class relies on the same guarantee.
        assertThat(agent.getMessageDispatcher()).isNull();

        capability.initialize(messageService);

        verify(messageService).subscribeRecipient(eq("test-agent"), any());
        assertThat(capability.getActiveConversations()).isEmpty();
    }

    @Test
    void shouldFailFastWithActionableMessageWhenNotInitialized() {
        var uninitialized = new DialogueCapability(agent);

        assertThatThrownBy(() -> uninitialized.request("someone", "task"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not initialized")
            .hasMessageContaining("initialize()");

        assertThatThrownBy(uninitialized::getActiveConversations)
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldScanHandlersOnlyOnceWhenInitializedTwice() {
        capability.initialize(messageService);
        capability.initialize(messageService);

        var incoming = Message.builder()
            .id("msg-1")
            .senderId("remote-agent")
            .receiverId("test-agent")
            .content("do task")
            .header("conversationId", "conv-1")
            .header("performative", "REQUEST")
            .build();
        messageHandler.handle(incoming).join();

        assertThat(agent.requestCount.get()).isEqualTo(1);
    }

    @Test
    void shouldUseTheConversationManagerSuppliedByTheFactory() {
        var stub = mock(ConversationManager.class);
        var custom = DialogueCapability.builder(agent)
            .conversationManagerFactory((id, dispatcher, protocols, commitments) -> stub)
            .build();

        custom.initialize(messageService);

        assertThat(custom.getConversationManager()).isSameAs(stub);
    }

    @Test
    void shouldUseTheCommitmentTrackerSuppliedByTheBuilder() {
        var tracker = new DefaultCommitmentTracker();
        var custom = DialogueCapability.builder(agent)
            .commitmentTracker(tracker)
            .build();

        custom.initialize(messageService);

        assertThat(custom.getCommitmentTracker()).isSameAs(tracker);
    }

    @Test
    void shouldUseTheProtocolRegistrySuppliedByTheBuilder() {
        // Given a registry whose "request" protocol is replaced by one that never leaves
        // its initial state
        var registry = new ProtocolRegistry();
        registry.register(new FrozenRequestProtocol());

        var custom = DialogueCapability.builder(agent).protocolRegistry(registry).build();
        custom.initialize(messageService);

        // When a request conversation starts
        custom.request("remote-agent", "task", Duration.ofSeconds(5));

        // Then the custom transitions are the ones applied
        assertThat(custom.getActiveConversations().get(0).getState())
            .isEqualTo(ProtocolState.INITIATED);
    }

    /** A "request" protocol that accepts nothing and never transitions. */
    static class FrozenRequestProtocol implements Protocol {
        @Override public String getId() { return "request"; }
        @Override public String getDisplayName() { return "Frozen Request"; }
        @Override public ProtocolState getInitialState() { return ProtocolState.INITIATED; }
        @Override public ProtocolState nextState(ProtocolState current, Performative received, boolean isInitiator) {
            return current;
        }
        @Override public java.util.Set<Performative> allowedPerformatives(ProtocolState state, boolean isInitiator) {
            return java.util.Set.of();
        }
    }

    /** BaseAgent-backed agent that does no manual dialogue wiring whatsoever. */
    static class HookedDialogueAgent extends BaseAgent {
        final DialogueCapability dialogue = new DialogueCapability(this);

        HookedDialogueAgent(String id) {
            super(id, id);
        }
    }

    /** Mixes direct messaging and dialogue on one agent — the shape ADR-029 D3 is about. */
    static class MixedTrafficAgent extends BaseAgent {
        final DialogueCapability dialogue = new DialogueCapability(this);
        final AtomicInteger directMessages = new AtomicInteger();
        final AtomicInteger dialogueMessages = new AtomicInteger();

        MixedTrafficAgent(String id) {
            super(id, id);
        }

        @Override
        protected void onDirectMessage(Message message) {
            directMessages.incrementAndGet();
        }

        @DialogueHandler(performatives = Performative.REQUEST)
        public void handleRequest(DialogueMessage msg) {
            dialogueMessages.incrementAndGet();
        }
    }

    /** Reproduces the MultiInstanceWorker shape: agentId resolved from a late-assigned field. */
    static class LateIdAgent extends BaseAgent {
        private final String lateId;
        final DialogueCapability dialogue = new DialogueCapability(this);

        LateIdAgent(String id) {
            super("placeholder", "placeholder");
            this.lateId = id;
        }

        @Override
        public String getAgentId() {
            return lateId;
        }
    }

    // Test agent with dialogue handlers
    static class TestDialogueAgent implements Agent {
        private final String id;
        final AtomicReference<DialogueMessage> lastRequest = new AtomicReference<>();
        final AtomicInteger requestCount = new AtomicInteger();
        final AtomicInteger informCount = new AtomicInteger();

        TestDialogueAgent(String id) {
            this.id = id;
        }

        @Override public String getAgentId() { return id; }
        @Override public String getAgentName() { return id; }
        @Override public boolean isRunning() { return true; }
        @Override public CompletableFuture<Void> start() { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Void> stop() { return CompletableFuture.completedFuture(null); }
        @Override public void addBehavior(dev.agenor.core.Behavior behavior) { }
        @Override public void removeBehavior(String behaviorId) { }
        @Override public dev.agenor.core.messaging.MessageDispatcher getMessageDispatcher() { return null; }

        @DialogueHandler(performatives = Performative.REQUEST)
        public void handleRequest(DialogueMessage msg) {
            lastRequest.set(msg);
            requestCount.incrementAndGet();
        }

        /** Present so that a plain message reinterpreted as a synthetic INFORM would be seen. */
        @DialogueHandler(performatives = Performative.INFORM)
        public void handleInform(DialogueMessage msg) {
            informCount.incrementAndGet();
        }
    }
}
