package dev.agenor.runtime.lifecycle;

import dev.agenor.core.AgentStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class LoggingLifecycleListenerTest {

    private final LoggingLifecycleListener listener = new LoggingLifecycleListener();

    @Test
    void onStatusChange_shouldNotThrowWhenStatusChanges() {
        assertThatCode(() ->
            listener.onStatusChange("agent-1", AgentStatus.STARTING, AgentStatus.RUNNING))
            .doesNotThrowAnyException();
    }

    @Test
    void onStatusChange_shouldNotThrowWhenStatusIsSame() {
        // same status should not log (silently skipped)
        assertThatCode(() ->
            listener.onStatusChange("agent-1", AgentStatus.RUNNING, AgentStatus.RUNNING))
            .doesNotThrowAnyException();
    }

    @Test
    void onStatusChange_shouldHandleNullOldStatus() {
        // null old status is a valid initial transition
        assertThatCode(() ->
            listener.onStatusChange("agent-1", null, AgentStatus.STARTING))
            .doesNotThrowAnyException();
    }

    @Test
    void onStatusChange_shouldHandleAllTransitions() {
        AgentStatus[] statuses = AgentStatus.values();
        for (AgentStatus from : statuses) {
            for (AgentStatus to : statuses) {
                assertThatCode(() ->
                    listener.onStatusChange("test-agent", from, to))
                    .doesNotThrowAnyException();
            }
        }
    }

    @Test
    void onStatusChange_shouldHandleMultipleCalls() {
        assertThatCode(() -> {
            listener.onStatusChange("agent-1", AgentStatus.STARTING, AgentStatus.RUNNING);
            listener.onStatusChange("agent-1", AgentStatus.RUNNING, AgentStatus.STOPPING);
            listener.onStatusChange("agent-1", AgentStatus.STOPPING, AgentStatus.STOPPED);
        }).doesNotThrowAnyException();
    }
}
