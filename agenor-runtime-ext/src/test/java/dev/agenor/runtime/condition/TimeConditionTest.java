package dev.agenor.runtime.condition;

import dev.agenor.core.Agent;
import dev.agenor.core.condition.Condition;
import dev.agenor.runtime.agent.BaseAgent;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

import java.time.DayOfWeek;
import java.time.LocalDateTime;

/**
 * Unit tests for TimeCondition pre-built conditions
 */
class TimeConditionTest {

    private final Agent testAgent = new TestAgent();

    @Test
    void shouldCheckBusinessHours() {
        // Given
        Condition condition = TimeCondition.businessHours();

        // When
        boolean result = condition.evaluate(testAgent);

        // Then - depends on current time
        int currentHour = LocalDateTime.now().getHour();
        boolean expected = currentHour >= 9 && currentHour < 17;
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void shouldCheckWeekday() {
        // Given
        Condition condition = TimeCondition.weekday();

        // When
        boolean result = condition.evaluate(testAgent);

        // Then - depends on current day
        DayOfWeek day = LocalDateTime.now().getDayOfWeek();
        boolean expected = day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void shouldCheckWeekend() {
        // Given
        Condition condition = TimeCondition.weekend();

        // When
        boolean result = condition.evaluate(testAgent);

        // Then - depends on current day
        DayOfWeek day = LocalDateTime.now().getDayOfWeek();
        boolean expected = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void shouldCheckAfterHour() {
        // Given
        Condition condition = TimeCondition.afterHour(0); // After midnight

        // When/Then
        assertThat(condition.evaluate(testAgent)).isTrue();
    }

    @Test
    void shouldCheckBeforeHour() {
        // Given
        Condition condition = TimeCondition.beforeHour(24);

        // When/Then
        assertThat(condition.evaluate(testAgent)).isTrue();
    }

    @Test
    void shouldCheckBetweenHours() {
        // Given
        Condition condition = TimeCondition.betweenHours(0, 24);

        // When/Then
        assertThat(condition.evaluate(testAgent)).isTrue();
    }

    static class TestAgent extends BaseAgent {
        TestAgent() {
            super("test-agent", "Test Agent");
        }
    }
}
