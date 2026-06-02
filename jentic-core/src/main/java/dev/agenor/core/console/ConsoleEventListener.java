package dev.agenor.core.console;

/**
 * Listener for console events, typically broadcast via WebSocket.
 *
 * <p>Implementations receive notifications about agent lifecycle
 * and messaging events for real-time UI updates.
 *
 * @since 0.4.0
 */
public interface ConsoleEventListener {

	void onAgentStarted(String agentId, String agentName);

    void onAgentStopped(String agentId, String agentName);

    void onMessageSent(String messageId, String topic, String senderId);

    void onError(String source, String message);

    void onBehaviorExecuted(String agentId, String behaviorId,
                            long durationMs, boolean success, String error);

    static ConsoleEventListener noOp() {
        return new ConsoleEventListener() {
            @Override public void onAgentStarted(String agentId, String agentName) {}
            @Override public void onAgentStopped(String agentId, String agentName) {}
            @Override public void onMessageSent(String messageId, String topic, String senderId) {}
            @Override public void onError(String source, String message) {}
            @Override public void onBehaviorExecuted(String agentId, String behaviorId,
                                                     long durationMs, boolean success, String error) {}
        };
    }
}
