package dev.agenor.core.spi;

import dev.agenor.core.Agent;
import dev.agenor.core.guardrail.WithGuardrails;
import dev.agenor.core.hitl.RequiresApproval;

import java.lang.annotation.Annotation;
import java.util.Set;

/**
 * Extension point invoked once per {@code AgenorRuntime.registerAgent()} call,
 * discovered via {@link java.util.ServiceLoader}.
 *
 * <p>Lets optional runtime modules (LLM guardrails, HITL behavior wrapping, ...)
 * hook into agent registration without {@code agenor-runtime} depending on them
 * directly. Absent implementations simply never register — the corresponding
 * feature becomes an inert annotation rather than a compile-time dependency.
 *
 * @since 0.25.0
 */
public interface AgentRegistrationExtension {

    /**
     * The annotations whose effect depends on an optional module being present.
     *
     * <p>Declared here, next to {@link #handledAnnotations()}, on purpose: the two halves of
     * the same contract — "which annotations can silently do nothing" and "who claims to act
     * on them" — stay in one file. <strong>Add an entry here when introducing an annotation
     * that a shipped {@code AgentRegistrationExtension} acts on</strong>, or the runtime will
     * not be able to tell the user when its providing module is missing.
     *
     * <p>Every entry is an {@code agenor-core} type, so this list creates no dependency on the
     * modules that implement the features.
     *
     * @since 0.26.0
     */
    Set<Class<? extends Annotation>> OPTIONAL_FEATURE_ANNOTATIONS =
            Set.of(WithGuardrails.class, RequiresApproval.class);

    /**
     * Called once for {@code agent} during {@code registerAgent()}.
     *
     * @param agent   the agent being registered; never {@code null}
     * @param context runtime services available to this extension; never {@code null}
     */
    void onAgentRegistered(Agent agent, RegistrationContext context);

    /**
     * The annotations this extension acts on, for diagnostics.
     *
     * <p>The runtime uses the union of these sets to detect annotations that no loaded
     * extension claims — the signature of a missing optional module — and reports them once
     * at startup. It is <strong>never</strong> used to decide whether
     * {@link #onAgentRegistered(Agent, RegistrationContext)} is invoked: every registered
     * extension is always invoked for every agent, whatever this returns.
     *
     * <p>Claiming an annotation asserts only that this extension processes it, not that it
     * will have an effect on every agent carrying it — an extension that applies to a
     * specific agent type should say so itself when it sees the annotation on an agent it
     * cannot serve.
     *
     * <p>Defaults to the empty set, so extensions written before this method existed keep
     * compiling and working; they simply claim nothing.
     *
     * @return the annotations this extension processes; never {@code null}
     * @since 0.26.0
     */
    default Set<Class<? extends Annotation>> handledAnnotations() {
        return Set.of();
    }
}
