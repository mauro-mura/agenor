package dev.agenor.core.persistence;

import dev.agenor.core.AgentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PersistenceStrategy and Stateful Tests")
class PersistenceStrategyAndStatefulTest {

    @Test
    @DisplayName("should have all expected persistence strategies")
    void shouldHaveAllExpectedStrategies() {
        // Given/When
        PersistenceStrategy[] strategies = PersistenceStrategy.values();

        // Then
        assertEquals(6, strategies.length);
        assertNotNull(PersistenceStrategy.valueOf("MANUAL"));
        assertNotNull(PersistenceStrategy.valueOf("IMMEDIATE"));
        assertNotNull(PersistenceStrategy.valueOf("PERIODIC"));
        assertNotNull(PersistenceStrategy.valueOf("ON_STOP"));
        assertNotNull(PersistenceStrategy.valueOf("DEBOUNCED"));
        assertNotNull(PersistenceStrategy.valueOf("SNAPSHOT"));
    }

    @Test
    @DisplayName("should get strategy by name")
    void shouldGetStrategyByName() {
        // When/Then
        assertEquals(PersistenceStrategy.MANUAL, PersistenceStrategy.valueOf("MANUAL"));
        assertEquals(PersistenceStrategy.IMMEDIATE, PersistenceStrategy.valueOf("IMMEDIATE"));
        assertEquals(PersistenceStrategy.PERIODIC, PersistenceStrategy.valueOf("PERIODIC"));
        assertEquals(PersistenceStrategy.ON_STOP, PersistenceStrategy.valueOf("ON_STOP"));
        assertEquals(PersistenceStrategy.DEBOUNCED, PersistenceStrategy.valueOf("DEBOUNCED"));
        assertEquals(PersistenceStrategy.SNAPSHOT, PersistenceStrategy.valueOf("SNAPSHOT"));
    }

    @Test
    @DisplayName("should throw exception for invalid strategy name")
    void shouldThrowExceptionForInvalidStrategyName() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () ->
            PersistenceStrategy.valueOf("INVALID")
        );
    }

    @Test
    @DisplayName("should implement Stateful interface")
    void shouldImplementStatefulInterface() {
        // Given
        TestStatefulAgent agent = new TestStatefulAgent("test-agent-1");
        agent.setCounter(42);
        agent.setName("Test Agent");

        // When
        AgentState captured = agent.captureState();

        // Then
        assertEquals("test-agent-1", captured.agentId());
        assertEquals(42, captured.getData("counter", Integer.class));
        assertEquals("Test Agent", captured.getData("name", String.class));
        assertEquals(1L, captured.version());
    }

    @Test
    @DisplayName("should restore state using Stateful interface")
    void shouldRestoreStateUsingStatefulInterface() {
        // Given
        TestStatefulAgent agent = new TestStatefulAgent("test-agent-1");
        AgentState state = AgentState.builder("test-agent-1")
                .agentName("Restored Agent")
                .status(AgentStatus.RUNNING)
                .data("counter", 100)
                .data("name", "Restored")
                .version(5)
                .build();

        // When
        agent.restoreState(state);

        // Then
        assertEquals(100, agent.getCounter());
        assertEquals("Restored", agent.getName());
        assertEquals(5L, agent.getStateVersion());
    }

    @Test
    @DisplayName("should use default state version")
    void shouldUseDefaultStateVersion() {
        // Given
        Stateful agent = new Stateful() {
            @Override
            public AgentState captureState() {
                return AgentState.builder("test").build();
            }

            @Override
            public void restoreState(AgentState state) {
                // No-op for test
            }
        };

        // When
        long version = agent.getStateVersion();

        // Then
        assertEquals(1L, version);
    }

    @Test
    @DisplayName("should handle null values in restore")
    void shouldHandleNullValuesInRestore() {
        // Given
        TestStatefulAgent agent = new TestStatefulAgent("test-agent-1");
        agent.setCounter(50);
        agent.setName("Original");

        AgentState state = AgentState.builder("test-agent-1")
                .data("counter", null)
                .build();

        // When
        agent.restoreState(state);

        // Then
        assertEquals(0, agent.getCounter());  // Default value when null
        assertNull(agent.getName());
    }

    @Test
    @DisplayName("should increment version on state capture")
    void shouldIncrementVersionOnStateCapture() {
        // Given
        TestStatefulAgent agent = new TestStatefulAgent("test-agent-1");

        // When
        AgentState state1 = agent.captureState();
        agent.incrementVersion();
        AgentState state2 = agent.captureState();

        // Then
        assertEquals(1L, state1.version());
        assertEquals(2L, state2.version());
    }

    @Test
    @DisplayName("should preserve state version through save-restore cycle")
    void shouldPreserveStateVersionThroughSaveRestoreCycle() {
        // Given
        TestStatefulAgent agent1 = new TestStatefulAgent("test-agent-1");
        agent1.setCounter(42);
        agent1.setStateVersion(10L);

        // When
        AgentState captured = agent1.captureState();
        TestStatefulAgent agent2 = new TestStatefulAgent("test-agent-1");
        agent2.restoreState(captured);

        // Then
        assertEquals(10L, agent2.getStateVersion());
        assertEquals(42, agent2.getCounter());
    }

    /**
     * Test implementation of Stateful interface
     */
    private static class TestStatefulAgent implements Stateful {
        private final String agentId;
        private int counter;
        private String name;
        private long stateVersion = 1L;

        public TestStatefulAgent(String agentId) {
            this.agentId = agentId;
        }

        @Override
        public AgentState captureState() {
            return AgentState.builder(agentId)
                    .agentName("Test Stateful Agent")
                    .status(AgentStatus.RUNNING)
                    .data("counter", counter)
                    .data("name", name)
                    .version(stateVersion)
                    .build();
        }

        @Override
        public void restoreState(AgentState state) {
            Integer restoredCounter = state.getData("counter", Integer.class);
            this.counter = restoredCounter != null ? restoredCounter : 0;
            this.name = state.getData("name", String.class);
            this.stateVersion = state.version();
        }

        @Override
        public long getStateVersion() {
            return stateVersion;
        }

        public int getCounter() {
            return counter;
        }

        public void setCounter(int counter) {
            this.counter = counter;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public void setStateVersion(long version) {
            this.stateVersion = version;
        }

        public void incrementVersion() {
            this.stateVersion++;
        }
    }
}
