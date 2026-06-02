package dev.agenor.core.dialogue;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a handler for dialogue messages.
 *
 * <p>Methods annotated with {@code @DialogueHandler} will be automatically
 * invoked when matching dialogue messages are received.
 *
 * <p>Example:
 * <pre>{@code
 * @DialogueHandler(performatives = {REQUEST, QUERY})
 * public void handleIncomingRequest(DialogueMessage message) {
 *     // Handle the message
 * }
 *
 * @DialogueHandler(protocol = "contract-net", performatives = {PROPOSE})
 * public void handleProposal(DialogueMessage message) {
 *     // Handle CFP proposal
 * }
 * }</pre>
 *
 * @since 0.5.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DialogueHandler {

    /**
     * The performatives this handler accepts.
     * If empty, accepts all performatives.
     *
     * @return array of accepted performatives
     */
    Performative[] performatives() default {};

    /**
     * The protocol this handler is associated with.
     * If empty, accepts messages from any protocol.
     *
     * @return the protocol ID
     */
    String protocol() default "";

    /**
     * Handler priority (higher = processed first).
     *
     * @return the priority value
     */
    int priority() default 0;
}
