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
