package dev.jentic.runtime.guardrail;

import dev.jentic.core.exceptions.JenticException;
import dev.jentic.core.guardrail.InputGuardrail;
import dev.jentic.core.guardrail.OutputGuardrail;
import dev.jentic.core.guardrail.WithGuardrails;
import dev.jentic.runtime.agent.LLMAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the {@link WithGuardrails} annotation from an {@link LLMAgent} subclass
 * and injects a {@link GuardrailChain} into the agent at registration time.
 *
 * <p>Called by {@code JenticRuntime.registerAgent()} whenever the registered
 * agent is an instance of {@link LLMAgent}:
 *
 * <pre>{@code
 * // Inside JenticRuntime.registerAgent():
 * if (agent instanceof LLMAgent llmAgent) {
 *     GuardrailAnnotationProcessor.process(llmAgent);
 * }
 * }</pre>
 *
 * <p><b>Wiring logic</b></p>
 * <ol>
 *   <li>If the agent class has no {@code @WithGuardrails} annotation → no-op.</li>
 *   <li>Instantiate each listed {@link InputGuardrail} and {@link OutputGuardrail}
 *       class via its public no-arg constructor (reflection).</li>
 *   <li>If the agent already has a programmatically configured chain, the
 *       annotation-derived guardrails are <em>prepended</em> to it.</li>
 *   <li>Otherwise a new chain is built and injected via
 *       {@link LLMAgent#setGuardrailChain}.</li>
 * </ol>
 *
 * <p><b>Error handling</b></p>
 * If a guardrail class listed in the annotation cannot be instantiated
 * (missing no-arg constructor, abstract class, etc.) a
 * {@link GuardrailWiringException} is thrown, which aborts agent registration
 * with a clear message.
 *
 * @since 0.13.0
 */
public final class GuardrailAnnotationProcessor {

    private static final Logger log = LoggerFactory.getLogger(GuardrailAnnotationProcessor.class);

    private GuardrailAnnotationProcessor() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Processes the {@link WithGuardrails} annotation on {@code agent}'s class
     * and injects the resulting chain.
     *
     * <p>This method is a no-op if the agent class carries no annotation.
     *
     * @param agent the LLM agent being registered; never {@code null}
     * @throws GuardrailWiringException if any guardrail class cannot be instantiated
     */
    public static void process(LLMAgent agent) {
        WithGuardrails annotation = agent.getClass().getAnnotation(WithGuardrails.class);
        if (annotation == null) {
            return;
        }

        log.debug("Processing @WithGuardrails on agent class {}",
                agent.getClass().getSimpleName());

        GuardrailChain.Builder builder = GuardrailChain.builder();

        // Instantiate input guardrails
        for (Class<? extends InputGuardrail> inputClass : annotation.input()) {
            InputGuardrail instance = instantiate(inputClass);
            builder.addInput(instance);
            log.debug("  + InputGuardrail: {}", inputClass.getSimpleName());
        }

        // Instantiate output guardrails
        for (Class<? extends OutputGuardrail> outputClass : annotation.output()) {
            OutputGuardrail instance = instantiate(outputClass);
            builder.addOutput(instance);
            log.debug("  + OutputGuardrail: {}", outputClass.getSimpleName());
        }

        // If agent already has a programmatic chain, prepend the annotation chain
        GuardrailChain annotationChain = builder.build();
        GuardrailChain existing = agent.getGuardrailChain();

        if (existing != null) {
            GuardrailChain merged = merge(annotationChain, existing);
            agent.setGuardrailChain(merged);
            log.debug("Merged @WithGuardrails chain with existing programmatic chain for agent {}",
                    agent.getAgentId());
        } else {
            agent.setGuardrailChain(annotationChain);
            log.debug("Injected @WithGuardrails chain into agent {}", agent.getAgentId());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Instantiates a guardrail class via its public no-arg constructor.
     *
     * @throws GuardrailWiringException if instantiation fails
     */
    private static <T> T instantiate(Class<T> clazz) {
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch (NoSuchMethodException e) {
            throw new GuardrailWiringException(
                    "Guardrail class " + clazz.getName()
                    + " has no public no-arg constructor. "
                    + "Guardrails configured via @WithGuardrails must expose a no-arg constructor. "
                    + "Use GuardrailChain.builder() for guardrails that require constructor parameters.",
                    e);
        } catch (Exception e) {
            throw new GuardrailWiringException(
                    "Failed to instantiate guardrail class " + clazz.getName()
                    + ": " + e.getMessage(), e);
        }
    }

    /**
     * Merges {@code annotation} chain (applied first) with {@code programmatic}
     * chain (applied after) by rebuilding a single chain that runs both in order.
     */
    static GuardrailChain merge(GuardrailChain annotation, GuardrailChain programmatic) {
        GuardrailChain.Builder merged = GuardrailChain.builder();
        annotation.inputGuardrails().forEach(merged::addInput);
        programmatic.inputGuardrails().forEach(merged::addInput);
        annotation.outputGuardrails().forEach(merged::addOutput);
        programmatic.outputGuardrails().forEach(merged::addOutput);
        return merged.build();
    }

    // -------------------------------------------------------------------------
    // Exception
    // -------------------------------------------------------------------------

    /**
     * Thrown when a guardrail class listed in {@code @WithGuardrails} cannot be
     * instantiated at agent bootstrap time.
     */
    public static class GuardrailWiringException extends JenticException {
		private static final long serialVersionUID = -4857694926905374881L;

		public GuardrailWiringException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}