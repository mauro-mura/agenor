package dev.agenor.core;

import dev.agenor.core.messaging.MessageDispatcher;
import java.util.concurrent.CompletableFuture;

/**
 * Functional interface for handling received messages in the Agenor framework.
 *
 * <p>A {@code MessageHandler} is a callback that processes incoming messages.
 * Handlers are registered with the {@link MessageDispatcher} to receive messages
 * matching specific criteria (topics, receivers, or predicates).
 *
 * <p><strong>Functional Interface:</strong>
 * Being a {@code @FunctionalInterface}, handlers can be created using:
 * <ul>
 *   <li>Lambda expressions</li>
 *   <li>Method references</li>
 *   <li>Anonymous classes</li>
 * </ul>
 *
 * <p><strong>Asynchronous by Default:</strong>
 * The {@link #handle(Message)} method returns a {@code CompletableFuture},
 * enabling non-blocking, asynchronous message processing. This is crucial for:
 * <ul>
 *   <li>Avoiding blocking the message service thread</li>
 *   <li>Processing multiple messages concurrently</li>
 *   <li>Chaining message processing operations</li>
 *   <li>Error handling and recovery</li>
 * </ul>
 *
 * <p><strong>Example - Lambda Handler:</strong>
 * <pre>{@code
 * // Simple lambda handler
 * MessageHandler handler = message -> {
 *     log.info("Received: {}", message.content());
 *     return CompletableFuture.completedFuture(null);
 * };
 *
 * // Subscribe
 * messageService.subscribe("notifications", handler);
 * }</pre>
 *
 * <p><strong>Example - Method Reference:</strong>
 * <pre>{@code
 * public class OrderProcessor {
 *
 *     public CompletableFuture<Void> handleOrder(Message message) {
 *         OrderData order = message.getContent(OrderData.class);
 *         return processOrderAsync(order);
 *     }
 *
 *     public void setupSubscription(MessageService messageService) {
 *         // Method reference as handler
 *         messageService.subscribe("orders", this::handleOrder);
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Example - Async Processing:</strong>
 * <pre>{@code
 * MessageHandler asyncHandler = message -> {
 *     return CompletableFuture.supplyAsync(() -> {
 *         // Long-running operation in separate thread
 *         Data data = message.getContent(Data.class);
 *         Result result = expensiveOperation(data);
 *
 *         // Send response
 *         Message response = message.reply(result)
 *             .senderId("processor")
 *             .build();
 *         messageService.send(response);
 *
 *         return null;
 *     });
 * };
 * }</pre>
 *
 * <p><strong>Example - Synchronous Wrapper:</strong>
 * Use {@link #sync(SyncMessageHandler)} for simple synchronous handlers:
 * <pre>{@code
 * // Synchronous handler wrapped in async
 * MessageHandler handler = MessageHandler.sync(message -> {
 *     log.info("Processing: {}", message.content());
 *     processSync(message);
 *     // No need to return CompletableFuture
 * });
 * }</pre>
 *
 * <p><strong>Error Handling:</strong>
 * Handlers should handle errors gracefully. Unhandled exceptions will:
 * <ul>
 *   <li>Complete the returned future exceptionally</li>
 *   <li>Be logged by the message service</li>
 *   <li>Not affect other handlers or messages</li>
 * </ul>
 *
 * <pre>{@code
 * MessageHandler resilientHandler = message -> {
 *     return CompletableFuture.supplyAsync(() -> {
 *         try {
 *             processMessage(message);
 *             return null;
 *         } catch (ValidationException e) {
 *             log.warn("Invalid message: {}", e.getMessage());
 *             sendErrorReply(message, e);
 *             return null;
 *         } catch (Exception e) {
 *             log.error("Processing failed", e);
 *             // Rethrow to mark future as failed
 *             throw new CompletionException(e);
 *         }
 *     });
 * };
 * }</pre>
 *
 * <p><strong>Best Practices:</strong>
 * <ul>
 *   <li>Keep handlers focused on a single responsibility</li>
 *   <li>Use async processing for I/O or long-running operations</li>
 *   <li>Handle errors explicitly; don't let exceptions propagate silently</li>
 *   <li>Log at appropriate levels for debugging and monitoring</li>
 *   <li>Consider idempotency for retry scenarios</li>
 *   <li>Don't block; return futures for all long operations</li>
 * </ul>
 *
 * @since 0.1.0
 * @see Message
 * @see MessageDispatcher
 */
@FunctionalInterface
public interface MessageHandler {

    /**
     * Handles a received message asynchronously.
     *
     * <p>This method is invoked when a message matching the subscription criteria
     * is delivered. The handler should process the message and return a
     * {@code CompletableFuture} that completes when processing is done.
     *
     * <p><strong>Asynchronous Processing:</strong>
     * The method returns immediately with a future. Actual processing can be:
     * <ul>
     *   <li><strong>Immediate</strong> - Use {@code CompletableFuture.completedFuture(null)}</li>
     *   <li><strong>Async</strong> - Use {@code CompletableFuture.supplyAsync(...)}</li>
     *   <li><strong>Chained</strong> - Return an existing future from async operations</li>
     * </ul>
     *
     * <p><strong>Thread Safety:</strong>
     * This method may be called concurrently from multiple threads for different
     * messages. Implementations must be thread-safe if they access shared state.
     *
     * <p><strong>Error Handling:</strong>
     * <ul>
     *   <li>Handle expected errors within the handler</li>
     *   <li>Let unexpected errors complete the future exceptionally</li>
     *   <li>Don't silently swallow exceptions</li>
     * </ul>
     *
     * <p><strong>Performance:</strong>
     * <ul>
     *   <li>Don't block the calling thread</li>
     *   <li>Use {@code CompletableFuture.runAsync()} for heavy processing</li>
     *   <li>Consider thread pool sizing for concurrent messages</li>
     * </ul>
     *
     * <p><strong>Examples:</strong>
     *
     * <p>Immediate completion:
     * <pre>{@code
     * public CompletableFuture<Void> handle(Message message) {
     *     log.info("Received: {}", message.content());
     *     return CompletableFuture.completedFuture(null);
     * }
     * }</pre>
     *
     * <p>Async processing:
     * <pre>{@code
     * public CompletableFuture<Void> handle(Message message) {
     *     return CompletableFuture.runAsync(() -> {
     *         expensiveOperation(message.content());
     *     });
     * }
     * }</pre>
     *
     * <p>Chained operations:
     * <pre>{@code
     * public CompletableFuture<Void> handle(Message message) {
     *     return fetchDataAsync()
     *         .thenCompose(data -> processAsync(data))
     *         .thenCompose(result -> sendReply(message, result))
     *         .exceptionally(ex -> {
     *             log.error("Handler failed", ex);
     *             return null;
     *         });
     * }
     * }</pre>
     *
     * @param message the received message, never null
     * @return a {@code CompletableFuture} that completes when processing is finished,
     *         or completes exceptionally if processing fails
     * @throws RuntimeException if processing fails (will complete future exceptionally)
     * @see Message
     */
    CompletableFuture<Void> handle(Message message);

    /**
     * Creates an async handler wrapper for synchronous message handlers.
     *
     * <p>This factory method simplifies creating handlers for simple synchronous
     * operations. It wraps a {@link SyncMessageHandler} that processes messages
     * synchronously and returns void, converting it to the async {@code MessageHandler}
     * interface.
     *
     * <p><strong>When to Use:</strong>
     * <ul>
     *   <li>Quick, in-memory processing</li>
     *   <li>Simple logging or routing logic</li>
     *   <li>Operations that complete instantly</li>
     * </ul>
     *
     * <p><strong>When NOT to Use:</strong>
     * <ul>
     *   <li>I/O operations (database, network, files)</li>
     *   <li>CPU-intensive computations</li>
     *   <li>Operations that could block</li>
     * </ul>
     *
     * <p><strong>Error Handling:</strong>
     * Exceptions thrown by the sync handler are caught and returned as a
     * failed future, preventing propagation to the message service.
     *
     * <p><strong>Examples:</strong>
     *
     * <p>Simple logging:
     * <pre>{@code
     * MessageHandler handler = MessageHandler.sync(message -> {
     *     log.info("Received message: {}", message.content());
     * });
     * }</pre>
     *
     * <p>Quick processing:
     * <pre>{@code
     * MessageHandler handler = MessageHandler.sync(message -> {
     *     String data = message.getContent(String.class);
     *     if (data != null) {
     *         cache.put(message.id(), data);
     *     }
     * });
     * }</pre>
     *
     * <p>With error handling:
     * <pre>{@code
     * MessageHandler handler = MessageHandler.sync(message -> {
     *     try {
     *         quickProcess(message);
     *     } catch (Exception e) {
     *         log.error("Processing failed", e);
     *         throw e;  // Becomes failed future
     *     }
     * });
     * }</pre>
     *
     * @param syncHandler the synchronous message handler
     * @return an async MessageHandler wrapping the synchronous handler
     * @throws NullPointerException if syncHandler is null
     * @see SyncMessageHandler
     */
    static MessageHandler sync(SyncMessageHandler syncHandler) {
        return message -> {
            try {
                syncHandler.handle(message);
                return CompletableFuture.completedFuture(null);
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        };
    }

    /**
     * Functional interface for synchronous message handling.
     *
     * <p>Used with {@link MessageHandler#sync(SyncMessageHandler)} to create
     * async handlers from synchronous code.
     *
     * @see MessageHandler#sync(SyncMessageHandler)
     */
    @FunctionalInterface
    interface SyncMessageHandler {
        void handle(Message message) throws Exception;
    }
}
