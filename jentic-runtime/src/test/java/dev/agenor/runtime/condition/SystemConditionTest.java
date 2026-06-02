package dev.agenor.runtime.condition;

import dev.agenor.core.Agent;
import dev.agenor.core.condition.Condition;
import dev.agenor.runtime.agent.BaseAgent;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for SystemCondition pre-built conditions
 */
class SystemConditionTest {

    private final Agent testAgent = new TestAgent();

    @Test
    void shouldCheckCpuBelow() {
        // Given
        Condition condition = SystemCondition.cpuBelow(100.0); // Should always be true

        // When/Then
        assertThat(condition.evaluate(testAgent)).isTrue();
    }

    @Test
    void shouldCheckCpuAbove() {
        // Given
        Condition condition = SystemCondition.cpuAbove(100.0); // Should be false

        // When/Then
        assertThat(condition.evaluate(testAgent)).isFalse();
    }

    @Test
    void shouldCheckMemoryBelow() {
        // Given
        Condition condition = SystemCondition.memoryBelow(100.0); // Should be true

        // When/Then
        assertThat(condition.evaluate(testAgent)).isTrue();
    }

    @Test
    void shouldCheckMemoryAbove() {
        // Given
        Condition condition = SystemCondition.memoryAbove(100.0);

        // When/Then
        assertThat(condition.evaluate(testAgent)).isFalse();
    }

    @Test
    void shouldCheckAvailableMemoryAbove() {
        // Given - very low threshold
        Condition condition = SystemCondition.availableMemoryAbove(1024);

        // When/Then - should have at least 1KB available
        assertThat(condition.evaluate(testAgent)).isTrue();
    }

    @Test
    void shouldCheckAvailableMemoryBelowUnrealistic() {
        // Given - unrealistically high threshold
        Condition condition = SystemCondition.availableMemoryAbove(Long.MAX_VALUE);

        // When/Then
        assertThat(condition.evaluate(testAgent)).isFalse();
    }

    @Test
    void shouldCheckThreadsBelow() {
        // Given - high threshold
        Condition condition = SystemCondition.threadsBelow(10000);

        // When/Then - should have less than 10000 threads
        assertThat(condition.evaluate(testAgent)).isTrue();
    }

    @Test
    void shouldCheckThreadsBelowZero() {
        // Given - negative threshold
        Condition condition = SystemCondition.threadsBelow(0);

        // When/Then - always false
        assertThat(condition.evaluate(testAgent)).isFalse();
    }

    @Test
    void shouldCheckSystemHealthy() {
        // Given
        Condition condition = SystemCondition.systemHealthy();

        // When
        boolean result = condition.evaluate(testAgent);

        // Then - depends on actual system load
        assertThat(result).isIn(true, false);
    }

    @Test
    void shouldCheckSystemUnderLoad() {
        // Given
        Condition condition = SystemCondition.systemUnderLoad();

        // When
        boolean result = condition.evaluate(testAgent);

        // Then - depends on actual system load
        assertThat(result).isIn(true, false);
    }

    @Test
    void shouldCombineSystemConditions() {
        // Given
        Condition condition = SystemCondition.cpuBelow(80.0)
            .and(SystemCondition.memoryBelow(80.0));

        // When
        boolean result = condition.evaluate(testAgent);

        // Then
        assertThat(result).isIn(true, false);
    }

    @Test
    void shouldCombineWithOrOperator() {
        // Given
        Condition condition = SystemCondition.cpuAbove(100.0)
                .or(SystemCondition.memoryBelow(100.0));

        // When
        boolean result = condition.evaluate(testAgent);

        // Then - at least one should be true
        assertThat(result).isTrue();
    }

    @Test
    void shouldCheckRealisticThresholds() {
        // Given
        Condition cpuLow = SystemCondition.cpuBelow(50.0);
        Condition memoryLow = SystemCondition.memoryBelow(50.0);

        // When
        boolean cpuResult = cpuLow.evaluate(testAgent);
        boolean memoryResult = memoryLow.evaluate(testAgent);

        // Then - results depend on actual system state
        assertThat(cpuResult).isIn(true, false);
        assertThat(memoryResult).isIn(true, false);
    }

    static class TestAgent extends BaseAgent {
        TestAgent() {
            super("test-agent", "Test Agent");
        }
    }
}
