package dev.agenor.runtime.condition;

import dev.agenor.core.condition.Condition;
import dev.agenor.core.condition.ConditionContext;
import dev.agenor.runtime.agent.BaseAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for ConditionEvaluator
 */
class ConditionEvaluatorTest {

    private ConditionEvaluator evaluator;
    private TestAgent testAgent;

    @BeforeEach
    void setUp() {
        evaluator = new ConditionEvaluator();
        testAgent = new TestAgent("test-agent", "Test Agent");
    }

    @Test
    void shouldEvaluateTrueCondition() {
        // Given
        Condition condition = agent -> true;

        // When
        boolean result = evaluator.evaluate(condition, testAgent);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void shouldEvaluateFalseCondition() {
        // Given
        Condition condition = agent -> false;

        // When
        boolean result = evaluator.evaluate(condition, testAgent);

        // Then
        assertThat(result).isFalse();
    }

    @Test
    void shouldReturnTrueForNullCondition() {
        // Given
        Condition condition = null;

        // When
        boolean result = evaluator.evaluate(condition, testAgent);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void shouldHandleExceptionInCondition() {
        // Given
        Condition condition = agent -> {
            throw new RuntimeException("Test exception");
        };

        // When
        boolean result = evaluator.evaluate(condition, testAgent);

        // Then
        assertThat(result).isFalse();
    }

    @Test
    void shouldEvaluateComplexCondition() {
        // Given
        Condition condition = agent ->
            agent.getAgentId().equals("test-agent") && agent.isRunning();

        testAgent.start();

        // When
        boolean result = evaluator.evaluate(condition, testAgent);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void shouldUseDefaultContext() {
        // Given
        ConditionEvaluator defaultEvaluator = new ConditionEvaluator();

        // When
        ConditionContext context = defaultEvaluator.getContext();

        // Then
        assertThat(context).isNotNull();
    }

    @Test
    void shouldUseProvidedContext() {
        // Given
        ConditionContext customContext = new ConditionContext();
        customContext.set("key", "value");

        ConditionEvaluator customEvaluator = new ConditionEvaluator(customContext);

        // When
        ConditionContext context = customEvaluator.getContext();

        // Then
        assertThat(context).isEqualTo(customContext);
        assertThat(context.get("key", String.class)).isEqualTo("value");
    }

    @Test
    void shouldEvaluateMultipleConditionsSequentially() {
        // Given
        Condition condition1 = agent -> agent.getAgentId().startsWith("test");
        Condition condition2 = agent -> agent.getAgentName().contains("Test");
        Condition condition3 = agent -> true;

        // When
        boolean result1 = evaluator.evaluate(condition1, testAgent);
        boolean result2 = evaluator.evaluate(condition2, testAgent);
        boolean result3 = evaluator.evaluate(condition3, testAgent);

        // Then
        assertThat(result1).isTrue();
        assertThat(result2).isTrue();
        assertThat(result3).isTrue();
    }

    @Test
    void shouldHandleNullPointerExceptionInCondition() {
        // Given
        Condition condition = agent -> {
            String nullString = null;
            return nullString.isEmpty();
        };

        // When
        boolean result = evaluator.evaluate(condition, testAgent);

        // Then
        assertThat(result).isFalse();
    }

    static class TestAgent extends BaseAgent {
        TestAgent(String id, String name) {
            super(id, name);
        }
    }
}
