package dev.agenor.runtime.directory;

import dev.agenor.core.AgentStatus;
import dev.agenor.core.directory.AgentPresence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link AgentHeartbeatDriver}.
 */
@DisplayName("AgentHeartbeatDriver")
class AgentHeartbeatDriverTest {

    /** Generous: these tests wait for a 20 ms cadence, so any delay here is a real stall. */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final RecordingPresence presence = new RecordingPresence();
    private final List<String> agentIds = new CopyOnWriteArrayList<>();

    private AgentHeartbeatDriver driver;

    @AfterEach
    void stopDriver() {
        if (driver != null) {
            driver.stop();
        }
    }

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("null collaborators are rejected")
    void nullCollaboratorsAreRejected() {
        assertThatNullPointerException().isThrownBy(
                () -> new AgentHeartbeatDriver(null, List::of, Duration.ofSeconds(1)));
        assertThatNullPointerException().isThrownBy(
                () -> new AgentHeartbeatDriver(presence, null, Duration.ofSeconds(1)));
        assertThatNullPointerException().isThrownBy(
                () -> new AgentHeartbeatDriver(presence, List::of, null));
    }

    @Test
    @DisplayName("a zero or negative interval is rejected")
    void nonPositiveIntervalIsRejected() {
        // Given an interval that would either spin or never fire
        assertThatIllegalArgumentException().isThrownBy(
                () -> new AgentHeartbeatDriver(presence, List::of, Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new AgentHeartbeatDriver(presence, List::of, Duration.ofSeconds(-1)));
    }

    @Test
    @DisplayName("construction schedules nothing")
    void constructionSchedulesNothing() {
        // Given a driver that was built but never started
        driver = newDriver(Duration.ofMillis(10));

        // Then it is idle — start() is what turns heartbeats on
        assertThat(driver.isRunning()).isFalse();
        assertThat(driver.interval()).isEqualTo(Duration.ofMillis(10));
    }

    // -------------------------------------------------------------------------
    // One round of beats
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a beat heartbeats every supplied agent")
    void beatHeartbeatsEveryAgent() {
        // Given three agents
        agentIds.addAll(List.of("a", "b", "c"));
        driver = newDriver(Duration.ofSeconds(30));

        // When one round runs
        driver.beat();

        // Then each was told it is alive, exactly once
        assertThat(presence.beaten).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("the agent list is read on every beat, not captured once")
    void agentListIsReadOnEveryBeat() {
        // Given one agent at the first beat
        agentIds.add("first");
        driver = newDriver(Duration.ofSeconds(30));
        driver.beat();

        // When a second agent starts between rounds
        agentIds.add("second");
        driver.beat();

        // Then it is beaten too — an agent that starts late must not stay invisible
        assertThat(presence.beaten).containsExactly("first", "first", "second");
    }

    @Test
    @DisplayName("no agents means no heartbeats and no failure")
    void emptyRoundIsHarmless() {
        driver = newDriver(Duration.ofSeconds(30));

        driver.beat();

        assertThat(presence.beaten).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Failure containment — a cancelled timer would look like a dead backend
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a heartbeat that throws does not stop the round")
    void throwingHeartbeatDoesNotStopTheRound() {
        // Given a backend that throws for one agent
        agentIds.addAll(List.of("ok-before", "boom", "ok-after"));
        presence.failWith = id -> "boom".equals(id)
                ? new IllegalStateException("connection pool exhausted") : null;
        driver = newDriver(Duration.ofSeconds(30));

        // When a round runs
        driver.beat();

        // Then the agents either side of the failure were still beaten
        assertThat(presence.beaten).containsExactly("ok-before", "boom", "ok-after");
    }

    @Test
    @DisplayName("a failed future does not stop the round")
    void failedFutureDoesNotStopTheRound() {
        // Given a backend whose future completes exceptionally
        agentIds.addAll(List.of("x", "y"));
        presence.failFuture = true;
        driver = newDriver(Duration.ofSeconds(30));

        // When a round runs / Then both agents were attempted
        driver.beat();

        assertThat(presence.beaten).containsExactly("x", "y");
    }

    @Test
    @DisplayName("a failing backend does not cancel the schedule")
    void failingBackendDoesNotCancelTheSchedule() {
        // Given a backend that throws on every call. scheduleAtFixedRate cancels a task that
        // lets an exception escape, and a driver that silently stopped is indistinguishable
        // from a backend that went quiet — every agent would drift to UNKNOWN with no signal.
        agentIds.add("always-fails");
        presence.failWith = id -> new IllegalStateException("down");
        driver = newDriver(Duration.ofMillis(20));

        // When it runs for several intervals
        driver.start();

        // Then beats keep arriving
        awaitBeats(3);
    }

    // -------------------------------------------------------------------------
    // Scheduling
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("start beats repeatedly until stop")
    void startBeatsUntilStopped() {
        // Given a running driver with one agent
        agentIds.add("agent-1");
        driver = newDriver(Duration.ofMillis(20));
        driver.start();
        assertThat(driver.isRunning()).isTrue();

        // When beats have arrived and the driver is stopped
        awaitBeats(2);
        driver.stop();
        assertThat(driver.isRunning()).isFalse();
        int afterStop = presence.beaten.size();

        // Then no further beat arrives, several intervals later
        sleep(200);
        assertThat(presence.beaten).hasSize(afterStop);
    }

    @Test
    @DisplayName("the first beat waits one interval")
    void firstBeatWaitsOneInterval() {
        // Given an agent that has just registered, so its last_seen is already current
        agentIds.add("just-registered");
        driver = newDriver(Duration.ofSeconds(30));

        // When the driver starts
        driver.start();
        sleep(100);

        // Then nothing is written immediately — there is nothing to refresh yet
        assertThat(presence.beaten).isEmpty();
    }

    @Test
    @DisplayName("start and stop are idempotent")
    void startAndStopAreIdempotent() {
        agentIds.add("agent-1");
        driver = newDriver(Duration.ofMillis(20));

        // Given a driver started twice
        driver.start();
        driver.start();
        assertThat(driver.isRunning()).isTrue();

        // When stopped twice
        driver.stop();
        driver.stop();

        // Then it is simply not running
        assertThat(driver.isRunning()).isFalse();
    }

    @Test
    @DisplayName("a stopped driver can be restarted")
    void stoppedDriverCanBeRestarted() {
        agentIds.add("agent-1");
        driver = newDriver(Duration.ofMillis(20));

        driver.start();
        awaitBeats(1);
        driver.stop();
        int beforeRestart = presence.beaten.size();

        // When started again / Then beats resume
        driver.start();
        awaitBeats(beforeRestart + 1);
    }

    @Test
    @DisplayName("close stops the driver")
    void closeStops() {
        agentIds.add("agent-1");
        driver = newDriver(Duration.ofMillis(20));
        driver.start();

        driver.close();

        assertThat(driver.isRunning()).isFalse();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private AgentHeartbeatDriver newDriver(Duration interval) {
        return new AgentHeartbeatDriver(presence, () -> agentIds, interval);
    }

    /** Waits until at least {@code expected} beats have been recorded, or fails. */
    private void awaitBeats(int expected) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (presence.beaten.size() < expected && System.nanoTime() < deadline) {
            sleep(5);
        }
        assertThat(presence.beaten.size())
                .as("beats recorded within %s", TIMEOUT)
                .isGreaterThanOrEqualTo(expected);
    }

    private static void sleep(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for heartbeats", e);
        }
    }

    /** Records what it was asked to heartbeat, and fails on demand. */
    private static final class RecordingPresence implements AgentPresence {

        private final List<String> beaten = new CopyOnWriteArrayList<>();

        /** Returns the exception to throw for an agent id, or null to succeed. */
        private Function<String, RuntimeException> failWith = id -> null;

        /** When true, heartbeat returns a future that has already failed. */
        private boolean failFuture;

        @Override
        public CompletableFuture<Void> heartbeat(String agentId) {
            beaten.add(agentId);
            RuntimeException failure = failWith.apply(agentId);
            if (failure != null) {
                throw failure;
            }
            return failFuture
                    ? CompletableFuture.failedFuture(new IllegalStateException("write rejected"))
                    : CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<AgentStatus> getStatus(String agentId) {
            return CompletableFuture.completedFuture(AgentStatus.UNKNOWN);
        }
    }
}
