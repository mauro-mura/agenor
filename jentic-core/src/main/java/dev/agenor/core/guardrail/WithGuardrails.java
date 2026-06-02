package dev.agenor.core.guardrail;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declaratively attaches guardrails to an {@code LLMAgent} subclass.
 *
 * <p>{@code JenticRuntime} reads this annotation at agent registration time,
 * instantiates the listed guardrail classes via their no-arg constructors,
 * builds a {@code GuardrailChain}, and injects it into the agent.
 *
 * <p>Guardrails that require constructor parameters (e.g. a YAML config path) must
 * be configured programmatically via the {@code GuardrailChain} builder instead.
 * Both approaches can be combined: the annotation-derived chain is extended with any
 * programmatically registered guardrails.
 *
 * <p>Example:
 * <pre>{@code
 * @WithGuardrails(
 *     input  = { PiiRedactionGuardrail.class, MaxTokensInputGuardrail.class },
 *     output = { ContentPolicyGuardrail.class }
 * )
 * public class FinanceAgent extends LLMAgent {
 *     // ...
 * }
 * }</pre>
 *
 * @see InputGuardrail
 * @see OutputGuardrail
 * @since 0.13.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface WithGuardrails {

    /**
     * Input guardrail classes to apply before the LLM call, in declaration order.
     * Each class must have a public no-arg constructor.
     */
    Class<? extends InputGuardrail>[] input() default {};

    /**
     * Output guardrail classes to apply after the LLM call, in declaration order.
     * Each class must have a public no-arg constructor.
     */
    Class<? extends OutputGuardrail>[] output() default {};
}
