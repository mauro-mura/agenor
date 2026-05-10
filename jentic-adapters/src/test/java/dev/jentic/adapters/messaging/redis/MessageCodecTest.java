package dev.jentic.adapters.messaging.redis;

import dev.jentic.core.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

@DisplayName("MessageCodec")
class MessageCodecTest {

    @Test
    @DisplayName("encode then decode preserves all message fields")
    void roundTrip_fullMessage_preservesAllFields() {
        var original = Message.builder()
                .id("msg-001")
                .topic("orders.created")
                .senderId("agent-a")
                .receiverId("agent-b")
                .correlationId("corr-xyz")
                .content("order-payload")
                .header("priority", "HIGH")
                .build();

        var fields  = MessageCodec.encode(original);
        var decoded = MessageCodec.decode(fields);

        assertThat(decoded.id()).isEqualTo("msg-001");
        assertThat(decoded.topic()).isEqualTo("orders.created");
        assertThat(decoded.senderId()).isEqualTo("agent-a");
        assertThat(decoded.receiverId()).isEqualTo("agent-b");
        assertThat(decoded.correlationId()).isEqualTo("corr-xyz");
        assertThat(decoded.content()).isEqualTo("order-payload");
        assertThat(decoded.headers()).containsEntry("priority", "HIGH");
    }

    @Test
    @DisplayName("encode stores payload as a non-blank JSON field")
    void encode_storesPayloadField() {
        var msg    = Message.builder().topic("t").content("data").build();
        var fields = MessageCodec.encode(msg);

        assertThat(fields).containsKey(MessageCodec.FIELD_PAYLOAD);
        assertThat(fields.get(MessageCodec.FIELD_PAYLOAD)).isNotBlank();
    }

    @Test
    @DisplayName("encode stores envelope fields alongside payload")
    void encode_storesEnvelopeFields() {
        var msg    = Message.builder().id("e-1").topic("evt").senderId("s").receiverId("r").build();
        var fields = MessageCodec.encode(msg);

        assertThat(fields).containsKeys(
                MessageCodec.FIELD_MSG_ID,
                MessageCodec.FIELD_TOPIC,
                MessageCodec.FIELD_SENDER,
                MessageCodec.FIELD_RECEIVER,
                MessageCodec.FIELD_CORR,
                MessageCodec.FIELD_TS
        );
        assertThat(fields.get(MessageCodec.FIELD_MSG_ID)).isEqualTo("e-1");
        assertThat(fields.get(MessageCodec.FIELD_TOPIC)).isEqualTo("evt");
        assertThat(fields.get(MessageCodec.FIELD_SENDER)).isEqualTo("s");
        assertThat(fields.get(MessageCodec.FIELD_RECEIVER)).isEqualTo("r");
    }

    @Test
    @DisplayName("encode handles null optional fields without throwing")
    void encode_nullOptionalFields_noException() {
        var msg    = Message.builder().topic("t").build();
        var fields = MessageCodec.encode(msg);

        assertThat(fields.get(MessageCodec.FIELD_SENDER)).isEmpty();
        assertThat(fields.get(MessageCodec.FIELD_RECEIVER)).isEmpty();
        assertThat(fields.get(MessageCodec.FIELD_CORR)).isEmpty();
    }

    @Test
    @DisplayName("decode throws IllegalStateException when payload field is missing")
    void decode_missingPayload_throws() {
        assertThatIllegalStateException().isThrownBy(() ->
                MessageCodec.decode(Map.of("msg_id", "x")));
    }

    @Test
    @DisplayName("decode throws IllegalStateException when payload field is blank")
    void decode_blankPayload_throws() {
        assertThatIllegalStateException().isThrownBy(() ->
                MessageCodec.decode(Map.of(MessageCodec.FIELD_PAYLOAD, "  ")));
    }

    @Test
    @DisplayName("decode throws IllegalStateException when payload is not valid JSON")
    void decode_invalidJson_throws() {
        assertThatIllegalStateException().isThrownBy(() ->
                MessageCodec.decode(Map.of(MessageCodec.FIELD_PAYLOAD, "not-json")));
    }

    @Test
    @DisplayName("round-trip preserves message with null content")
    void roundTrip_nullContent_preserved() {
        var original = Message.builder().topic("t").build();
        var decoded  = MessageCodec.decode(MessageCodec.encode(original));
        assertThat(decoded.content()).isNull();
    }
}
