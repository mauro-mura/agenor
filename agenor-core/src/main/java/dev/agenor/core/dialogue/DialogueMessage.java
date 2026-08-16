package dev.agenor.core.dialogue;

import dev.agenor.core.Message;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A dialogue-aware wrapper around {@link Message}.
 * Adds conversation tracking and performative semantics.
 *
 * @param id             unique message identifier (UUID)
 * @param conversationId identifier grouping messages in the same dialogue session
 * @param senderId       agent ID of the message sender; must not be null
 * @param receiverId     agent ID of the intended recipient; may be null for broadcast
 * @param performative   FIPA-inspired speech act declaring the communicative intent; must not be null
 * @param content        message payload; type depends on the ontology in use
 * @param protocol       optional dialogue protocol name (e.g. {@code "fipa-request"})
 * @param inReplyTo      ID of the message this is a reply to; null if not a reply
 * @param timestamp      instant at which this message was created; must not be null
 * @param metadata       immutable map of additional header-style data; empty if null
 * @since 0.5.0
 */
public record DialogueMessage(
    String id,
    String conversationId,
    String senderId,
    String receiverId,
    Performative performative,
    Object content,
    String protocol,
    String inReplyTo,
    Instant timestamp,
    Map<String, Object> metadata
) {

    /**
     * Header carrying the payload's Java type, written by {@link #toMessage()} when the content
     * is non-null.
     *
     * <p>Deliberately <strong>not</strong> {@code content-type}: that key already means a domain
     * label in ADR-005's message example and an HTTP-style media type in the message-filtering
     * guide, and a Java class name is neither.
     *
     * <p>Informational only. {@link #contentAs(Class)} converts to the type the caller asks for
     * and never reads this header to decide what to build — a peer written in another language
     * neither writes nor understands it, and honouring it would make the wire format
     * Java-specific. It is used for diagnostics and lets a Java peer route on payload type
     * without deserialising. See ADR-030.
     *
     * @since 0.26.0
     */
    public static final String CONTENT_CLASS_HEADER = "content-class";

    public DialogueMessage {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(conversationId, "conversationId cannot be null");
        Objects.requireNonNull(senderId, "senderId cannot be null");
        Objects.requireNonNull(performative, "performative cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    /**
     * Decides whether a {@link Message} is part of a dialogue exchange.
     *
     * <p>A message qualifies when its {@code performative} header is present <em>and</em> names
     * a known {@link Performative}. The header is what declares communicative intent;
     * {@code conversationId} is deliberately not required, because a peer's first message may
     * legitimately omit it.
     *
     * <p>This is the classifier; {@link #fromMessage(Message)} is the converter. Converting
     * without classifying first is the caller asserting it already knows what it holds — which
     * is correct when awaiting the reply to a dialogue it started itself. Components that route
     * arbitrary inbound traffic should classify first: a message that is not dialogue must not
     * be turned into a synthetic {@code INFORM} in a conversation nobody started.
     *
     * <p>A message whose {@code performative} header holds an unrecognised value is
     * <strong>not</strong> a dialogue message: it comes from a peer speaking a dialect this
     * runtime does not know, and executing it as {@code INFORM} would run something its sender
     * never said. See ADR-029.
     *
     * @param message the message to classify; must not be null
     * @return true if the message carries a known performative
     * @since 0.26.0
     */
    public static boolean isDialogueMessage(Message message) {
        Objects.requireNonNull(message, "message cannot be null");
        return parsePerformative(message).isPresent();
    }

    /**
     * Creates a new DialogueMessage from an existing Message.
     *
     * <p>Lenient by design: a missing or unrecognised {@code performative} header becomes
     * {@link Performative#INFORM} and a missing {@code conversationId} is generated. Use
     * {@link #isDialogueMessage(Message)} first when the message came from a channel that also
     * carries non-dialogue traffic.
     */
    public static DialogueMessage fromMessage(Message message) {
        Objects.requireNonNull(message, "message cannot be null");

        return new DialogueMessage(
            message.id(),
            extractConversationId(message),
            message.senderId(),
            message.receiverId(),
            extractPerformative(message),
            message.content(),
            extractProtocol(message),
            message.correlationId(),
            message.timestamp(),
            toObjectMap(message.headers())
        );
    }

    /**
     * Converts this DialogueMessage to a Message.
     *
     * <p>Writes the dialogue headers ({@code conversationId}, {@code performative}, and
     * {@code protocol} when set) plus {@link #CONTENT_CLASS_HEADER} when the content is
     * non-null.
     */
    public Message toMessage() {
        return Message.builder()
            .id(id)
            .senderId(senderId)
            .receiverId(receiverId)
            .correlationId(inReplyTo)
            .content(content)
            .timestamp(timestamp)
            .headers(buildHeaders())
            .build();
    }

    /**
     * Reads {@link #content()} as the requested type, converting it if necessary.
     *
     * <p>This is the accessor a {@code @DialogueHandler} should use. Casting {@code content()}
     * directly works only while the dialogue stays inside one JVM: over a serialising transport
     * such as Redis (ADR-021) the payload arrives as a {@code Map} and the cast throws — a
     * failure that no in-memory test can reproduce, which is why the cast is a bug even when it
     * currently works.
     *
     * <pre>{@code
     * // breaks the first time this dialogue crosses a runtime boundary
     * Bid bid = (Bid) proposal.content();
     *
     * // works on every transport
     * Bid bid = proposal.contentAs(Bid.class);
     * }</pre>
     *
     * <p>Content already of the requested type is returned as the same reference; content
     * rebuilt from JSON is converted. Delegates to {@link Message#convertContent(Object, Class)}
     * so both accessors share one implementation, and adds the sender's declared type from
     * {@link #CONTENT_CLASS_HEADER} to the failure message when one is available.
     *
     * @param <T> the expected type of the content
     * @param type the class of the expected type; must not be null
     * @return the content as type T, or null if the content is null
     * @throws IllegalArgumentException if the content is neither an instance of {@code type} nor
     *         convertible to it
     * @since 0.26.0
     */
    public <T> T contentAs(Class<T> type) {
        try {
            return Message.convertContent(content, type);
        } catch (IllegalArgumentException e) {
            var declared = metadata.get(CONTENT_CLASS_HEADER);
            if (declared == null) {
                throw e;
            }
            throw new IllegalArgumentException(
                e.getMessage() + " The sender declared it as " + declared + " ("
                    + CONTENT_CLASS_HEADER + " header).", e);
        }
    }

    /**
     * Creates a reply to this message.
     */
    public DialogueMessage reply(Performative replyPerformative, Object replyContent, String replySenderId) {
        return new DialogueMessage(
            UUID.randomUUID().toString(),
            this.conversationId,
            replySenderId,
            this.senderId,
            replyPerformative,
            replyContent,
            this.protocol,
            this.id,
            Instant.now(),
            Map.of()
        );
    }

    /**
     * @return true if this message expects a response
     */
    public boolean expectsResponse() {
        return performative.expectsResponse();
    }

    /**
     * @return true if this message is a reply to another message
     */
    public boolean isReply() {
        return inReplyTo != null;
    }

    /**
     * @return the protocol if present
     */
    public Optional<String> getProtocol() {
        return Optional.ofNullable(protocol);
    }

    // Builder for creating new messages
    public static Builder builder() {
        return new Builder();
    }

    private Map<String, String> buildHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("conversationId", conversationId);
        headers.put("performative", performative.name());
        if (protocol != null) headers.put("protocol", protocol);
        if (content != null) headers.put(CONTENT_CLASS_HEADER, content.getClass().getName());
        return headers;
    }

    private static Map<String, Object> toObjectMap(Map<String, String> headers) {
        Map<String, Object> result = new HashMap<>();
        headers.forEach(result::put);
        return result;
    }

    private static String extractConversationId(Message message) {
        String convId = message.headers().get("conversationId");
        return convId != null ? convId : UUID.randomUUID().toString();
    }

    private static Performative extractPerformative(Message message) {
        return parsePerformative(message).orElse(Performative.INFORM);
    }

    /**
     * Reads the {@code performative} header, if it is present and names a known performative.
     *
     * <p>Single source of truth for both {@link #isDialogueMessage(Message)} and
     * {@link #extractPerformative(Message)}: if the two parsed independently, a header the
     * classifier rejected could still be converted (or vice versa) — for instance a lower-case
     * {@code "inform"}.
     */
    private static Optional<Performative> parsePerformative(Message message) {
        String perf = message.headers().get("performative");
        if (perf == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Performative.valueOf(perf.toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String extractProtocol(Message message) {
        String proto = message.headers().get("protocol");
        return proto != null && !proto.isEmpty() ? proto : null;
    }

    public static class Builder {
        private String id;
        private String conversationId;
        private String senderId;
        private String receiverId;
        private Performative performative;
        private Object content;
        private String protocol;
        private String inReplyTo;
        private Instant timestamp;
        private Map<String, Object> metadata = Map.of();

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder conversationId(String conversationId) {
            this.conversationId = conversationId;
            return this;
        }

        public Builder senderId(String senderId) {
            this.senderId = senderId;
            return this;
        }

        public Builder receiverId(String receiverId) {
            this.receiverId = receiverId;
            return this;
        }

        public Builder performative(Performative performative) {
            this.performative = performative;
            return this;
        }

        public Builder content(Object content) {
            this.content = content;
            return this;
        }

        public Builder protocol(String protocol) {
            this.protocol = protocol;
            return this;
        }

        public Builder inReplyTo(String inReplyTo) {
            this.inReplyTo = inReplyTo;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public DialogueMessage build() {
            if (id == null) {
                id = UUID.randomUUID().toString();
            }
            if (conversationId == null) {
                conversationId = UUID.randomUUID().toString();
            }
            if (timestamp == null) {
                timestamp = Instant.now();
            }
            return new DialogueMessage(
                id, conversationId, senderId, receiverId,
                performative, content, protocol, inReplyTo,
                timestamp, metadata
            );
        }
    }
}
