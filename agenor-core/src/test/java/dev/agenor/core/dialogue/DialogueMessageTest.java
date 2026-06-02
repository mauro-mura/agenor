package dev.agenor.core.dialogue;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DialogueMessageTest {

    @Test
    void shouldBuildBasicMessage() {
        // Given
        var message = DialogueMessage.builder()
            .senderId("agent-1")
            .receiverId("agent-2")
            .performative(Performative.REQUEST)
            .content("do something")
            .build();

        // Then
        assertThat(message.id()).isNotNull();
        assertThat(message.conversationId()).isNotNull();
        assertThat(message.senderId()).isEqualTo("agent-1");
        assertThat(message.receiverId()).isEqualTo("agent-2");
        assertThat(message.performative()).isEqualTo(Performative.REQUEST);
        assertThat(message.content()).isEqualTo("do something");
        assertThat(message.timestamp()).isNotNull();
    }

    @Test
    void shouldRejectNullSenderId() {
        assertThatThrownBy(() -> DialogueMessage.builder()
            .performative(Performative.INFORM)
            .build())
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullPerformative() {
        assertThatThrownBy(() -> DialogueMessage.builder()
            .senderId("agent-1")
            .build())
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldCreateReply() {
        // Given
        var original = DialogueMessage.builder()
            .senderId("agent-1")
            .receiverId("agent-2")
            .performative(Performative.REQUEST)
            .protocol("request")
            .content("do something")
            .build();

        // When
        var reply = original.reply(Performative.AGREE, "ok", "agent-2");

        // Then
        assertThat(reply.conversationId()).isEqualTo(original.conversationId());
        assertThat(reply.senderId()).isEqualTo("agent-2");
        assertThat(reply.receiverId()).isEqualTo("agent-1");
        assertThat(reply.performative()).isEqualTo(Performative.AGREE);
        assertThat(reply.inReplyTo()).isEqualTo(original.id());
        assertThat(reply.protocol()).isEqualTo("request");
    }

    @Test
    void shouldConvertToAndFromMessage() {
        // Given
        var original = DialogueMessage.builder()
            .senderId("agent-1")
            .receiverId("agent-2")
            .performative(Performative.INFORM)
            .content("data")
            .protocol("query")
            .build();

        // When
        var message = original.toMessage();
        var reconstructed = DialogueMessage.fromMessage(message);

        // Then
        assertThat(reconstructed.id()).isEqualTo(original.id());
        assertThat(reconstructed.conversationId()).isEqualTo(original.conversationId());
        assertThat(reconstructed.senderId()).isEqualTo(original.senderId());
        assertThat(reconstructed.performative()).isEqualTo(original.performative());
        assertThat(reconstructed.protocol()).isEqualTo(original.protocol());
    }

    @Test
    void shouldIdentifyReplyMessages() {
        var original = DialogueMessage.builder()
            .senderId("agent-1")
            .performative(Performative.REQUEST)
            .build();

        var reply = original.reply(Performative.AGREE, null, "agent-2");

        assertThat(original.isReply()).isFalse();
        assertThat(reply.isReply()).isTrue();
    }

    @Test
    void shouldDetermineIfResponseExpected() {
        var request = DialogueMessage.builder()
            .senderId("a").performative(Performative.REQUEST).build();
        var inform = DialogueMessage.builder()
            .senderId("a").performative(Performative.INFORM).build();

        assertThat(request.expectsResponse()).isTrue();
        assertThat(inform.expectsResponse()).isFalse();
    }

    @Test
    void shouldReturnProtocolAsOptional() {
        var withProtocol = DialogueMessage.builder()
            .senderId("a").performative(Performative.REQUEST).protocol("request").build();
        var withoutProtocol = DialogueMessage.builder()
            .senderId("a").performative(Performative.REQUEST).build();

        assertThat(withProtocol.getProtocol()).isPresent().contains("request");
        assertThat(withoutProtocol.getProtocol()).isEmpty();
    }

    @Test
    void shouldMakeMetadataImmutable() {
        var message = new DialogueMessage(
            "id", "conv", "sender", null, Performative.INFORM,
            null, null, null, Instant.now(), Map.of("key", "value")
        );

        assertThatThrownBy(() -> message.metadata().put("new", "value"))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
