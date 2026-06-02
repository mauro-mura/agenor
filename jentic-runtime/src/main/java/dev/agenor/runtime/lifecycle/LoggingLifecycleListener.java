package dev.agenor.runtime.lifecycle;

import dev.agenor.core.AgentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default lifecycle listener that logs status changes
 */
public class LoggingLifecycleListener implements LifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingLifecycleListener.class);

    @Override
    public void onStatusChange(String agentId, AgentStatus oldStatus, AgentStatus newStatus) {
        if (oldStatus != newStatus) {
            log.info("Agent lifecycle: {} {} -> {}", agentId,
                    oldStatus != null ? oldStatus : "null", newStatus);
        }
    }
}
