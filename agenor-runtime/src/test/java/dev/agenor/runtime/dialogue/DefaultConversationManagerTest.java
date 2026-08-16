package dev.agenor.runtime.dialogue;

import dev.agenor.core.Message;
import dev.agenor.core.messaging.MessageDispatcher;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.core.dialogue.protocol.ProtocolState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultConversationManagerTest {

    private MessageDispatcher messageDispatcher;
    private DefaultConversationManager manager;

    @BeforeEach
    void setUp() {
        messageDispatcher = mock(MessageDispatcher.class);
        when(messageDispatcher.sendTo(any(Message.class)))
            .thenReturn(CompletableFuture.completedFuture(null));

        manager = new DefaultConversationManager("local-agent", messageDispatcher);
    }

    @Test
    void shouldCreateConversationOnRequest() {
        var future = manager.request("remote-agent", "do task", Duration.ofSeconds(5));

        var conversations = manager.getActiveConversations();
        assertThat(conversations).hasSize(1);

        var conv = conversations.get(0);
        assertThat(conv.getInitiatorId()).isEqualTo("local-agent");
        assertThat(conv.getParticipantId()).isEqualTo("remote-agent");
        assertThat(conv.isInitiator()).isTrue();
    }

    @Test
    void shouldCreateConversationOnQuery() {
        manager.query("remote-agent", "what time?", Duration.ofSeconds(5));

        var conversations = manager.getActiveConversations();
        assertThat(conversations).hasSize(1);
        assertThat(conversations.get(0).getProtocol())
            .isPresent()
            .hasValueSatisfying(p -> assertThat(p.getId()).isEqualTo("query"));
    }

    @Test
    void shouldHandleIncomingMessage() {
        var incoming = DialogueMessage.builder()
            .conversationId("conv-1")
            .senderId("remote-agent")
            .receiverId("local-agent")
            .performative(Performative.REQUEST)
            .content("do something")
            .protocol("request")
            .build();

        manager.handleIncoming(incoming);

        var conversation = manager.getConversation("conv-1");
        assertThat(conversation).isPresent();
        assertThat(conversation.get().isInitiator()).isFalse();
        assertThat(conversation.get().getHistory()).hasSize(1);
    }

    @Test
    void shouldReplyToMessage() {
        var incoming = DialogueMessage.builder()
            .conversationId("conv-1")
            .senderId("remote-agent")
            .receiverId("local-agent")
            .performative(Performative.REQUEST)
            .content("do something")
            .build();

        manager.handleIncoming(incoming);
        manager.reply(incoming, Performative.AGREE, "ok");

        var conversation = manager.getConversation("conv-1");
        assertThat(conversation.get().getHistory()).hasSize(2);
    }

    @Test
    void shouldCancelConversation() {
        manager.request("remote-agent", "task", Duration.ofSeconds(5));

        var conv = manager.getActiveConversations().get(0);
        manager.cancel(conv.getId());

        assertThat(manager.getConversation(conv.getId()).get().getState())
            .isEqualTo(ProtocolState.CANCELLED);
    }

    @Test
    void shouldGetConversationsWith() {
        manager.request("agent-a", "task1", Duration.ofSeconds(5));
        manager.request("agent-a", "task2", Duration.ofSeconds(5));
        manager.request("agent-b", "task3", Duration.ofSeconds(5));

        var withA = manager.getConversationsWith("agent-a");
        var withB = manager.getConversationsWith("agent-b");

        assertThat(withA).hasSize(2);
        assertThat(withB).hasSize(1);
    }

    @Test
    void shouldCompleteResponseFutureOnIncoming() {
        // Start a request
        var future = manager.request("remote-agent", "task", Duration.ofSeconds(5));

        // Get the conversation to find the message ID
        var conv = manager.getActiveConversations().get(0);
        var requestMsg = conv.getHistory().get(0);

        // Simulate response
        var response = DialogueMessage.builder()
            .conversationId(conv.getId())
            .senderId("remote-agent")
            .receiverId("local-agent")
            .performative(Performative.INFORM)
            .content("result")
            .inReplyTo(requestMsg.id())
            .build();

        manager.handleIncoming(response);

        assertThat(future).isCompletedWithValueMatching(
            msg -> msg.performative() == Performative.INFORM
        );
    }

    @Test
    void shouldNotifyMessageHandler() {
        var received = new AtomicReference<DialogueMessage>();

        var incoming = DialogueMessage.builder()
            .conversationId("conv-1")
            .senderId("remote-agent")
            .performative(Performative.REQUEST)
            .build();

        manager.handleIncoming(incoming);
        manager.onMessage("conv-1", received::set);

        var second = DialogueMessage.builder()
            .conversationId("conv-1")
            .senderId("remote-agent")
            .performative(Performative.INFORM)
            .build();

        manager.handleIncoming(second);

        assertThat(received.get()).isNotNull();
        assertThat(received.get().performative()).isEqualTo(Performative.INFORM);
    }

    @Test
    void shouldNotCompleteResponseFutureOnIntermediateAgree() {
        var future = manager.request("remote-agent", "task", Duration.ofSeconds(5));

        var conv = manager.getActiveConversations().get(0);
        var requestMsg = conv.getHistory().get(0);

        var agree = DialogueMessage.builder()
            .conversationId(conv.getId())
            .senderId("remote-agent")
            .receiverId("local-agent")
            .performative(Performative.AGREE)
            .content("agreed")
            .inReplyTo(requestMsg.id())
            .build();

        manager.handleIncoming(agree);

        assertThat(future).isNotDone();
    }

    @Test
    void shouldCompleteResponseFutureOnInformAfterAgree() {
        var future = manager.request("remote-agent", "task", Duration.ofSeconds(5));

        var conv = manager.getActiveConversations().get(0);
        var requestMsg = conv.getHistory().get(0);

        var agree = DialogueMessage.builder()
            .conversationId(conv.getId())
            .senderId("remote-agent")
            .receiverId("local-agent")
            .performative(Performative.AGREE)
            .content("agreed")
            .inReplyTo(requestMsg.id())
            .build();
        manager.handleIncoming(agree);

        var inform = DialogueMessage.builder()
            .conversationId(conv.getId())
            .senderId("remote-agent")
            .receiverId("local-agent")
            .performative(Performative.INFORM)
            .content("result")
            .inReplyTo(requestMsg.id())
            .build();
        manager.handleIncoming(inform);

        assertThat(future).isCompletedWithValueMatching(
            msg -> msg.performative() == Performative.INFORM
        );
    }

    @Test
    void shouldCompleteResponseFutureOnFailureAfterAgree() {
        var future = manager.request("remote-agent", "task", Duration.ofSeconds(5));

        var conv = manager.getActiveConversations().get(0);
        var requestMsg = conv.getHistory().get(0);

        var agree = DialogueMessage.builder()
            .conversationId(conv.getId())
            .senderId("remote-agent")
            .receiverId("local-agent")
            .performative(Performative.AGREE)
            .content("agreed")
            .inReplyTo(requestMsg.id())
            .build();
        manager.handleIncoming(agree);

        var failure = DialogueMessage.builder()
            .conversationId(conv.getId())
            .senderId("remote-agent")
            .receiverId("local-agent")
            .performative(Performative.FAILURE)
            .content("could not do it")
            .inReplyTo(requestMsg.id())
            .build();
        manager.handleIncoming(failure);

        assertThat(future).isCompletedWithValueMatching(
            msg -> msg.performative() == Performative.FAILURE
        );
    }

    @Test
    void shouldCompleteResponseFutureImmediatelyOnRefuse() {
        var future = manager.request("remote-agent", "task", Duration.ofSeconds(5));

        var conv = manager.getActiveConversations().get(0);
        var requestMsg = conv.getHistory().get(0);

        var refuse = DialogueMessage.builder()
            .conversationId(conv.getId())
            .senderId("remote-agent")
            .receiverId("local-agent")
            .performative(Performative.REFUSE)
            .content("can't do it")
            .inReplyTo(requestMsg.id())
            .build();

        manager.handleIncoming(refuse);

        assertThat(future).isCompletedWithValueMatching(
            msg -> msg.performative() == Performative.REFUSE
        );
    }

    @Test
    void shouldStillResolveCallForProposalsOnFirstProposeReply() {
        var future = manager.callForProposals(
            java.util.List.of("bidder-1"), "task-spec", Duration.ofSeconds(5));

        var conv = manager.getConversationsWith("bidder-1").get(0);
        var cfpMsg = conv.getHistory().get(0);

        var propose = DialogueMessage.builder()
            .conversationId(conv.getId())
            .senderId("bidder-1")
            .receiverId("local-agent")
            .performative(Performative.PROPOSE)
            .content("my bid")
            .inReplyTo(cfpMsg.id())
            .protocol("contract-net")
            .build();

        manager.handleIncoming(propose);

        assertThat(future).isCompletedWithValueMatching(
            responses -> responses.size() == 1
                && responses.get(0).performative() == Performative.PROPOSE
        );
    }

    @Test
    void shouldNotifyOnMessageHandlerForAgreeWithoutCompletingFuture() {
        var future = manager.request("remote-agent", "task", Duration.ofSeconds(5));

        var conv = manager.getActiveConversations().get(0);
        var requestMsg = conv.getHistory().get(0);

        var received = new AtomicReference<DialogueMessage>();
        manager.onMessage(conv.getId(), received::set);

        var agree = DialogueMessage.builder()
            .conversationId(conv.getId())
            .senderId("remote-agent")
            .receiverId("local-agent")
            .performative(Performative.AGREE)
            .content("agreed")
            .inReplyTo(requestMsg.id())
            .build();

        manager.handleIncoming(agree);

        assertThat(received.get()).isNotNull();
        assertThat(received.get().performative()).isEqualTo(Performative.AGREE);
        assertThat(future).isNotDone();
    }

    @Test
    void shouldTrackCommitments() {
        var incoming = DialogueMessage.builder()
            .conversationId("conv-1")
            .senderId("remote-agent")
            .receiverId("local-agent")
            .performative(Performative.REQUEST)
            .content("task")
            .build();

        manager.handleIncoming(incoming);
        manager.reply(incoming, Performative.AGREE, "ok");

        var commitments = manager.getCommitmentTracker()
            .getActiveAsPerformer("local-agent");

        assertThat(commitments).hasSize(1);
    }

    @Test
    void shouldNotLeakPendingResponseWhenRequestTimesOut() {
        // Given a request to a participant that never replies
        var future = manager.request("silent-agent", "task", Duration.ofMillis(50));
        assertThat(manager.pendingResponseCount()).isEqualTo(1);

        // When the timeout fires
        assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class)
            .hasCauseInstanceOf(TimeoutException.class);

        // Then the pending entry is gone and the conversation is marked TIMEOUT
        assertThat(manager.pendingResponseCount()).isZero();
        assertThat(manager.getConversationsWith("silent-agent").get(0).getState())
            .isEqualTo(ProtocolState.TIMEOUT);
    }

    @Test
    void shouldNotLeakPendingResponseWhenRequestSucceeds() {
        var future = manager.request("remote-agent", "task", Duration.ofSeconds(5));
        var requestMsg = manager.getActiveConversations().get(0).getHistory().get(0);

        manager.handleIncoming(DialogueMessage.builder()
            .conversationId(requestMsg.conversationId())
            .senderId("remote-agent")
            .receiverId("local-agent")
            .performative(Performative.INFORM)
            .content("done")
            .inReplyTo(requestMsg.id())
            .build());

        assertThat(future).isCompleted();
        assertThat(manager.pendingResponseCount()).isZero();
    }

    @Test
    void shouldRemoveTerminalConversationsOnCleanup() {
        // Given one completed and one still-open conversation
        var completed = DialogueMessage.builder()
            .conversationId("conv-done")
            .senderId("remote-agent")
            .receiverId("local-agent")
            .performative(Performative.REQUEST)
            .protocol("request")
            .build();
        manager.handleIncoming(completed);
        manager.onMessage("conv-done", msg -> { });
        manager.cancel("conv-done");

        manager.request("remote-agent", "still running", Duration.ofSeconds(30));

        // When sweeping with a zero retention window
        int removed = manager.cleanup(Duration.ZERO);

        // Then only the terminal conversation is dropped
        assertThat(removed).isEqualTo(1);
        assertThat(manager.getConversation("conv-done")).isEmpty();
        assertThat(manager.getActiveConversations()).hasSize(1);
    }

    @Test
    void shouldKeepActiveConversationsOnCleanupHoweverOld() {
        manager.request("remote-agent", "task", Duration.ofSeconds(30));

        assertThat(manager.cleanup(Duration.ZERO)).isZero();
        assertThat(manager.getActiveConversations()).hasSize(1);
    }

    @Test
    void shouldNotLeakPendingResponsesWhenCallForProposalsDeadlineExpires() throws Exception {
        // Given a CFP to three participants, none of which proposes
        var future = manager.callForProposals(
            List.of("worker-1", "worker-2", "worker-3"), "spec", Duration.ofMillis(50));
        assertThat(manager.pendingResponseCount()).isEqualTo(3);

        // When the deadline expires
        var proposals = future.get(2, TimeUnit.SECONDS);

        // Then no proposal is collected and no pending entry survives
        assertThat(proposals).isEmpty();
        assertThat(manager.pendingResponseCount()).isZero();
    }

    /**
     * The dispatcher delivers every message on its own virtual thread with no per-recipient
     * serialisation, so two messages opening the same unknown conversation arrive concurrently.
     * A check-then-act (get → null → new → put) builds two conversations and keeps one, and the
     * message that went into the discarded one is gone — not a wrong state, a lost message.
     */
    @Test
    void shouldNotLoseAMessageWhenTwoArriveOnAnUnknownConversationAtOnce() throws Exception {
        for (int round = 0; round < 200; round++) {
            // Given two messages for a conversation this agent has never seen
            var conversationId = "conv-" + round;
            var start = new CountDownLatch(1);
            var done = new CountDownLatch(2);

            // When they are handled at the same instant
            for (int i = 0; i < 2; i++) {
                var message = DialogueMessage.builder()
                    .conversationId(conversationId)
                    .senderId("remote-agent")
                    .receiverId("local-agent")
                    .performative(Performative.INFORM)
                    .content("msg-" + i)
                    .build();
                Thread.ofVirtual().start(() -> {
                    try {
                        start.await();
                        manager.handleIncoming(message);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

            // Then both are in the history of the one conversation that exists
            assertThat(manager.getConversation(conversationId).orElseThrow().getHistory())
                .as("round %d — one message was written to a conversation that was then "
                    + "discarded by the racing put", round)
                .hasSize(2);
        }
    }
}
