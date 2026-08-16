package dev.agenor.adapters.messaging.redis;

import dev.agenor.core.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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

    @Test
    @DisplayName("a POJO payload survives the round-trip and is readable via getContent (ADR-030)")
    void roundTrip_pojoContent_readableViaGetContent() {
        // Given a domain payload — the shape a dialogue message carries
        var bid      = new Bid("worker-1", 9.5, 3);
        var original = Message.builder().topic("cfp.replies").senderId("worker-1").content(bid).build();

        // When it crosses the transport
        var decoded = MessageCodec.decode(MessageCodec.encode(original));

        // Then the raw content is a Map — `Object content` carries no type on the wire, so this
        // is what any cast in a handler actually receives. Asserted explicitly: it is the defect
        // ADR-030 exists for, and the reason getContent below cannot be reduced back to a cast.
        assertThat(decoded.content()).isInstanceOf(Map.class);

        // ...and the typed accessor gives the payload back
        assertThat(decoded.getContent(Bid.class)).isEqualTo(bid);
    }

    @Test
    @DisplayName("a payload that never left the JVM is returned by reference, not converted")
    void inMemoryPath_pojoContent_sameReference() {
        // Given the same payload on the in-memory path (no encode/decode)
        var bid = new Bid("worker-1", 9.5, 3);
        var msg = Message.builder().topic("cfp.replies").content(bid).build();

        // Then the two transports differ in what they hand over, and only this test pins the
        // in-memory contract: same reference, no Jackson round trip
        assertThat(msg.getContent(Bid.class)).isSameAs(bid);
    }

    @Test
    @DisplayName("an Instant inside a POJO payload survives the round-trip")
    void roundTrip_pojoWithInstant_preservesTime() {
        // Given a payload with a java.time field — the case that breaks if core's mapper and
        // this codec's mapper are configured differently
        var deadline = Instant.parse("2026-08-16T10:15:30Z");
        var original = Message.builder().topic("commitments").content(new Promise("ship-it", deadline)).build();

        // When
        var decoded = MessageCodec.decode(MessageCodec.encode(original));

        // Then
        assertThat(decoded.getContent(Promise.class).deadline()).isEqualTo(deadline);
    }

    @Test
    @DisplayName("requesting a scalar payload as a record names both types instead of failing at the use site")
    void getContent_incompatibleScalar_reportsBothTypes() {
        var decoded = MessageCodec.decode(MessageCodec.encode(
                Message.builder().topic("t").content(42).build()));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> decoded.getContent(Bid.class))
                .withMessageContaining(Bid.class.getName());
    }

    @Test
    @DisplayName("asking for an unrelated record converts leniently — it does not fail (ADR-030 limitation)")
    void getContent_unrelatedRecord_convertsLeniently() {
        // Given a Bid on the wire
        var decoded = MessageCodec.decode(MessageCodec.encode(
                Message.builder().topic("t").content(new Bid("worker-1", 9.5, 3)).build()));

        // When it is read as an unrelated record
        var wrong = decoded.getContent(Promise.class);

        // Then the conversion succeeds with empty fields rather than throwing. The mapper is
        // lenient on unknown properties — deliberately, so a receiver still reads a payload
        // whose sender added a field — and a Map has no identity to contradict the request.
        // The typed accessor removes the ClassCastException, not the need to ask for the right
        // type. Pinned here so the behaviour is a documented decision, not a surprise.
        assertThat(wrong).isNotNull();
        assertThat(wrong.what()).isNull();
        assertThat(wrong.deadline()).isNull();
    }

    record Bid(String workerId, double cost, int timeSeconds) {}

    record Promise(String what, Instant deadline) {}
}
