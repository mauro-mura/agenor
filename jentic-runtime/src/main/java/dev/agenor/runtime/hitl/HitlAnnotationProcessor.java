package dev.agenor.runtime.hitl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.agenor.core.Behavior;
import dev.agenor.core.exceptions.JenticException;
import dev.agenor.core.hitl.ApprovalDecision;
import dev.agenor.core.hitl.ApprovalGate;
import dev.agenor.core.hitl.ApprovalNotifier;
import dev.agenor.core.hitl.DefaultApprovalNotifier;
import dev.agenor.core.hitl.RequiresApproval;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.behavior.BaseBehavior;
import dev.agenor.runtime.behavior.advanced.HumanCheckpointBehavior;

/**
 * Reads the {@link RequiresApproval} annotation from behavior classes registered
 * with a {@link BaseAgent} and wraps each annotated behavior in a
 * {@link HumanCheckpointBehavior} at agent registration time.
 *
 * <p>Called by {@code JenticRuntime.registerAgent()} for every {@link BaseAgent}:
 *
 * <pre>{@code
 * // Inside JenticRuntime.registerAgent():
 * if (agent instanceof BaseAgent baseAgent) {
 *     HitlAnnotationProcessor.process(baseAgent, approvalGate);
 * }
 * }</pre>
 *
 * <p><b>Wiring logic</b></p>
 * <ol>
 *   <li>Iterate over all behaviors currently registered on the agent.</li>
 *   <li>For each behavior whose class carries {@link RequiresApproval}:</li>
 *   <li>Parse {@link RequiresApproval#timeout()} → {@link Duration}.</li>
 *   <li>Instantiate {@link ApprovalNotifier}: if notifier class is the
 *       {@link DefaultApprovalNotifier} sentinel, substitute
 *       {@link LoggingApprovalNotifier}.</li>
 *   <li>Remove the original behavior from the agent.</li>
 *   <li>Add a {@link HumanCheckpointBehavior} wrapping it in its place.</li>
 * </ol>
 *
 * @since 0.13.0
 */
public final class HitlAnnotationProcessor {

    private static final Logger log = LoggerFactory.getLogger(HitlAnnotationProcessor.class);

    private HitlAnnotationProcessor() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Processes all {@link RequiresApproval}-annotated behaviors on {@code agent}
     * and wraps them with {@link HumanCheckpointBehavior}.
     *
     * <p>This method is a no-op if no behavior carries the annotation.
     *
     * @param agent the agent being registered; never {@code null}
     * @param gate  the singleton approval gate; never {@code null}
     * @throws HitlWiringException if a notifier class cannot be instantiated, or
     *                             if the timeout string cannot be parsed
     */
    public static void process(BaseAgent agent, ApprovalGate gate) {
        Objects.requireNonNull(agent, "agent must not be null");
        Objects.requireNonNull(gate, "gate must not be null");

        // Snapshot to avoid ConcurrentModificationException while mutating
        List<Behavior> snapshot = new ArrayList<>(agent.getBehaviors());

        for (Behavior behavior : snapshot) {
            RequiresApproval annotation = behavior.getClass().getAnnotation(RequiresApproval.class);
            if (annotation == null) continue;

            log.debug("Processing @RequiresApproval on behavior {} (agent {})",
                    behavior.getClass().getSimpleName(), agent.getAgentId());

            Duration timeout = parseTimeout(annotation.timeout(), behavior.getClass());
            ApprovalNotifier notifier = instantiateNotifier(annotation.notifier(), behavior.getClass());

            // Build the checkpoint wrapper using the original behavior's action via a delegate
            String checkpointId = behavior.getBehaviorId() + "-checkpoint";
            HumanCheckpointBehavior<Object> checkpoint = new HumanCheckpointBehavior<>(
                    checkpointId,
                    gate,
                    notifier,
                    null,                           // payload: behavior provides its own
                    behavior.getClass().getSimpleName(),
                    timeout,
                    decision -> {
                        if (decision instanceof ApprovalDecision.Approved
                                || decision instanceof ApprovalDecision.Modified) {
                            // removeBehavior() may have stopped the behavior (active=false);
                            // reactivate before executing so BaseBehavior.execute() doesn't no-op.
                            if (behavior instanceof BaseBehavior bb) {
                                bb.activate();
                            }
                            behavior.execute().join();
                        }
                        // Rejected: skip execution silently
                    }
            );

            agent.removeBehavior(behavior.getBehaviorId());
            agent.addBehavior(checkpoint);

            log.info("Wrapped behavior {} with HumanCheckpointBehavior (timeout={}, notifier={})",
                    behavior.getBehaviorId(), timeout, notifier.getClass().getSimpleName());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Parses a timeout string into a {@link Duration}.
     * Supported formats: {@code "30s"}, {@code "10m"}, {@code "2h"}.
     *
     * @throws HitlWiringException if the format is unrecognised
     */
    static Duration parseTimeout(String timeout, Class<?> behaviorClass) {
        if (timeout == null || timeout.isBlank()) {
            throw new HitlWiringException(
                    "Empty timeout on @RequiresApproval in " + behaviorClass.getName(), null);
        }
        try {
            String s = timeout.trim().toLowerCase();
            if (s.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(s.substring(0, s.length() - 1)));
            }
            if (s.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(s.substring(0, s.length() - 1)));
            }
            if (s.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(s.substring(0, s.length() - 1)));
            }
            throw new HitlWiringException(
                    "Unrecognised timeout format '" + timeout + "' on @RequiresApproval in "
                    + behaviorClass.getName() + ". Use '30s', '10m', or '2h'.", null);
        } catch (NumberFormatException e) {
            throw new HitlWiringException(
                    "Invalid number in timeout '" + timeout + "' on @RequiresApproval in "
                    + behaviorClass.getName(), e);
        }
    }

    /**
     * Instantiates an {@link ApprovalNotifier}.
     * Substitutes {@link LoggingApprovalNotifier} when the sentinel
     * {@link DefaultApprovalNotifier} is specified.
     *
     * @throws HitlWiringException if instantiation fails
     */
    static ApprovalNotifier instantiateNotifier(Class<? extends ApprovalNotifier> notifierClass,
                                                 Class<?> behaviorClass) {
        Class<? extends ApprovalNotifier> resolved =
                notifierClass == DefaultApprovalNotifier.class
                        ? LoggingApprovalNotifier.class
                        : notifierClass;
        try {
            return resolved.getDeclaredConstructor().newInstance();
        } catch (NoSuchMethodException e) {
            throw new HitlWiringException(
                    "ApprovalNotifier class " + resolved.getName()
                    + " has no public no-arg constructor. "
                    + "Use HumanCheckpointBehavior directly for notifiers requiring parameters.",
                    e);
        } catch (Exception e) {
            throw new HitlWiringException(
                    "Failed to instantiate ApprovalNotifier " + resolved.getName()
                    + " for behavior " + behaviorClass.getName() + ": " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Exception
    // -------------------------------------------------------------------------

    /**
     * Thrown when a behavior class listed in {@code @RequiresApproval} cannot be
     * wired at agent bootstrap time.
     */
    public static class HitlWiringException extends JenticException {
        private static final long serialVersionUID = -5557669097342903733L;

		public HitlWiringException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
