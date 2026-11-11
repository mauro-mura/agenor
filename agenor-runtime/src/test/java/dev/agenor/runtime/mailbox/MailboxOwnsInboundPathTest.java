package dev.agenor.runtime.mailbox;

import dev.agenor.core.Message;
import dev.agenor.core.MessageHandler;
import dev.agenor.core.mailbox.AgentMailbox;
import dev.agenor.core.messaging.Subscription;
import dev.agenor.core.annotations.AgenorMessageHandler;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.core.dialogue.DialogueHandler;
import dev.agenor.core.telemetry.AgenorTelemetry;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.annotation.AgentAnnotationProcessor;
import dev.agenor.runtime.dialogue.DialogueCapability;
import dev.agenor.runtime.directory.InMemoryAgentDirectory;
import dev.agenor.runtime.messaging.InMemoryMessageDispatcher;
import dev.agenor.runtime.scheduler.SimpleBehaviorScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The invariant ADR-032 exists for: one agent, one persistent recipient subscription, held by
 * the mailbox — asserted against the dispatcher rather than inferred from delivery working.
 *
 * <p>Before this, a {@code BaseAgent} with a {@link DialogueCapability} took out two rival
 * subscriptions on its own channel, and which of them survived depended on the dispatcher.
 */
class MailboxOwnsInboundPathTest {

    private InMemoryMessageDispatcher dispatcher;
    private InMemoryAgentDirectory directory;
    private SimpleBehaviorScheduler scheduler;

    @BeforeEach
    void setUp() {
        directory = new InMemoryAgentDirectory();
        dispatcher = new InMemoryMessageDispatcher(directory, AgenorTelemetry.noop());
        scheduler = new SimpleBehaviorScheduler();
        scheduler.start().join();
    }

    @AfterEach
    void tearDown() {
        scheduler.stop().join();
    }

    private MixedAgent startedAgent() {
        var agent = new MixedAgent("mixed-agent");
        agent.setMessageDispatcher(dispatcher);
        agent.setAgentDirectory(directory);
        agent.setBehaviorScheduler(scheduler);
        new AgentAnnotationProcessor(dispatcher, Optional.empty()).processAnnotations(agent);
        agent.start().join();
        return agent;
    }

    /** Bounded wait: no Awaitility on the classpath, and no fixed sleep in tests. */
    private static void awaitUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 2_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
    }

    @Test
    @Timeout(10)
    @DisplayName("an agent that speaks dialogue still holds exactly one recipient subscription")
    void oneSubscriptionPerAgent() {
        // Given an agent with both an annotated handler and a dialogue capability
        var agent = startedAgent();

        try {
            // Then the dispatcher holds one handler for it: the mailbox
            assertThat(dispatcher.receiverSubscriptionCount("mixed-agent")).isEqualTo(1);
            assertThat(agent.mailbox()).isPresent();
        } finally {
            agent.stop().join();
        }
    }

    @Test
    @Timeout(10)
    @DisplayName("stopping the agent releases the subscription and the mailbox")
    void stopReleasesTheInboundPath() {
        // Given
        var agent = startedAgent();

        // When
        agent.stop().join();

        // Then
        assertThat(dispatcher.receiverSubscriptionCount("mixed-agent")).isZero();
        assertThat(agent.mailbox()).isEmpty();
    }

    @Test
    @Timeout(10)
    @DisplayName("both inbound paths still work through the single subscription")
    void bothPathsStillDeliver() {
        // Given
        var agent = startedAgent();

        try {
            // When an annotated-topic message and a dialogue message arrive
            dispatcher.sendTo(Message.builder()
                    .topic("task.process")
                    .senderId("coordinator")
                    .receiverId("mixed-agent")
                    .content("do work")
                    .build());
            dispatcher.sendTo(DialogueMessage.builder()
                    .conversationId("conv-1")
                    .senderId("coordinator")
                    .receiverId("mixed-agent")
                    .performative(Performative.REQUEST)
                    .content("ask something")
                    .build()
                    .toMessage());

            // Then each reaches its own handler, and neither reaches the other's
            awaitUntil(() -> agent.annotated.get() != null && agent.dialogueMessages.get() == 1);
            assertThat(agent.annotated.get()).isNotNull()
                    .extracting(Message::content).isEqualTo("do work");
            assertThat(agent.dialogueMessages.get()).isEqualTo(1);
            // The annotated handler matched, so the fallback never ran; the dialogue message
            // was routed away from the direct path entirely (ADR-029 D3 as amended by ADR-032)
            assertThat(agent.fallbacks.get()).isZero();
        } finally {
            agent.stop().join();
        }
    }

    @Test
    @Timeout(10)
    @DisplayName("dialogue initialized before the agent starts still ends up on the mailbox")
    void manualInitializeBeforeStartReattaches() {
        // Given dialogue wired by hand before start, which the docs call safe — at that point
        // there is no mailbox yet, so dialogue can only subscribe to the dispatcher
        var agent = new MixedAgent("mixed-agent");
        agent.setMessageDispatcher(dispatcher);
        agent.setAgentDirectory(directory);
        agent.setBehaviorScheduler(scheduler);
        new AgentAnnotationProcessor(dispatcher, Optional.empty()).processAnnotations(agent);
        agent.dialogue.initialize();
        assertThat(dispatcher.receiverSubscriptionCount("mixed-agent")).isEqualTo(1);

        try {
            // When the agent starts and its mailbox opens
            agent.start().join();

            // Then dialogue has moved onto it: still one subscription, not two
            assertThat(dispatcher.receiverSubscriptionCount("mixed-agent")).isEqualTo(1);

            // ...and routing is the mailbox's, so dialogue traffic does not reach the
            // direct path
            dispatcher.sendTo(DialogueMessage.builder()
                    .conversationId("conv-2")
                    .senderId("coordinator")
                    .receiverId("mixed-agent")
                    .performative(Performative.REQUEST)
                    .content("ask something")
                    .build()
                    .toMessage());

            awaitUntil(() -> agent.dialogueMessages.get() == 1);
            assertThat(agent.dialogueMessages.get()).isEqualTo(1);
            assertThat(agent.fallbacks.get()).isZero();
        } finally {
            agent.stop().join();
        }
    }

    @Test
    @Timeout(10)
    @DisplayName("an agent can substitute its own AgentMailbox and the runtime uses it")
    void createMailboxSubstitutesTheImplementation() {
        // Given an agent that hands the runtime its own AgentMailbox implementation
        var agent = new SubstitutedMailboxAgent();
        agent.setMessageDispatcher(dispatcher);
        agent.setAgentDirectory(directory);
        agent.setBehaviorScheduler(scheduler);
        new AgentAnnotationProcessor(dispatcher, Optional.empty()).processAnnotations(agent);
        agent.start().join();

        try {
            // Then that instance is the agent's inbound path — not a DefaultAgentMailbox the
            // runtime built behind its back
            assertThat(agent.installed.get()).isNotNull();
            assertThat(agent.mailbox()).containsSame(agent.installed.get());

            // When traffic for both lanes arrives
            dispatcher.sendTo(Message.builder()
                    .topic("task.process")
                    .senderId("coordinator")
                    .receiverId("substituted-agent")
                    .content("do work")
                    .build());
            dispatcher.sendTo(DialogueMessage.builder()
                    .conversationId("conv-3")
                    .senderId("coordinator")
                    .receiverId("substituted-agent")
                    .performative(Performative.REQUEST)
                    .content("ask something")
                    .build()
                    .toMessage());

            // Then it passed through the substitute, and both lanes still work
            awaitUntil(() -> agent.annotated.get() != null && agent.dialogueMessages.get() == 1);
            assertThat(agent.installed.get().offered.get()).isEqualTo(2);
            assertThat(agent.annotated.get().content()).isEqualTo("do work");
            assertThat(agent.dialogueMessages.get()).isEqualTo(1);
            assertThat(agent.fallbacks.get()).isZero();
        } finally {
            agent.stop().join();
        }
    }

    @Test
    @Timeout(10)
    @DisplayName("a dialogue message that arrives while the agent starts reaches the dialogue "
            + "handler, not onDirectMessage")
    void aDialogueMessageArrivingDuringStartReachesTheDialogueHandler() {
        // Given an agent whose onStart() does not return until a dialogue message for it has
        // reached its mailbox — before the start hook that attaches its DialogueCapability
        var agent = new MessagedWhileStarting(dispatcher);
        agent.setMessageDispatcher(dispatcher);
        agent.setAgentDirectory(directory);
        agent.setBehaviorScheduler(scheduler);
        new AgentAnnotationProcessor(dispatcher, Optional.empty()).processAnnotations(agent);

        try {
            // When it starts
            agent.start().join();

            // Then the message waited for the dialogue lane. Until 0.41.0 the mailbox routed it
            // on arrival, to onDirectMessage, whose default ignores it, and on Redis the
            // completed outcome acknowledged it: the message was lost.
            awaitUntil(() -> agent.dialogueMessages.get() == 1 || agent.fallbacks.get() > 0);
            assertThat(agent.queuedDuringStart).as("message queued while starting").isTrue();
            assertThat(agent.dialogueMessages.get()).isEqualTo(1);
            assertThat(agent.fallbacks.get()).isZero();
        } finally {
            agent.stop().join();
        }
    }

    @Test
    @Timeout(10)
    @DisplayName("an agent that fails to start releases its subscription")
    void aFailedStartReleasesTheInboundPath() {
        // Given an agent whose onStart() throws
        var agent = new MixedAgent("failing-agent") {
            @Override
            protected void onStart() {
                throw new IllegalStateException("start fails on purpose");
            }
        };
        agent.setMessageDispatcher(dispatcher);
        agent.setAgentDirectory(directory);
        agent.setBehaviorScheduler(scheduler);

        // When
        try {
            agent.start().join();
        } catch (RuntimeException expected) {
            // the failure is the point
        }

        // Then nothing is left holding its channel: a mailbox that never opened would keep
        // whatever reached it, and a transport waiting on those outcomes with it
        assertThat(dispatcher.receiverSubscriptionCount("failing-agent")).isZero();
        assertThat(agent.mailbox()).isEmpty();
    }

    @Test
    @Timeout(10)
    @DisplayName("a dialogue handler in flight when the agent stops still sends its reply")
    void aDialogueHandlerInFlightAtStopStillReplies() throws Exception {
        // Given an agent whose dialogue handler is running when the agent is told to stop
        var agent = new RepliesSlowly();
        agent.setMessageDispatcher(dispatcher);
        agent.setAgentDirectory(directory);
        agent.setBehaviorScheduler(scheduler);
        new AgentAnnotationProcessor(dispatcher, Optional.empty()).processAnnotations(agent);
        agent.start().join();
        var replies = new java.util.concurrent.CopyOnWriteArrayList<Message>();
        var requester = dispatcher.subscribeRecipient("coordinator",
                MessageHandler.sync(replies::add));

        try {
            dispatcher.sendTo(DialogueMessage.builder()
                    .conversationId("conv-stop")
                    .senderId("coordinator")
                    .receiverId("slow-replier")
                    .performative(Performative.REQUEST)
                    .content("work")
                    .build()
                    .toMessage());
            assertThat(agent.handling.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            // When the agent stops, and the handler finishes only after the stop has begun
            var stopped = agent.stop();
            Thread.sleep(200);
            agent.release.countDown();
            stopped.join();

            // Then the handler found its DialogueCapability still there, and the reply went out.
            // Until 0.41.0 the capability shut down from a stop hook before the mailbox waited
            // for running handlers, so inform() threw and the reply was lost.
            awaitUntil(() -> !replies.isEmpty());
            assertThat(agent.replyFailure.get()).isNull();
            assertThat(replies).hasSize(1);
            assertThat(DialogueMessage.fromMessage(replies.get(0)).performative())
                    .isEqualTo(Performative.INFORM);
        } finally {
            agent.release.countDown();
            requester.unsubscribe();
        }
    }

    /** Replies to a REQUEST only once released, so a stop can begin while it is in flight. */
    static class RepliesSlowly extends BaseAgent {

        final DialogueCapability dialogue = new DialogueCapability(this);
        final java.util.concurrent.CountDownLatch handling = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        final AtomicReference<Throwable> replyFailure = new AtomicReference<>();

        RepliesSlowly() {
            super("slow-replier", "slow-replier");
        }

        @DialogueHandler(performatives = Performative.REQUEST)
        public void handleRequest(DialogueMessage message) throws InterruptedException {
            handling.countDown();
            release.await();
            try {
                dialogue.inform(message, "done").join();
            } catch (RuntimeException e) {
                replyFailure.set(e);
                throw e;
            }
        }
    }

    /**
     * Sends itself a dialogue message from {@code onStart()} and waits until it is queued, so it
     * arrives inside the window between subscribing and the dialogue start hook.
     */
    static class MessagedWhileStarting extends BaseAgent {

        final DialogueCapability dialogue = new DialogueCapability(this);
        final AtomicInteger dialogueMessages = new AtomicInteger();
        final AtomicInteger fallbacks = new AtomicInteger();
        private final InMemoryMessageDispatcher sender;
        volatile boolean queuedDuringStart;

        MessagedWhileStarting(InMemoryMessageDispatcher sender) {
            super("starting-agent", "starting-agent");
            this.sender = sender;
        }

        @Override
        protected void onStart() {
            sender.sendTo(DialogueMessage.builder()
                    .conversationId("conv-start")
                    .senderId("coordinator")
                    .receiverId("starting-agent")
                    .performative(Performative.REQUEST)
                    .content("arrived while starting")
                    .build()
                    .toMessage());
            long deadline = System.currentTimeMillis() + 2_000;
            var box = mailbox().orElseThrow();
            while (System.currentTimeMillis() < deadline
                    && box.size() == 0 && dialogueMessages.get() == 0 && fallbacks.get() == 0) {
                Thread.onSpinWait();
            }
            queuedDuringStart = box.size() == 1;
        }

        @DialogueHandler(performatives = Performative.REQUEST)
        public void handleRequest(DialogueMessage message) {
            dialogueMessages.incrementAndGet();
        }

        @Override
        protected void onDirectMessage(Message message) {
            fallbacks.incrementAndGet();
        }
    }

    /** An agent that receives on both inbound paths — the shape ADR-032 D-1 is about. */
    static class MixedAgent extends BaseAgent {

        final DialogueCapability dialogue = new DialogueCapability(this);
        final AtomicReference<Message> annotated = new AtomicReference<>();
        final AtomicInteger dialogueMessages = new AtomicInteger();
        final AtomicInteger fallbacks = new AtomicInteger();

        MixedAgent(String agentId) {
            super(agentId, agentId);
        }

        @AgenorMessageHandler("task.process")
        public void handleTask(Message message) {
            annotated.set(message);
        }

        @DialogueHandler(performatives = Performative.REQUEST)
        public void handleRequest(DialogueMessage message) {
            dialogueMessages.incrementAndGet();
        }

        @Override
        protected void onDirectMessage(Message message) {
            fallbacks.incrementAndGet();
        }
    }

    /**
     * Supplies its own mailbox through the factory hook, wrapping the runtime's default.
     *
     * <p>Declares its own handlers rather than inheriting {@link MixedAgent}'s: the annotation
     * processor reads {@code getDeclaredMethods()}, so an inherited
     * {@code @AgenorMessageHandler} is never registered.
     */
    static class SubstitutedMailboxAgent extends BaseAgent {

        final DialogueCapability dialogue = new DialogueCapability(this);
        final AtomicReference<CountingMailbox> installed = new AtomicReference<>();
        final AtomicReference<Message> annotated = new AtomicReference<>();
        final AtomicInteger dialogueMessages = new AtomicInteger();
        final AtomicInteger fallbacks = new AtomicInteger();

        SubstitutedMailboxAgent() {
            super("substituted-agent", "Substituted Mailbox");
        }

        @Override
        protected AgentMailbox createMailbox(MessageHandler pushConsumer) {
            var box = new CountingMailbox(super.createMailbox(pushConsumer));
            installed.set(box);
            return box;
        }

        @AgenorMessageHandler("task.process")
        public void handleTask(Message message) {
            annotated.set(message);
        }

        @DialogueHandler(performatives = Performative.REQUEST)
        public void handleRequest(DialogueMessage message) {
            dialogueMessages.incrementAndGet();
        }

        @Override
        protected void onDirectMessage(Message message) {
            fallbacks.incrementAndGet();
        }
    }

    /** A decorator over whatever the default is — the shape a user extension would take. */
    static final class CountingMailbox implements AgentMailbox {

        private final AgentMailbox delegate;
        final AtomicInteger offered = new AtomicInteger();

        CountingMailbox(AgentMailbox delegate) {
            this.delegate = delegate;
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> offer(Message message) {
            offered.incrementAndGet();
            return delegate.offer(message);
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public int capacity() {
            return delegate.capacity();
        }

        @Override
        public Subscription registerDialogueConsumer(MessageHandler handler) {
            return delegate.registerDialogueConsumer(handler);
        }

        @Override
        public void start() {
            delegate.start();
        }

        @Override
        public void stop() {
            delegate.stop();
        }
    }
}
