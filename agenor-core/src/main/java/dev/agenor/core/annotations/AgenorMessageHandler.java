package dev.agenor.core.annotations;

import dev.agenor.core.messaging.MessageDispatcher;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a message handler that is automatically subscribed to the
 * specified topic when the agent starts.
 *
 * <p>The annotated method must be {@code public} and accept a single
 * {@link dev.agenor.core.Message} parameter. The runtime registers the method as a
 * subscriber on the agent's {@link MessageDispatcher} using the provided topic.
 *
 * <h2>Handlers that are not finished when they return</h2>
 *
 * <p>Return {@code void} when the work is done by the time the method returns. When it is not
 * — an LLM call, a database write, anything whose result arrives later — return the
 * {@link java.util.concurrent.CompletableFuture} instead of starting it and returning
 * {@code void}. The framework waits on what you return, and that is what puts the work inside
 * the delivery guarantees: the message is acknowledged when your future completes, a failure
 * in the chain reaches the transport rather than vanishing, and the work counts against the
 * mailbox's limit on concurrent handlers.
 *
 * <pre>{@code
 * // Inside the guarantees: the framework waits for the call.
 * @AgenorMessageHandler("research.request")
 * public CompletableFuture<Void> onRequest(Message message) {
 *     return llm.chat(request(message)).thenAccept(this::publishFindings);
 * }
 *
 * // Outside them: the method returns at once, the message is acknowledged at once,
 * // and a later failure in the chain is invisible to the framework.
 * @AgenorMessageHandler("research.request")
 * public void onRequest(Message message) {
 *     llm.chat(request(message)).thenAccept(this::publishFindings);
 * }
 * }</pre>
 *
 * <p>Any other return type is rejected when the agent is registered, with the same warning a
 * wrong parameter list gets. {@code CompletableFuture<Void>} was rejected too before 0.30.0,
 * which meant an asynchronous handler could not be written at all.
 *
 * <p>The topic is matched <b>exactly</b>. Both delivery paths resolve a handler by map
 * lookup on {@link dev.agenor.core.Message#topic()} — the topic path in the dispatcher and
 * the direct path in {@code BaseAgent} — so a value containing {@code *} or {@code #} matches
 * nothing and is rejected when the agent is registered. For pattern matching, subscribe
 * programmatically with a {@link dev.agenor.core.filter.MessageFilter}; {@code TopicFilter}
 * in {@code agenor-runtime-ext} builds one from a wildcard with {@code TopicFilter.wildcard()}.
 *
 * <p>Example:
 * <pre>{@code
 * @Agent("inventory-agent")
 * public class InventoryAgent extends BaseAgent {
 *
 *     @AgenorMessageHandler("orders.new")
 *     public void onNewOrder(Message message) {
 *         var order = message.getContentAs(Order.class);
 *         reserveStock(order);
 *     }
 *
 *     @AgenorMessageHandler(value = "inventory.low-stock", autoSubscribe = false)
 *     public void onLowStock(Message message) {
 *         // subscribed manually when needed
 *     }
 * }
 * }</pre>
 *
 * @since 0.1.0
 * @see MessageDispatcher
 * @see dev.agenor.core.Message
 * @see Agent
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AgenorMessageHandler {

    /**
     * The exact topic this handler subscribes to.
     *
     * <p>Compared for equality with the incoming {@link dev.agenor.core.Message#topic()}.
     * No wildcard or pattern syntax is supported; see the type documentation for the
     * filter-based alternative.
     *
     * @return the topic, must not be empty and must not contain {@code *} or {@code #}
     */
    String value();

    /**
     * Whether the runtime should subscribe this handler automatically when the agent starts.
     *
     * <p>Set to {@code false} to delay subscription; the handler can then be registered
     * manually at an appropriate point in the agent's lifecycle.
     *
     * @return {@code true} to subscribe automatically (default), {@code false} for manual subscription
     */
    boolean autoSubscribe() default true;
}
