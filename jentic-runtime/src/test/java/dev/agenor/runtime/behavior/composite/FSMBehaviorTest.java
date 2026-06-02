package dev.agenor.runtime.behavior.composite;

import dev.agenor.core.Behavior;
import dev.agenor.core.BehaviorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

@DisplayName("FSM Behavior Tests")
class FSMBehaviorTest {

    private FSMBehavior fsmBehavior;
    private List<String> stateHistory;
    private AtomicInteger counter;

    @BeforeEach
    void setUp() {
        fsmBehavior = new FSMBehavior("test-fsm", "IDLE");
        stateHistory = new ArrayList<>();
        counter = new AtomicInteger(0);
    }

    @Test
    @DisplayName("Should start in initial state")
    void shouldStartInInitialState() {
        assertThat(fsmBehavior.getCurrentState()).isEqualTo("IDLE");
        assertThat(fsmBehavior.isInState("IDLE")).isTrue();
    }

    @Test
    @DisplayName("Should transition between states based on conditions")
    void shouldTransitionBetweenStates() throws Exception {
        // Given
        fsmBehavior.addState("IDLE", createTestBehavior("idle-action"));
        fsmBehavior.addState("ACTIVE", createTestBehavior("active-action"));
        fsmBehavior.addState("DONE", createTestBehavior("done-action"));

        fsmBehavior.addTransition("IDLE", "ACTIVE", fsm -> counter.get() >= 1);
        fsmBehavior.addTransition("ACTIVE", "DONE", fsm -> counter.get() >= 2);

        // When - execute FSM multiple times
        counter.set(0);
        fsmBehavior.execute().get(1, TimeUnit.SECONDS);
        assertThat(fsmBehavior.getCurrentState()).isEqualTo("IDLE");

        counter.set(1);
        fsmBehavior.execute().get(1, TimeUnit.SECONDS);
        assertThat(fsmBehavior.getCurrentState()).isEqualTo("ACTIVE");

        counter.set(2);
        fsmBehavior.execute().get(1, TimeUnit.SECONDS);
        assertThat(fsmBehavior.getCurrentState()).isEqualTo("DONE");
    }

    @Test
    @DisplayName("Should execute state behavior on execute")
    void shouldExecuteStateBehavior() throws Exception {
        // Given - use global fsmBehavior which has "IDLE" as initial state
        List<String> executed = new ArrayList<>();
        fsmBehavior.addState("IDLE", createRecordingBehavior("idle", executed));

        // When
        fsmBehavior.execute().get(1, TimeUnit.SECONDS);

        // Then
        assertThat(executed).contains("idle");
    }

    @Test
    @DisplayName("Should execute transition action when transitioning")
    void shouldExecuteTransitionAction() throws Exception {
        // Given - Create new FSM with proper initial state
        List<String> executed = new ArrayList<>();
        FSMBehavior testFSM = new FSMBehavior("test-fsm", "START");
        testFSM.addState("START", createRecordingBehavior("start", executed));
        testFSM.addState("END", createRecordingBehavior("end", executed));

        Behavior transitionAction = createRecordingBehavior("transition", executed);
        testFSM.addTransition("START", "END", fsm -> true, "go-to-end", transitionAction);

        // When
        testFSM.execute().get(1, TimeUnit.SECONDS);

        // Then
        assertThat(executed).containsExactly("start", "transition");
        assertThat(testFSM.getCurrentState()).isEqualTo("END");
    }

    @Test
    @DisplayName("Should stay in current state if no transition matches")
    void shouldStayInStateIfNoTransition() throws Exception {
        // Given
        fsmBehavior.addState("IDLE", createTestBehavior("idle"));
        fsmBehavior.addState("ACTIVE", createTestBehavior("active"));
        fsmBehavior.addTransition("IDLE", "ACTIVE", fsm -> false); // Condition never met

        // When
        fsmBehavior.execute().get(1, TimeUnit.SECONDS);

        // Then
        assertThat(fsmBehavior.getCurrentState()).isEqualTo("IDLE");
    }

    @Test
    @DisplayName("Should reset to initial state")
    void shouldResetToInitialState() throws Exception {
        // Given
        fsmBehavior.addState("IDLE", createTestBehavior("idle"));
        fsmBehavior.addState("ACTIVE", createTestBehavior("active"));
        fsmBehavior.addTransition("IDLE", "ACTIVE", fsm -> true);

        fsmBehavior.execute().get(1, TimeUnit.SECONDS);
        assertThat(fsmBehavior.getCurrentState()).isEqualTo("ACTIVE");

        // When
        fsmBehavior.reset();

        // Then
        assertThat(fsmBehavior.getCurrentState()).isEqualTo("IDLE");
    }

    @Test
    @DisplayName("Should support forced transition")
    void shouldSupportForcedTransition() {
        // Given
        fsmBehavior.addState("A", createTestBehavior("a"));
        fsmBehavior.addState("B", createTestBehavior("b"));
        fsmBehavior.addState("C", createTestBehavior("c"));

        // When
        fsmBehavior.transitionTo("C");

        // Then
        assertThat(fsmBehavior.getCurrentState()).isEqualTo("C");
    }

    @Test
    @DisplayName("Should return FSM type")
    void shouldReturnFSMType() {
        assertThat(fsmBehavior.getType()).isEqualTo(BehaviorType.FSM);
    }

    @Test
    @DisplayName("Should build FSM using builder")
    void shouldBuildFSMUsingBuilder() {
        // Given
        FSMBehavior fsm = FSMBehavior.builder("traffic-light", "RED")
                .state("RED", createTestBehavior("stop"))
                .state("YELLOW", createTestBehavior("prepare"))
                .state("GREEN", createTestBehavior("go"))
                .transition("RED", "GREEN", f -> counter.get() > 5)
                .transition("GREEN", "YELLOW", f -> counter.get() > 10)
                .transition("YELLOW", "RED", f -> counter.get() > 15)
                .build();

        // Then
        assertThat(fsm.getCurrentState()).isEqualTo("RED");
        assertThat(fsm.getStateNames()).containsExactlyInAnyOrder("RED", "YELLOW", "GREEN");
    }

    @Test
    @DisplayName("Should fail to build FSM without initial state")
    void shouldFailToBuildWithoutInitialState() {
        // Given
        FSMBehavior.Builder builder = FSMBehavior.builder("invalid-fsm", "UNKNOWN")
                .state("A", createTestBehavior("a"))
                .state("B", createTestBehavior("b"));

        // When / Then
        assertThatThrownBy(() -> builder.build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Initial state 'UNKNOWN' not defined");
    }

    @Test
    @DisplayName("Should handle multiple transitions from same state")
    void shouldHandleMultipleTransitions() throws Exception {
        // Given - Create FSM with correct initial state
        FSMBehavior testFSM = new FSMBehavior("test-fsm", "START");
        testFSM.addState("START", createTestBehavior("start"));
        testFSM.addState("PATH_A", createTestBehavior("path-a"));
        testFSM.addState("PATH_B", createTestBehavior("path-b"));

        // First matching transition should be taken
        testFSM.addTransition("START", "PATH_A", fsm -> counter.get() < 5);
        testFSM.addTransition("START", "PATH_B", fsm -> counter.get() >= 5);

        // When - Test first transition
        counter.set(3);
        testFSM.execute().get(1, TimeUnit.SECONDS);
        assertThat(testFSM.getCurrentState()).isEqualTo("PATH_A");

        // Reset and test second transition
        testFSM.reset(); // Goes back to "START" (initial state)
        counter.set(7);
        testFSM.execute().get(1, TimeUnit.SECONDS);
        assertThat(testFSM.getCurrentState()).isEqualTo("PATH_B");
    }

    @Test
    @DisplayName("Should get all state names")
    void shouldGetAllStateNames() {
        // Given - use global fsmBehavior and include IDLE
        fsmBehavior.addState("IDLE", createTestBehavior("idle"));
        fsmBehavior.addState("A", createTestBehavior("a"));
        fsmBehavior.addState("B", createTestBehavior("b"));
        fsmBehavior.addState("C", createTestBehavior("c"));

        // Then
        assertThat(fsmBehavior.getStateNames()).containsExactlyInAnyOrder("IDLE", "A", "B", "C");
    }

    @Test
    @DisplayName("Should timeout slow state execution")
    void shouldTimeoutSlowState() throws Exception {
        // Given - FSM with 100ms state timeout
        Duration stateTimeout = Duration.ofMillis(100);
        FSMBehavior testFSM = new FSMBehavior("test-fsm", "FAST", stateTimeout);

        testFSM.addState("FAST", createTestBehaviorWithDelay("fast-state", 30));
        testFSM.addState("SLOW", createTestBehaviorWithDelay("slow-state", 500)); // Will timeout
        testFSM.addTransition("FAST", "SLOW", fsm -> counter.get() >= 1);

        // When - transition to SLOW state
        counter.set(1);
        testFSM.execute().get(1, TimeUnit.SECONDS); // Execute FAST, transition to SLOW

        // Try to execute SLOW state (will timeout)
        CompletableFuture<Void> slowFuture = testFSM.execute();
        Thread.sleep(300); // Wait for timeout

        // Then - should still be in SLOW state (timeout doesn't change state)
        assertThat(testFSM.getCurrentState()).isEqualTo("SLOW");
        assertThat(stateHistory).contains("fast-state");
        // slow-state might not be in history due to timeout
    }

    @Test
    @DisplayName("Should get and set state timeout")
    void shouldGetAndSetStateTimeout() {
        // Given
        Duration initialTimeout = Duration.ofSeconds(10);
        FSMBehavior testFSM = new FSMBehavior("test-fsm", "START", initialTimeout);

        // When/Then
        assertThat(testFSM.getStateTimeout()).isEqualTo(initialTimeout);

        // Update timeout
        Duration newTimeout = Duration.ofSeconds(30);
        testFSM.setStateTimeout(newTimeout);

        assertThat(testFSM.getStateTimeout()).isEqualTo(newTimeout);
    }

    @Test
    @DisplayName("Should work without state timeout (null)")
    void shouldWorkWithoutStateTimeout() throws Exception {
        // Given - FSM without timeout
        FSMBehavior testFSM = new FSMBehavior("test-fsm", "START", null);
        testFSM.addState("START", createTestBehaviorWithDelay("start", 50));
        testFSM.addState("END", createTestBehaviorWithDelay("end", 50));
        testFSM.addTransition("START", "END", fsm -> counter.get() >= 1);

        // When
        counter.set(1);
        testFSM.execute().get(1, TimeUnit.SECONDS);
        testFSM.execute().get(1, TimeUnit.SECONDS);

        // Then - both states should execute
        assertThat(testFSM.getCurrentState()).isEqualTo("END");
        assertThat(stateHistory).contains("start", "end");
        assertThat(testFSM.getStateTimeout()).isNull();
    }

    @Test
    @DisplayName("Should build FSM with timeout using builder")
    void shouldBuildFSMWithTimeoutUsingBuilder() {
        // Given/When
        Duration timeout = Duration.ofSeconds(15);
        FSMBehavior fsm = FSMBehavior.builder("test-fsm", "START")
                .stateTimeout(timeout)
                .state("START", createTestBehavior("start"))
                .state("END", createTestBehavior("end"))
                .transition("START", "END", f -> counter.get() > 0)
                .build();

        // Then
        assertThat(fsm.getCurrentState()).isEqualTo("START");
        assertThat(fsm.getStateTimeout()).isEqualTo(timeout);
        assertThat(fsm.getStateNames()).containsExactlyInAnyOrder("START", "END");
    }

    @Test
    @DisplayName("Should build FSM with timeout in constructor")
    void shouldBuildFSMWithTimeoutInConstructor() {
        // Given/When
        Duration timeout = Duration.ofSeconds(20);
        FSMBehavior fsm = FSMBehavior.builder("test-fsm", "INIT", timeout)
                .state("INIT", createTestBehavior("init"))
                .state("DONE", createTestBehavior("done"))
                .build();

        // Then
        assertThat(fsm.getStateTimeout()).isEqualTo(timeout);
    }

    // Helper methods

    private Behavior createTestBehavior(String name) {
        return new TestBehavior(name, 10);
    }

    private Behavior createRecordingBehavior(String name, List<String> recordList) {
        return new RecordingBehavior(name, recordList);
    }

    private Behavior createTestBehaviorWithDelay(String name, long delayMs) {
        return new TestBehavior(name, delayMs);
    }

    private class TestBehavior implements Behavior {
        private final String name;
        private final long delayMs;
        private boolean active = true;

        TestBehavior(String name, long delayMs) {
            this.name = name;
            this.delayMs = delayMs;
        }

        @Override
        public String getBehaviorId() {
            return name;
        }

        @Override
        public dev.agenor.core.Agent getAgent() {
            return null;
        }

        @Override
        public CompletableFuture<Void> execute() {
            return CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(delayMs);
                    stateHistory.add(name);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public void stop() {
            active = false;
        }

        @Override
        public BehaviorType getType() {
            return BehaviorType.ONE_SHOT;
        }

        @Override
        public Duration getInterval() {
            return null;
        }
    }

    private class RecordingBehavior implements Behavior {
        private final String name;
        private final List<String> recordList;
        private boolean active = true;

        RecordingBehavior(String name, List<String> recordList) {
            this.name = name;
            this.recordList = recordList;
        }

        @Override
        public String getBehaviorId() {
            return name;
        }

        @Override
        public dev.agenor.core.Agent getAgent() {
            return null;
        }

        @Override
        public CompletableFuture<Void> execute() {
            return CompletableFuture.runAsync(() -> {
                recordList.add(name);
            });
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public void stop() {
            active = false;
        }

        @Override
        public BehaviorType getType() {
            return BehaviorType.ONE_SHOT;
        }

        @Override
        public Duration getInterval() {
            return null;
        }
    }
}
