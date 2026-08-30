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
