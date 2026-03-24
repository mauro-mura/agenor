package dev.jentic.core.hitl;

/**
 * Sentinel class used as the default value of {@link RequiresApproval#notifier()}.
 *
 * <p>This class is <em>never</em> instantiated directly. When the runtime wiring
 * encounters this sentinel it substitutes the concrete default implementation
 * ({@code LoggingApprovalNotifier}) so that {@code jentic-core} has no dependency
 * on {@code jentic-runtime}.
 *
 * @since 0.13.0
 */
public final class DefaultApprovalNotifier implements ApprovalNotifier {

    private DefaultApprovalNotifier() {
        // Sentinel — must not be instantiated
        throw new UnsupportedOperationException(
                "DefaultApprovalNotifier is a sentinel class; "
                + "it is replaced by the runtime wiring with LoggingApprovalNotifier.");
    }

    @Override
    public void notify(ApprovalRequest request) {
        throw new UnsupportedOperationException("sentinel");
    }
}