package dev.agenor.core.guardrail;

import dev.agenor.core.exceptions.AgenorException;

import java.util.Objects;

/**
 * Thrown when a guardrail in the chain returns {@link GuardrailResult.Blocked},
 * indicating that the content must not proceed through the pipeline.
 *
 * <p>Extends {@link AgenorException} (unchecked) to be consistent with the Jentic
 * exception hierarchy (ADR-014). Callers may catch it explicitly when they need to
 * handle policy violations, but are not required to declare it.
 *
 * <p>Example:
 * <pre>{@code
 * try {
 *     String response = agent.chat(userInput);
 * } catch (GuardrailViolationException e) {
 *     log.warn("Guardrail {} blocked request: {}", e.blockedBy(), e.reason());
 *     return "Your request could not be processed due to content policy.";
 * }
 * }</pre>
 *
 * @since 0.13.0
 */
public class GuardrailViolationException extends AgenorException {

	private static final long serialVersionUID = -2251140556509681723L;

	private final String reason;
    private final String blockedBy;

    /**
     * Creates a new violation exception.
     *
     * @param reason    human-readable explanation from the blocking guardrail; never {@code null}
     * @param blockedBy fully-qualified class name of the guardrail that issued the block;
     *                  never {@code null}
     */
    public GuardrailViolationException(String reason, String blockedBy) {
        super("Guardrail [" + blockedBy + "] blocked content: " + reason);
        this.reason    = Objects.requireNonNull(reason,    "reason must not be null");
        this.blockedBy = Objects.requireNonNull(blockedBy, "blockedBy must not be null");
    }

    /**
     * Creates a new violation exception with an underlying cause.
     *
     * @param reason    human-readable explanation; never {@code null}
     * @param blockedBy guardrail class name; never {@code null}
     * @param cause     the underlying exception, if any
     */
    public GuardrailViolationException(String reason, String blockedBy, Throwable cause) {
        super("Guardrail [" + blockedBy + "] blocked content: " + reason, cause);
        this.reason    = Objects.requireNonNull(reason,    "reason must not be null");
        this.blockedBy = Objects.requireNonNull(blockedBy, "blockedBy must not be null");
    }

    /**
     * Returns the human-readable reason provided by the blocking guardrail.
     *
     * @return the block reason; never {@code null}
     */
    public String reason() {
        return reason;
    }

    /**
     * Returns the fully-qualified class name of the guardrail that blocked the content.
     *
     * @return the guardrail class name; never {@code null}
     */
    public String blockedBy() {
        return blockedBy;
    }
}
