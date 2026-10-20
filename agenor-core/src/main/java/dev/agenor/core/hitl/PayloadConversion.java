package dev.agenor.core.hitl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agenor.core.Message;

import java.util.Objects;

/**
 * The one conversion behind {@link ApprovalRequest#payloadAs(Class)} and
 * {@link ApprovalDecision.Modified#newPayloadAs(Class)}.
 *
 * <p>A gate that keeps the payload in memory hands back the caller's own object; a gate that
 * persists it hands back the JSON it stored, as a {@code String}, because the type it was
 * written from is not recorded. A JSON string is therefore parsed before it is converted, unless
 * a {@code String} is what the caller asked for.
 */
final class PayloadConversion {

    private static final ObjectMapper JSON = new ObjectMapper();

    private PayloadConversion() {}

    static <T> T as(Object payload, Class<T> type) {
        Objects.requireNonNull(type, "type cannot be null");
        if (payload instanceof String json && !type.isInstance(json)) {
            Object tree;
            try {
                tree = JSON.readValue(json, Object.class);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException(
                        "Cannot read the approval payload as " + type.getName() + ": it is a String"
                                + " that is not JSON. A persistent gate returns the payload as the"
                                + " JSON it stored; a String payload is stored as it is.", e);
            }
            return Message.convertContent(tree, type);
        }
        return Message.convertContent(payload, type);
    }
}
