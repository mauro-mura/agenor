package dev.agenor.core.directory;

import dev.agenor.core.AgentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reusable contract tests for {@link AgentPresence}.
 *
 * <p>Every backend that provides liveness semantics is expected to satisfy these tests.
 * Failure-detection latency and the staleness window are backend properties and are not
 * asserted here.
 *
 * <p>Unlike the sibling registry, resolver and discovery suites, this one is typed on the
 * capability rather than on {@link AgentDirectory}, because a presence backend need not be a
 * directory — {@code JdbcAgentPresence} is a standalone class. Seeding therefore goes through
 * {@link Fixture}, which pairs the subject under test with a way to write to the same store.
 *
 * @since 0.20.0
 */
public interface AgentPresenceContractTests {

    /**
     * Creates a fresh, isolated fixture for a single test.
     *
     * @return a fixture whose presence subject and seeding methods share one store; never null
     */
    Fixture createPresenceFixture();

    /**
     * Pairs the presence implementation under test with a way to seed the store it reads.
     */
    interface Fixture {

        /**
         * Returns the presence implementation under test.
         *
         * @return the subject; never null
         */
        AgentPresence presence();

        /**
         * Registers an agent with the given status, visible to {@link #presence()}.
         *
         * @param agentId the agent id, must not be null
         * @param status  the initial status, must not be null
         */
        void register(String agentId, AgentStatus status);

        /**
         * Updates a registered agent's status, visible to {@link #presence()}.
         *
         * @param agentId the agent id, must not be null
         * @param status  the new status, must not be null
         */
        void updateStatus(String agentId, AgentStatus status);
    }

    @Test
    @DisplayName("[Presence] heartbeat transitions a STARTING agent to RUNNING")
    default void heartbeat_setsRunning() {
        // Given an agent registered as still starting
        var fixture = createPresenceFixture();
        fixture.register("p1", AgentStatus.STARTING);

        // When it heartbeats
        fixture.presence().heartbeat("p1").join();

        // Then its status reads RUNNING
        assertThat(fixture.presence().getStatus("p1").join()).isEqualTo(AgentStatus.RUNNING);
    }

    @Test
    @DisplayName("[Presence] getStatus returns UNKNOWN for an unregistered agent")
    default void getStatus_unknownAgentReturnsUnknown() {
        // Given a store that has never seen this agent
        var presence = createPresenceFixture().presence();

        // When its status is requested / Then the answer is UNKNOWN
        assertThat(presence.getStatus("nobody").join()).isEqualTo(AgentStatus.UNKNOWN);
    }

    @Test
    @DisplayName("[Presence] getStatus reflects the last updateStatus call")
    default void getStatus_reflectsLastUpdate() {
        // Given a registered agent
        var fixture = createPresenceFixture();
        fixture.register("p2", AgentStatus.STARTING);

        // When its status is updated
        fixture.updateStatus("p2", AgentStatus.ERROR);

        // Then getStatus reports the new value
        assertThat(fixture.presence().getStatus("p2").join()).isEqualTo(AgentStatus.ERROR);
    }
}
