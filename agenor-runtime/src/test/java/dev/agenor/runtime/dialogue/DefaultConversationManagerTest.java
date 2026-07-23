package dev.agenor.runtime.dialogue;

import dev.agenor.core.Message;
import dev.agenor.core.messaging.MessageDispatcher;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.core.dialogue.protocol.ProtocolState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
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
}
