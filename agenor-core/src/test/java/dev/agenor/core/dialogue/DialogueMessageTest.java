package dev.agenor.core.dialogue;

import dev.agenor.core.Message;
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
    void shouldClassifyAMessageProducedByToMessageAsDialogue() {
        var message = DialogueMessage.builder()
            .senderId("agent-1")
            .receiverId("agent-2")
            .performative(Performative.REQUEST)
            .content("do something")
            .build()
            .toMessage();

        assertThat(DialogueMessage.isDialogueMessage(message)).isTrue();
    }

    @Test
    void shouldNotClassifyAPlainMessageAsDialogue() {
        // Given a message sent with sendTo(), carrying no dialogue headers
        var message = Message.builder()
            .senderId("agent-1")
            .receiverId("agent-2")
            .content("just a notification")
            .build();

        assertThat(DialogueMessage.isDialogueMessage(message)).isFalse();
    }

    @Test
    void shouldNotClassifyAnUnknownPerformativeAsDialogue() {
        // Given a peer speaking a dialect this runtime does not know
        var message = Message.builder()
            .senderId("agent-1")
            .header("performative", "TELEPATHY")
            .content("data")
            .build();

        // Then it is not routed to dialogue...
        assertThat(DialogueMessage.isDialogueMessage(message)).isFalse();

        // ...while fromMessage() stays lenient for callers that already know what they hold
        assertThat(DialogueMessage.fromMessage(message).performative())
            .isEqualTo(Performative.INFORM);
    }

    @Test
    void shouldClassifyAndConvertALowerCasePerformativeConsistently() {
        // Given a header that the converter accepts case-insensitively
        var message = Message.builder()
            .senderId("agent-1")
            .header("performative", "inform")
            .content("data")
            .build();

        // Then the classifier agrees with it — both read the same parse
        assertThat(DialogueMessage.isDialogueMessage(message)).isTrue();
        assertThat(DialogueMessage.fromMessage(message).performative())
            .isEqualTo(Performative.INFORM);
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

    @Test
    void shouldDeclareThePayloadTypeInTheContentClassHeader() {
        // Given
        var message = DialogueMessage.builder()
            .senderId("agent-1")
            .performative(Performative.PROPOSE)
            .content(new Bid(9.5, 3))
            .build();

        // When
        var headers = message.toMessage().headers();

        // Then
        assertThat(headers).containsEntry(
            DialogueMessage.CONTENT_CLASS_HEADER, Bid.class.getName());
    }

    @Test
    void shouldNotDeclareAContentClassWhenThereIsNoContent() {
        var message = DialogueMessage.builder()
            .senderId("agent-1")
            .performative(Performative.CANCEL)
            .build();

        assertThat(message.toMessage().headers())
            .doesNotContainKey(DialogueMessage.CONTENT_CLASS_HEADER);
    }

    @Test
    void shouldNotReuseTheContentTypeHeaderName() {
        // Given — `content-type` already means a domain label (ADR-005's example) and an
        // HTTP-style media type (docs/message-filtering.md); a Java class name is neither
        var message = DialogueMessage.builder()
            .senderId("agent-1")
            .performative(Performative.PROPOSE)
            .content(new Bid(9.5, 3))
            .build();

        assertThat(message.toMessage().headers()).doesNotContainKey("content-type");
    }

    @Test
    void shouldReturnTheSameReferenceWhenContentIsAlreadyTyped() {
        // Given a dialogue message that never left the JVM
        var bid = new Bid(9.5, 3);
        var message = DialogueMessage.builder()
            .senderId("agent-1")
            .performative(Performative.PROPOSE)
            .content(bid)
            .build();

        // When / Then
        assertThat(message.contentAs(Bid.class)).isSameAs(bid);
    }

    @Test
    void shouldConvertContentThatArrivedAsAMap() {
        // Given the shape a payload has after a serialising transport rebuilt it from JSON
        var message = DialogueMessage.builder()
            .senderId("agent-1")
            .performative(Performative.PROPOSE)
            .content(Map.of("cost", 9.5, "timeSeconds", 3))
            .build();

        // When / Then — this is the call that makes cross-runtime dialogue usable
        assertThat(message.contentAs(Bid.class)).isEqualTo(new Bid(9.5, 3));
    }

    @Test
    void shouldNameTheSenderDeclaredTypeWhenConversionFails() {
        // Given a message reconstructed from the wire, whose content-class says one thing
        // while the handler asks for another
        var onTheWire = DialogueMessage.builder()
            .senderId("agent-1")
            .performative(Performative.PROPOSE)
            .content(new Bid(9.5, 3))
            .build()
            .toMessage();
        var received = DialogueMessage.fromMessage(onTheWire);

        // When / Then
        assertThatThrownBy(() -> received.contentAs(Instant.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(Bid.class.getName())
            .hasMessageContaining(DialogueMessage.CONTENT_CLASS_HEADER);
    }

    @Test
    void shouldReturnNullContentAsNull() {
        var message = DialogueMessage.builder()
            .senderId("agent-1")
            .performative(Performative.CANCEL)
            .build();

        assertThat(message.contentAs(Bid.class)).isNull();
    }

    // Test record standing in for a domain payload — the Contract-Net bid shape
    record Bid(double cost, int timeSeconds) {}
}
