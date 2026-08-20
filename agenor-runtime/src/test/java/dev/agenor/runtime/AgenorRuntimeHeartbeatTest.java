package dev.agenor.runtime;

import dev.agenor.core.AgentStatus;
import dev.agenor.core.directory.AgentPresence;
import dev.agenor.runtime.agent.BaseAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * How {@link AgenorRuntime} drives {@link dev.agenor.runtime.directory.AgentHeartbeatDriver}
 * (ADR-028 D-3): off unless asked for, alive only while the runtime is.
 */
@DisplayName("AgenorRuntime — heartbeats")
class AgenorRuntimeHeartbeatTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final RecordingPresence presence = new RecordingPresence();

    private AgenorRuntime runtime;

    @AfterEach
    void stopRuntime() {
        if (runtime != null && runtime.isRunning()) {
            runtime.stop().join();
        }
    }

    @Test
    @DisplayName("no heartbeat interval means no heartbeats at all")
    void heartbeatsAreOffByDefault() {
        // Given a runtime with a presence backend but no configured interval
        runtime = AgenorRuntime.builder()
                .agentPresence(presence)
                .build();
        runtime.registerAgent(new TestAgent("agent-1", "Agent 1"));

        // When it runs for many times a plausible cadence
        runtime.start().join();
        sleep(200);

        // Then nothing was ever heartbeated — existing deployments see no new writes
        assertThat(presence.beaten).isEmpty();
    }

    @Test
    @DisplayName("a configured interval heartbeats the running agents")
    void configuredIntervalHeartbeatsRunningAgents() {
        // Given a runtime with two agents and a 20 ms cadence
        runtime = AgenorRuntime.builder()
                .agentPresence(presence)
                .heartbeatInterval(Duration.ofMillis(20))
                .build();
        runtime.registerAgent(new TestAgent("agent-1", "Agent 1"));
        runtime.registerAgent(new TestAgent("agent-2", "Agent 2"));

        // When it starts
        runtime.start().join();

        // Then both agents are reported alive, repeatedly
        awaitBeats("agent-1", 2);
        awaitBeats("agent-2", 2);
    }

    @Test
    @DisplayName("stopping the runtime stops the heartbeats and releases the thread")
    void stoppingTheRuntimeStopsHeartbeats() {
        // Given a runtime beating for one agent
        runtime = AgenorRuntime.builder()
                .agentPresence(presence)
                .heartbeatInterval(Duration.ofMillis(20))
                .build();
        runtime.registerAgent(new TestAgent("agent-1", "Agent 1"));
        runtime.start().join();
        awaitBeats("agent-1", 1);
        assertThat(heartbeatThreadAlive()).isTrue();

        // When the runtime stops
        runtime.stop().join();
        int afterStop = presence.beaten.size();

        // Then no beat outlives it — a heartbeat for a stopped agent would be a lie
        sleep(200);
        assertThat(presence.beaten).hasSize(afterStop);

        // And the timer is really gone. Beats alone would not prove it: stop() unregisters
        // the agents first, so a driver that was never stopped would also fall silent — it
        // would just keep a thread per runtime alive forever.
        assertThat(heartbeatThreadAlive()).isFalse();
    }

    @Test
    @DisplayName("a zero or negative interval is rejected by the builder")
    void nonPositiveIntervalIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> AgenorRuntime.builder().heartbeatInterval(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(
                () -> AgenorRuntime.builder().heartbeatInterval(Duration.ofSeconds(-5)));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void awaitBeats(String agentId, int expected) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (count(agentId) < expected && System.nanoTime() < deadline) {
            sleep(5);
        }
        assertThat(count(agentId))
                .as("heartbeats for %s within %s", agentId, TIMEOUT)
                .isGreaterThanOrEqualTo(expected);
    }

    /** Whether the driver's single named daemon thread is alive right now. */
    private static boolean heartbeatThreadAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.isAlive() && "agenor-heartbeat".equals(t.getName()));
    }

    private long count(String agentId) {
        return presence.beaten.stream().filter(agentId::equals).count();
    }

    private static void sleep(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for heartbeats", e);
        }
    }

    static class TestAgent extends BaseAgent {
        TestAgent(String agentId, String agentName) {
            super(agentId, agentName);
        }
    }

    /** Records every heartbeat it receives. */
    private static final class RecordingPresence implements AgentPresence {

        private final List<String> beaten = new CopyOnWriteArrayList<>();

        @Override
        public CompletableFuture<Void> heartbeat(String agentId) {
            beaten.add(agentId);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<AgentStatus> getStatus(String agentId) {
            return CompletableFuture.completedFuture(AgentStatus.UNKNOWN);
        }
    }
}
