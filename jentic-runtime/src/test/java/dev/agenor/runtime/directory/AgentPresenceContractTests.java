package dev.agenor.runtime.directory;

import dev.agenor.core.AgentDescriptor;
import dev.agenor.core.AgentStatus;
import dev.agenor.core.directory.AgentDirectory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reusable contract tests for {@link dev.agenor.core.directory.AgentPresence}.
 *
 * <p>Note: the JDBC directory adapter deliberately does <em>not</em> implement this
 * capability (see ADR-020). Only backends that provide liveness semantics need to
 * extend this contract.
 *
 * @since 0.20.0
 */
public interface AgentPresenceContractTests {

    AgentDirectory createSubject();

    @Test
    @DisplayName("[Presence] heartbeat transitions a STARTING agent to RUNNING")
    default void heartbeat_setsRunning() {
        var dir = createSubject();
        dir.register(AgentDescriptor.builder("p1").agentName("P1")
                .status(AgentStatus.STARTING).build()).join();
        dir.heartbeat("p1").join();

        assertThat(dir.getStatus("p1").join()).isEqualTo(AgentStatus.RUNNING);
    }

    @Test
    @DisplayName("[Presence] getStatus returns UNKNOWN for an unregistered agent")
    default void getStatus_unknownAgentReturnsUnknown() {
        assertThat(createSubject().getStatus("nobody").join()).isEqualTo(AgentStatus.UNKNOWN);
    }

    @Test
    @DisplayName("[Presence] getStatus reflects the last updateStatus call")
    default void getStatus_reflectsLastUpdate() {
        var dir = createSubject();
        dir.register(AgentDescriptor.builder("p2").agentName("P2").build()).join();
        dir.updateStatus("p2", AgentStatus.ERROR).join();

        assertThat(dir.getStatus("p2").join()).isEqualTo(AgentStatus.ERROR);
    }
}
