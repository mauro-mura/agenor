package dev.agenor.core.hitl;

/**
 * Outcome of a human approval request.
 *
 * <p>Three mutually exclusive variants are modeled as records inside this sealed
 * interface, enabling exhaustive {@code switch} expressions with full compiler
 * enforcement (Java 21+):
 *
 * <pre>{@code
 * ApprovalDecision decision = gate.requestApproval(request).join();
 * switch (decision) {
 *     case ApprovalDecision.Approved a  -> executeAction(request.payloadAs(Action.class));
 *     case ApprovalDecision.Rejected r  -> log.warn("Rejected: {}", r.reason());
 *     case ApprovalDecision.Modified m  -> executeAction(m.newPayloadAs(Action.class));
 * }
 * }</pre>
 *
 * @see ApprovalGate
 * @see ApprovalRequest
 * @since 0.13.0
 */
public sealed interface ApprovalDecision
        permits ApprovalDecision.Approved,
                ApprovalDecision.Rejected,
                ApprovalDecision.Modified {

    /** The action is approved; the agent proceeds with the original payload. */
    record Approved() implements ApprovalDecision {}

    /**
     * The action is rejected; the agent must not execute it.
     *
     * @param reason human-readable explanation; never {@code null}
     */
    record Rejected(String reason) implements ApprovalDecision {
        public Rejected {
            if (reason == null) throw new IllegalArgumentException("reason must not be null");
        }
    }

    /**
     * The action is approved with a modified payload; the agent executes with
     * {@link #newPayload()} instead of the original.
     *
     * @param newPayload the revised action payload; never {@code null}
     */
    record Modified(Object newPayload) implements ApprovalDecision {
        public Modified {
            if (newPayload == null) throw new IllegalArgumentException("newPayload must not be null");
        }

        /**
         * Reads {@link #newPayload()} as the requested type, converting it if necessary.
         *
         * <p>A decision submitted on another node reaches a persistent gate's future as the
         * JSON it stored, not as the submitter's object, so a cast that works in memory throws
         * there. Same conversion as {@link ApprovalRequest#payloadAs(Class)}.
         *
         * @param <T> the expected type of the payload
         * @param type the class of the expected type; must not be null
         * @return the new payload as type T
         * @throws IllegalArgumentException if the payload is neither an instance of {@code type}
         *         nor convertible to it
         * @since 0.38.0
         */
        public <T> T newPayloadAs(Class<T> type) {
            return PayloadConversion.as(newPayload, type);
        }
    }
}
