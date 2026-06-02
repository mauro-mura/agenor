package dev.agenor.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.agenor.core.messaging.MessageDispatcher;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable message object for agent communication in the Jentic framework.
 *
 * <p>Messages are the fundamental unit of communication between agents. They
 * encapsulate content along with routing, correlation, and metadata information.
 * The {@code Message} record provides a type-safe, immutable container for
 * inter-agent communication with support for both publish-subscribe and
 * point-to-point messaging patterns.
 *
 * <p><strong>Key Features:</strong>
 * <ul>
 *   <li><strong>Immutability</strong> - Thread-safe, can be safely shared across agents</li>
 *   <li><strong>Builder Pattern</strong> - Fluent API for message construction</li>
 *   <li><strong>JSON Serialization</strong> - Native Jackson support for serialization</li>
 *   <li><strong>Correlation Support</strong> - Built-in request-response pattern</li>
 *   <li><strong>Flexible Content</strong> - Type-safe content retrieval</li>
 *   <li><strong>Headers</strong> - Extensible metadata for custom information</li>
 * </ul>
 *
 * <p><strong>Message Components:</strong>
 * <table border="1">
 *   <caption>Message core components and properties</caption>
 *   <tr>
 *     <th>Component</th>
 *     <th>Purpose</th>
 *     <th>Required</th>
 *   </tr>
 *   <tr>
 *     <td>id</td>
 *     <td>Unique message identifier</td>
 *     <td>Auto-generated if not provided</td>
 *   </tr>
 *   <tr>
 *     <td>topic</td>
 *     <td>Publish-subscribe routing</td>
 *     <td>Optional for direct messages</td>
 *   </tr>
 *   <tr>
 *     <td>senderId</td>
 *     <td>Source agent identification</td>
 *     <td>Optional but recommended</td>
 *   </tr>
 *   <tr>
 *     <td>receiverId</td>
 *     <td>Point-to-point routing</td>
 *     <td>Optional for broadcast</td>
 *   </tr>
 *   <tr>
 *     <td>correlationId</td>
 *     <td>Request-response correlation</td>
 *     <td>Optional, for replies</td>
 *   </tr>
 *   <tr>
 *     <td>content</td>
 *     <td>Message payload</td>
 *     <td>Optional</td>
 *   </tr>
 *   <tr>
 *     <td>headers</td>
 *     <td>Custom metadata</td>
 *     <td>Optional</td>
 *   </tr>
 *   <tr>
 *     <td>timestamp</td>
 *     <td>Creation time</td>
 *     <td>Auto-generated if not provided</td>
 *   </tr>
 * </table>
 *
 * <p><strong>Messaging Patterns Supported:</strong>
 *
 * <p><strong>1. Publish-Subscribe (Topic-Based):</strong>
 * Multiple agents subscribe to topics and receive all messages published to those topics.
 * <pre>{@code
 * // Publisher
 * Message announcement = Message.builder()
 *     .topic("system.alerts")
 *     .content("System maintenance scheduled")
 *     .build();
 * messageService.send(announcement);
 *
 * // Subscribers
 * messageService.subscribe("system.alerts", message -> {
 *     log.info("Received alert: {}", message.content());
 *     return CompletableFuture.completedFuture(null);
 * });
 * }</pre>
 *
 * <p><strong>2. Point-to-Point (Direct Messaging):</strong>
 * Messages sent directly to a specific agent by ID.
 * <pre>{@code
 * // Send to specific agent
 * Message direct = Message.builder()
 *     .senderId("order-processor")
 *     .receiverId("payment-service")
 *     .content(orderData)
 *     .build();
 * messageService.send(direct);
 *
 * // Receiver subscribes to their ID
 * messageService.subscribeToReceiver("payment-service", message -> {
 *     processPayment(message.getContent(OrderData.class));
 *     return CompletableFuture.completedFuture(null);
 * });
 * }</pre>
 *
 * <p><strong>3. Request-Response (Correlation-Based):</strong>
 * Synchronous-style communication using correlation IDs to match requests and responses.
 * <pre>{@code
 * // Requester
 * Message request = Message.builder()
 *     .senderId("client")
 *     .receiverId("server")
 *     .content("query data")
 *     .build();
 *
 * CompletableFuture<Message> responseFuture =
 *     messageService.sendAndWait(request, 5000);
 *
 * responseFuture.thenAccept(response -> {
 *     log.info("Response: {}", response.content());
 * });
 *
 * // Responder
 * messageService.subscribeToReceiver("server", request -> {
 *     Object result = processQuery(request.content());
 *
 *     // Reply using correlation
 *     Message response = request.reply(result)
 *         .senderId("server")
 *         .build();
 *
 *     return messageService.send(response);
 * });
 * }</pre>
 *
 * <p><strong>Headers Usage:</strong>
 * Headers provide a flexible mechanism for metadata without modifying the content:
 * <pre>{@code
 * Message message = Message.builder()
 *     .topic("data.events")
 *     .content(data)
 *     .header("priority", "HIGH")
 *     .header("source", "sensor-01")
 *     .header("timestamp-utc", Instant.now().toString())
 *     .header("retry-count", "0")
 *     .build();
 *
 * // Filter by headers
 * messageService.subscribe(
 *     msg -> "HIGH".equals(msg.headers().get("priority")),
 *     this::handleHighPriority
 * );
 * }</pre>
 *
 * <p><strong>Content Type Safety:</strong>
 * The {@link #getContent(Class)} method provides type-safe content retrieval:
 * <pre>{@code
 * // Sending typed content
 * OrderData order = new OrderData(...);
 * Message message = Message.builder()
 *     .content(order)
 *     .build();
 *
 * // Receiving with type safety
 * messageService.subscribe("orders", msg -> {
 *     OrderData receivedOrder = msg.getContent(OrderData.class);
 *     processOrder(receivedOrder);
 *     return CompletableFuture.completedFuture(null);
 * });
 * }</pre>
 *
 * <p><strong>Thread Safety:</strong>
 * Messages are immutable and thread-safe. They can be safely:
 * <ul>
 *   <li>Shared across multiple agent threads</li>
 *   <li>Passed through message queues</li>
 *   <li>Stored for audit or replay</li>
 *   <li>Serialized and transmitted over network</li>
 * </ul>
 *
 * <p><strong>Serialization:</strong>
 * Messages are fully JSON-serializable using Jackson annotations. This enables:
 * <ul>
 *   <li>Persistence to databases or files</li>
 *   <li>Network transmission in distributed systems</li>
 *   <li>Logging and debugging</li>
 *   <li>Message replay and testing</li>
 * </ul>
 *
 * <p><strong>Best Practices:</strong>
 * <ul>
 *   <li>Always set {@code senderId} for traceability</li>
 *   <li>Use meaningful topics for pub-sub patterns</li>
 *   <li>Set {@code receiverId} for point-to-point messages</li>
 *   <li>Use {@code correlationId} for request-response patterns</li>
 *   <li>Keep content serializable for distributed scenarios</li>
 *   <li>Use headers for cross-cutting concerns (priority, tracing, etc.)</li>
 *   <li>Don't store large objects in content; use references instead</li>
 * </ul>
 *
 * @param id unique message identifier, auto-generated if null
 * @param topic the topic for publish-subscribe routing, may be null
 * @param senderId the identifier of the sending agent, may be null
 * @param receiverId the identifier of the receiving agent for point-to-point, may be null
 * @param correlationId identifier linking this message to a previous message (for replies), may be null
 * @param content the message payload, may be any serializable object or null
 * @param headers custom metadata as key-value pairs, never null (empty map if not provided)
 * @param timestamp when the message was created, auto-generated if null
 *
 * @since 0.1.0
 * @see MessageDispatcher
 * @see MessageHandler
 */
public record Message(
        @JsonProperty("id") String id,
        @JsonProperty("topic") String topic,
        @JsonProperty("senderId") String senderId,
        @JsonProperty("receiverId") String receiverId,
        @JsonProperty("correlationId") String correlationId,
        @JsonProperty("content") Object content,
        @JsonProperty("headers") Map<String, String> headers,
        @JsonProperty("timestamp") Instant timestamp
) {

    /**
     * Canonical constructor with default value generation and defensive copying.
     *
     * <p>This constructor ensures that:
     * <ul>
     *   <li>Missing {@code id} is auto-generated as UUID</li>
     *   <li>Missing {@code timestamp} is set to current time</li>
     *   <li>{@code headers} map is defensively copied for immutability</li>
     *   <li>Empty headers map is created if null</li>
     * </ul>
     *
     * <p><strong>Note:</strong> Jackson primarily uses this constructor
     * during deserialization. For message creation, use the {@link #builder()}
     * pattern instead.
     *
     * @param id message identifier, generated if null
     * @param topic topic for routing
     * @param senderId sender agent identifier
     * @param receiverId receiver agent identifier
     * @param correlationId correlation identifier for request-response
     * @param content message payload
     * @param headers metadata map, defensively copied
     * @param timestamp creation timestamp, set to now if null
     */
    @JsonCreator
    public Message(
            @JsonProperty("id") String id,
            @JsonProperty("topic") String topic,
            @JsonProperty("senderId") String senderId,
            @JsonProperty("receiverId") String receiverId,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("content") Object content,
            @JsonProperty("headers") Map<String, String> headers,
            @JsonProperty("timestamp") Instant timestamp
    ) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.topic = topic;
        this.senderId = senderId;
        this.receiverId = receiverId;
        this.correlationId = correlationId;
        this.content = content;
        this.headers = headers != null ? Map.copyOf(headers) : Map.of();
        this.timestamp = timestamp != null ? timestamp : Instant.now();
    }

    /**
     * Retrieves the message content with automatic type casting.
     *
     * <p>This is a convenience method that casts the {@code content} object to
     * the specified type. It's more readable than manual casting and provides
     * a clear API for type-safe content retrieval.
     *
     * <p><strong>Type Safety:</strong>
     * This method performs an unchecked cast. Ensure the type parameter matches
     * the actual type of the content, or a {@code ClassCastException} will be
     * thrown when the returned value is used.
     *
     * <p><strong>Null Handling:</strong>
     * Returns {@code null} if the content is {@code null}. Always check for
     * null before using the returned value, or use {@code Optional.ofNullable()}.
     *
     * <p><strong>Examples:</strong>
     *
     * <p>Simple content types:
     * <pre>{@code
     * String text = message.getContent(String.class);
     * Integer number = message.getContent(Integer.class);
     * Boolean flag = message.getContent(Boolean.class);
     * }</pre>
     *
     * <p>Complex domain objects:
     * <pre>{@code
     * OrderData order = message.getContent(OrderData.class);
     * PaymentRequest payment = message.getContent(PaymentRequest.class);
     * }</pre>
     *
     * <p>Collections (requires care with generics):
     * <pre>{@code
     * @SuppressWarnings("unchecked")
     * List<String> items = message.getContent(List.class);
     *
     * @SuppressWarnings("unchecked")
     * Map<String, Object> data = message.getContent(Map.class);
     * }</pre>
     *
     * <p>Safe usage with null check:
     * <pre>{@code
     * OrderData order = message.getContent(OrderData.class);
     * if (order != null) {
     *     processOrder(order);
     * } else {
     *     log.warn("Message {} has null content", message.id());
     * }
     * }</pre>
     *
     * @param <T> the expected type of the content
     * @param type the class of the expected type (for clarity, not type checking)
     * @return the content cast to type T, or null if content is null
     * @throws ClassCastException if the content cannot be cast to type T
     */
    @SuppressWarnings("unchecked")
    public <T> T getContent(Class<T> type) {
        return (T) content;
    }

    /**
     * Creates a new builder for constructing messages fluently.
     *
     * <p>The builder pattern is the recommended way to create messages. It provides
     * a fluent, readable API that makes message construction clear and maintainable.
     *
     * <p><strong>Examples:</strong>
     *
     * <p>Minimal message:
     * <pre>{@code
     * Message simple = Message.builder()
     *     .content("Hello")
     *     .build();
     * }</pre>
     *
     * <p>Topic-based broadcast:
     * <pre>{@code
     * Message broadcast = Message.builder()
     *     .topic("system.events")
     *     .senderId("monitor-agent")
     *     .content(event)
     *     .build();
     * }</pre>
     *
     * <p>Direct message:
     * <pre>{@code
     * Message direct = Message.builder()
     *     .senderId("client")
     *     .receiverId("server")
     *     .content(request)
     *     .build();
     * }</pre>
     *
     * <p>With headers:
     * <pre>{@code
     * Message enriched = Message.builder()
     *     .topic("orders")
     *     .content(order)
     *     .header("priority", "HIGH")
     *     .header("region", "US-WEST")
     *     .build();
     * }</pre>
     *
     * @return a new MessageBuilder instance
     * @see MessageBuilder
     */
    public static MessageBuilder builder() {
        return new MessageBuilder();
    }

    /**
     * Creates a reply message builder with correlation ID set automatically.
     *
     * <p>This convenience method simplifies the request-response pattern by
     * automatically setting the correlation ID to this message's ID and
     * setting the receiver to this message's sender.
     *
     * <p><strong>Usage Pattern:</strong>
     * <ol>
     *   <li>Receive a request message</li>
     *   <li>Process the request</li>
     *   <li>Use {@code reply()} to create response</li>
     *   <li>Send the response</li>
     * </ol>
     *
     * <p><strong>Examples:</strong>
     *
     * <p>Simple reply:
     * <pre>{@code
     * messageService.subscribeToReceiver("server", request -> {
     *     String result = processRequest(request.content());
     *
     *     Message response = request.reply(result)
     *         .senderId("server")
     *         .build();
     *
     *     return messageService.send(response);
     * });
     * }</pre>
     *
     * <p>Reply with additional metadata:
     * <pre>{@code
     * Message response = request.reply(result)
     *     .senderId("processor")
     *     .topic("responses")
     *     .header("processing-time-ms", String.valueOf(elapsed))
     *     .header("status", "SUCCESS")
     *     .build();
     * }</pre>
     *
     * <p>Error reply:
     * <pre>{@code
     * Message errorResponse = request.reply(null)
     *     .senderId("validator")
     *     .header("error", "VALIDATION_FAILED")
     *     .header("error-message", errorMessage)
     *     .build();
     * }</pre>
     *
     * <p><strong>Automatic Fields:</strong>
     * The returned builder has:
     * <ul>
     *   <li>{@code correlationId} set to this message's {@code id}</li>
     *   <li>{@code receiverId} set to this message's {@code senderId}</li>
     *   <li>{@code content} set to the provided reply content</li>
     * </ul>
     *
     * <p><strong>Example:</strong>
     * <pre>{@code
     * // Responder — inside a subscribeRecipient handler
     * dispatcher.subscribeRecipient("server", req -> {
     *     Message reply = req.reply(processQuery(req.content()))
     *         .senderId("server")
     *         .build();
     *     return dispatcher.sendTo(reply);
     * });
     * }</pre>
     *
     * @param content the reply content (payload)
     * @return a MessageBuilder with correlationId and receiverId pre-set
     * @see MessageDispatcher
     */
    public MessageBuilder reply(Object content) {
        return builder()
                .correlationId(this.id)
                .receiverId(this.senderId)
                .content(content);
    }

    /**
     * Fluent builder for constructing {@link Message} instances.
     *
     * <p>The builder provides a readable, type-safe way to construct messages
     * with only the fields you need. It handles default value generation and
     * ensures the immutability of the final message.
     *
     * <p><strong>Builder Features:</strong>
     * <ul>
     *   <li>Fluent method chaining</li>
     *   <li>Optional fields with sensible defaults</li>
     *   <li>Header accumulation (multiple calls add headers)</li>
     *   <li>Immutable result</li>
     * </ul>
     *
     * <p><strong>Thread Safety:</strong>
     * This builder is NOT thread-safe. Each thread should use its own builder
     * instance.
     */
    public static class MessageBuilder {
        private String id;
        private String topic;
        private String senderId;
        private String receiverId;
        private String correlationId;
        private Object content;
        private Map<String, String> headers = Map.of();
        private Instant timestamp;

        /**
         * Sets the message ID.
         *
         * <p>If not set, a random UUID will be generated automatically.
         *
         * @param id the message identifier
         * @return this builder for method chaining
         */
        public MessageBuilder id(String id) {
            this.id = id;
            return this;
        }

        /**
         * Sets the topic for publish-subscribe routing.
         *
         * <p>Topics enable one-to-many communication where multiple agents
         * can subscribe to receive messages.
         *
         * @param topic the topic name (e.g., "orders.created", "system.alerts")
         * @return this builder for method chaining
         */
        public MessageBuilder topic(String topic) {
            this.topic = topic;
            return this;
        }

        /**
         * Sets the sender agent identifier.
         *
         * <p>Always set this field for traceability and to enable reply
         * functionality.
         *
         * @param senderId the sending agent's ID
         * @return this builder for method chaining
         */
        public MessageBuilder senderId(String senderId) {
            this.senderId = senderId;
            return this;
        }

        /**
         * Sets the receiver agent identifier for point-to-point messaging.
         *
         * <p>When set, the message will be delivered directly to the specified
         * agent, in addition to any topic-based routing.
         *
         * @param receiverId the receiving agent's ID
         * @return this builder for method chaining
         */
        public MessageBuilder receiverId(String receiverId) {
            this.receiverId = receiverId;
            return this;
        }

        /**
         * Sets the correlation ID for request-response patterns.
         *
         * <p>The correlation ID links a response message to its originating
         * request. Typically set to the ID of the request message.
         *
         * @param correlationId the correlation identifier
         * @return this builder for method chaining
         */
        public MessageBuilder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        /**
         * Sets the message content (payload).
         *
         * <p>The content can be any object. For distributed scenarios, ensure
         * the content is serializable.
         *
         * @param content the message payload
         * @return this builder for method chaining
         */
        public MessageBuilder content(Object content) {
            this.content = content;
            return this;
        }

        /**
         * Adds multiple headers at once, merging with existing headers.
         *
         * <p>If a header key already exists, it will be replaced with the new value.
         *
         * @param headers map of headers to add
         * @return this builder for method chaining
         */
        public MessageBuilder headers(Map<String, String> headers) {
            if (headers != null) {
                var allHeaders = new java.util.HashMap<>(this.headers);
                allHeaders.putAll(headers);
                this.headers = Map.copyOf(allHeaders);
            }
            return this;
        }

        /**
         * Adds a single header, preserving existing headers.
         *
         * <p>This method can be called multiple times to add multiple headers.
         *
         * @param key the header key
         * @param value the header value
         * @return this builder for method chaining
         */
        public MessageBuilder header(String key, String value) {
            if (key != null && value != null) {
                var newHeaders = new java.util.HashMap<>(this.headers);
                newHeaders.put(key, value);
                this.headers = Map.copyOf(newHeaders);
            }
            return this;
        }

        /**
         * Sets the message timestamp.
         *
         * <p>If not set, the timestamp will be set to the current time when
         * {@link #build()} is called.
         *
         * @param timestamp the message creation timestamp
         * @return this builder for method chaining
         */
        public MessageBuilder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        /**
         * Builds an immutable Message with the accumulated values.
         *
         * <p>This method can be called multiple times to create multiple
         * messages with the same configuration.
         *
         * @return a new immutable Message instance
         */
        public Message build() {
            return new Message(id, topic, senderId, receiverId, correlationId,
                    content, headers, timestamp);
        }
    }
}
