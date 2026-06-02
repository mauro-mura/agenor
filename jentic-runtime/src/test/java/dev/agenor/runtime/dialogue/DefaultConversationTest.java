package dev.agenor.runtime.dialogue;

import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.core.dialogue.protocol.ProtocolState;
import dev.agenor.runtime.dialogue.protocol.RequestProtocol;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultConversationTest {

    @Test
    void shouldCreateConversation() {
        var conversation = new DefaultConversation(
            "conv-1",
            new RequestProtocol(),
            "agent-a",
            "agent-b",
            true
        );

        assertThat(conversation.getId()).isEqualTo("conv-1");
        assertThat(conversation.getInitiatorId()).isEqualTo("agent-a");
        assertThat(conversation.getParticipantId()).isEqualTo("agent-b");
        assertThat(conversation.isInitiator()).isTrue();
        assertThat(conversation.getState()).isEqualTo(ProtocolState.INITIATED);
        assertThat(conversation.isComplete()).isFalse();
    }

    @Test
    void shouldTrackMessageHistory() {
        var conversation = new DefaultConversation(
            "conv-1", null, "agent-a", "agent-b", true
        );

        var msg1 = DialogueMessage.builder()
            .senderId("agent-a")
            .performative(Performative.REQUEST)
            .content("do task")
            .build();

        var msg2 = DialogueMessage.builder()
            .senderId("agent-b")
            .performative(Performative.AGREE)
            .content("ok")
            .build();

        conversation.addMessage(msg1);
        conversation.addMessage(msg2);

        assertThat(conversation.getHistory()).hasSize(2);
        assertThat(conversation.getMessageCount()).isEqualTo(2);
        assertThat(conversation.getLastMessage()).isPresent().contains(msg2);
    }

    @Test
    void shouldTransitionStateWithProtocol() {
        var conversation = new DefaultConversation(
            "conv-1",
            new RequestProtocol(),
            "agent-a",
            "agent-b",
            true
        );

        var request = DialogueMessage.builder()
            .senderId("agent-a")
            .performative(Performative.REQUEST)
            .build();
        conversation.addMessage(request);
        assertThat(conversation.getState()).isEqualTo(ProtocolState.AWAITING_RESPONSE);

        var agree = DialogueMessage.builder()
            .senderId("agent-b")
            .performative(Performative.AGREE)
            .build();
        conversation.addMessage(agree);
        assertThat(conversation.getState()).isEqualTo(ProtocolState.AGREED);

        var inform = DialogueMessage.builder()
            .senderId("agent-b")
            .performative(Performative.INFORM)
            .build();
        conversation.addMessage(inform);
        assertThat(conversation.getState()).isEqualTo(ProtocolState.COMPLETED);
        assertThat(conversation.isComplete()).isTrue();
    }

    @Test
    void shouldUpdateLastActivity() throws InterruptedException {
        var conversation = new DefaultConversation(
            "conv-1", null, "agent-a", "agent-b", true
        );

        var initialActivity = conversation.getLastActivity();
        Thread.sleep(10);

        conversation.addMessage(DialogueMessage.builder()
            .senderId("agent-a")
            .performative(Performative.INFORM)
            .build());

        assertThat(conversation.getLastActivity()).isAfter(initialActivity);
    }

    @Test
    void shouldAllowManualStateChange() {
        var conversation = new DefaultConversation(
            "conv-1", null, "agent-a", "agent-b", true
        );

        conversation.setState(ProtocolState.CANCELLED);

        assertThat(conversation.getState()).isEqualTo(ProtocolState.CANCELLED);
        assertThat(conversation.isComplete()).isTrue();
    }
}
