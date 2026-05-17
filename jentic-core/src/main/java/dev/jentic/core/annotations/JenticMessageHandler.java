package dev.jentic.core.annotations;

import dev.jentic.core.messaging.MessageDispatcher;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a message handler that is automatically subscribed to the
 * specified topic when the agent starts.
 *
 * <p>The annotated method must be {@code public} and accept a single
 * {@link dev.jentic.core.Message} parameter. The runtime registers the method as a
 * subscriber on the agent's {@link MessageDispatcher} using the provided
 * topic pattern.
 *
 * <p>Topic patterns support wildcard matching: {@code "orders.*"} matches
 * {@code "orders.new"} and {@code "orders.cancelled"}; {@code "#"} matches any topic.
 *
 * <p>Example:
 * <pre>{@code
 * @JenticAgent("inventory-agent")
 * public class InventoryAgent extends BaseAgent {
 *
 *     @JenticMessageHandler("orders.new")
 *     public void onNewOrder(Message message) {
 *         var order = message.getContentAs(Order.class);
 *         reserveStock(order);
 *     }
 *
 *     @JenticMessageHandler(value = "inventory.*", autoSubscribe = false)
 *     public void onInventoryEvent(Message message) {
 *         // subscribed manually when needed
 *     }
 * }
 * }</pre>
 *
 * @since 0.1.0
 * @see MessageDispatcher
 * @see dev.jentic.core.Message
 * @see JenticAgent
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface JenticMessageHandler {

    /**
     * The topic (or topic pattern) this handler subscribes to.
     *
     * <p>The topic is matched against incoming {@link dev.jentic.core.Message#topic()} values.
     * Wildcard support is implementation-dependent but typically follows MQTT-style
     * patterns ({@code *} single segment, {@code #} multi-segment).
     *
     * @return the topic or topic pattern, must not be empty
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