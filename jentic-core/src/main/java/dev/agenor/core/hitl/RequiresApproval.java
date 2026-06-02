package dev.agenor.core.hitl;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an agent behavior as requiring human approval before execution.
 *
 * <p>{@code AgenorRuntime} reads this annotation at agent registration time,
 * parses the {@link #timeout()} string, instantiates the {@link #notifier()} class
 * via its no-arg constructor, and wraps the behavior in a
 * {@code HumanCheckpointBehavior} with the singleton {@code InMemoryApprovalGate}.
 *
 * <p>Notifiers requiring constructor parameters (e.g. a webhook URL or auth token)
 * must be wired programmatically via {@code HumanCheckpointBehavior} instead.
 *
 * <p>Example:
 * <pre>{@code
 * @RequiresApproval(timeout = "30m", notifier = WebhookApprovalNotifier.class)
 * public class PaymentBehavior extends AgentBehavior {
 *     // ...
 * }
 * }</pre>
 *
 * <p><b>Timeout format</b></p>
 * The {@link #timeout()} string is parsed as a simple duration:
 * <ul>
 *   <li>{@code "30s"} — 30 seconds</li>
 *   <li>{@code "10m"} — 10 minutes</li>
 *   <li>{@code "2h"}  — 2 hours</li>
 * </ul>
 *
 * @see ApprovalGate
 * @see ApprovalNotifier
 * @since 0.13.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RequiresApproval {

    /**
     * Approval timeout as a simple duration string: {@code "30s"}, {@code "10m"},
     * {@code "2h"}. Defaults to 30 minutes.
     */
    String timeout() default "30m";

    /**
     * {@link ApprovalNotifier} implementation to use for this behavior.
     * Must expose a public no-arg constructor.
     *
     * <p>Defaults to {@link DefaultApprovalNotifier}, a sentinel that the runtime
     * wiring replaces with {@code LoggingApprovalNotifier} at agent registration
     * time, avoiding a compile-time dependency from {@code agenor-core} on
     * {@code agenor-runtime}.
     */
    Class<? extends ApprovalNotifier> notifier() default DefaultApprovalNotifier.class;
}
