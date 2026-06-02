package dev.agenor.adapters.messaging.redis;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.agenor.core.Message;

import java.util.HashMap;
import java.util.Map;

/**
 * Serialises and deserialises {@link Message} objects to/from Redis stream entry fields.
 *
 * <p>The full message is stored as a JSON {@code payload} field. Envelope fields are
 * duplicated as individual stream fields to allow server-side inspection without
 * deserialising the payload.
 */
final class MessageCodec {

    static final String FIELD_PAYLOAD  = "payload";
    static final String FIELD_MSG_ID   = "msg_id";
    static final String FIELD_TOPIC    = "topic";
    static final String FIELD_SENDER   = "sender_id";
    static final String FIELD_RECEIVER = "receiver_id";
    static final String FIELD_CORR     = "correlation_id";
    static final String FIELD_TS       = "timestamp";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private MessageCodec() { throw new UnsupportedOperationException(); }

    static Map<String, String> encode(Message msg) {
        try {
            var fields = new HashMap<String, String>();
            fields.put(FIELD_PAYLOAD,  MAPPER.writeValueAsString(msg));
            fields.put(FIELD_MSG_ID,   orEmpty(msg.id()));
            fields.put(FIELD_TOPIC,    orEmpty(msg.topic()));
            fields.put(FIELD_SENDER,   orEmpty(msg.senderId()));
            fields.put(FIELD_RECEIVER, orEmpty(msg.receiverId()));
            fields.put(FIELD_CORR,     orEmpty(msg.correlationId()));
            fields.put(FIELD_TS,       msg.timestamp() != null ? msg.timestamp().toString() : "");
            return fields;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to encode message " + msg.id(), e);
        }
    }

    static Message decode(Map<String, String> fields) {
        try {
            var payload = fields.get(FIELD_PAYLOAD);
            if (payload == null || payload.isBlank()) {
                throw new IllegalArgumentException("Stream entry missing '" + FIELD_PAYLOAD + "' field");
            }
            return MAPPER.readValue(payload, Message.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decode stream message", e);
        }
    }

    private static String orEmpty(String s) { return s != null ? s : ""; }
}
