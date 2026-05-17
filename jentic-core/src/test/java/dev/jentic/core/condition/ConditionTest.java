package dev.jentic.core.condition;

import dev.jentic.core.Agent;
import dev.jentic.core.Behavior;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive test coverage for Condition interface (jentic-core module)
 */
@DisplayName("Condition Interface Tests")
class ConditionTest {

    private final Agent testAgent = new TestAgent();

    // === Static Factory Methods ===

    @Test
    @DisplayName("always() should always return true")
    void alwaysShouldAlwaysReturnTrue() {
        // Given
        Condition condition = Condition.always();

        // When/Then
        assertThat(condition.evaluate(testAgent)).isTrue();
        assertThat(condition.evaluate(null)).isTrue(); // Even with null agent
    }

    @Test
    @DisplayName("never() should always return false")
    void neverShouldAlwaysReturnFalse() {
        // Given
        Condition condition = Condition.never();

        // When/Then
        assertThat(condition.evaluate(testAgent)).isFalse();
        assertThat(condition.evaluate(null)).isFalse(); // Even with null agent
    }

    // === AND Combinator ===

    @Test
    @DisplayName("and() should return true when both conditions are true")
    void andShouldReturnTrueWhenBothTrue() {
        // Given
        Condition trueCondition1 = agent -> true;
        Condition trueCondition2 = agent -> true;

        // When
        Condition combined = trueCondition1.and(trueCondition2);

        // Then
        assertThat(combined.evaluate(testAgent)).isTrue();
    }

    @Test
    @DisplayName("and() should return false when first condition is false")
    void andShouldReturnFalseWhenFirstFalse() {
        // Given
        Condition falseCondition = agent -> false;
        Condition trueCondition = agent -> true;

        // When
        Condition combined = falseCondition.and(trueCondition);

        // Then
        assertThat(combined.evaluate(testAgent)).isFalse();
    }

    @Test
    @DisplayName("and() should return false when second condition is false")
    void andShouldReturnFalseWhenSecondFalse() {
        // Given
        Condition trueCondition = agent -> true;
        Condition falseCondition = agent -> false;

        // When
        Condition combined = trueCondition.and(falseCondition);

        // Then
        assertThat(combined.evaluate(testAgent)).isFalse();
    }

    @Test
    @DisplayName("and() should short-circuit when first condition is false")
    void andShouldShortCircuitWhenFirstFalse() {
        // Given
        AtomicInteger counter = new AtomicInteger(0);
        Condition falseCondition = agent -> false;
        Condition counterCondition = agent -> {
            counter.incrementAndGet();
            return true;
        };

        // When
        Condition combined = falseCondition.and(counterCondition);
        combined.evaluate(testAgent);

        // Then
        assertThat(counter.get()).isEqualTo(0); // Second condition not evaluated
    }

    @Test
    @DisplayName("and() should evaluate second condition when first is true")
    void andShouldEvaluateSecondWhenFirstTrue() {
        // Given
        AtomicInteger counter = new AtomicInteger(0);
        Condition trueCondition = agent -> true;
        Condition counterCondition = agent -> {
            counter.incrementAndGet();
            return true;
        };

        // When
        Condition combined = trueCondition.and(counterCondition);
        combined.evaluate(testAgent);

        // Then
        assertThat(counter.get()).isEqualTo(1); // Second condition evaluated
    }

    @Test
    @DisplayName("and() should chain multiple conditions")
    void andShouldChainMultipleConditions() {
        // Given
        Condition c1 = agent -> true;
        Condition c2 = agent -> true;
        Condition c3 = agent -> true;
        Condition c4 = agent -> false;

        // When
        boolean allTrue = c1.and(c2).and(c3).evaluate(testAgent);
        boolean oneFalse = c1.and(c2).and(c3).and(c4).evaluate(testAgent);

        // Then
        assertThat(allTrue).isTrue();
        assertThat(oneFalse).isFalse();
    }

    // === OR Combinator ===

    @Test
    @DisplayName("or() should return true when first condition is true")
    void orShouldReturnTrueWhenFirstTrue() {
        // Given
        Condition trueCondition = agent -> true;
        Condition falseCondition = agent -> false;

        // When
        Condition combined = trueCondition.or(falseCondition);

        // Then
        assertThat(combined.evaluate(testAgent)).isTrue();
    }

    @Test
    @DisplayName("or() should return true when second condition is true")
    void orShouldReturnTrueWhenSecondTrue() {
        // Given
        Condition falseCondition = agent -> false;
        Condition trueCondition = agent -> true;

        // When
        Condition combined = falseCondition.or(trueCondition);

        // Then
        assertThat(combined.evaluate(testAgent)).isTrue();
    }

    @Test
    @DisplayName("or() should return false when both conditions are false")
    void orShouldReturnFalseWhenBothFalse() {
        // Given
        Condition falseCondition1 = agent -> false;
        Condition falseCondition2 = agent -> false;

        // When
        Condition combined = falseCondition1.or(falseCondition2);

        // Then
        assertThat(combined.evaluate(testAgent)).isFalse();
    }

    @Test
    @DisplayName("or() should short-circuit when first condition is true")
    void orShouldShortCircuitWhenFirstTrue() {
        // Given
        AtomicInteger counter = new AtomicInteger(0);
        Condition trueCondition = agent -> true;
        Condition counterCondition = agent -> {
            counter.incrementAndGet();
            return false;
        };

        // When
        Condition combined = trueCondition.or(counterCondition);
        combined.evaluate(testAgent);

        // Then
        assertThat(counter.get()).isEqualTo(0); // Second condition not evaluated
    }

    @Test
    @DisplayName("or() should evaluate second condition when first is false")
    void orShouldEvaluateSecondWhenFirstFalse() {
        // Given
        AtomicInteger counter = new AtomicInteger(0);
        Condition falseCondition = agent -> false;
        Condition counterCondition = agent -> {
            counter.incrementAndGet();
            return true;
        };

        // When
        Condition combined = falseCondition.or(counterCondition);
        combined.evaluate(testAgent);

        // Then
        assertThat(counter.get()).isEqualTo(1); // Second condition evaluated
    }

    @Test
    @DisplayName("or() should chain multiple conditions")
    void orShouldChainMultipleConditions() {
        // Given
        Condition c1 = agent -> false;
        Condition c2 = agent -> false;
        Condition c3 = agent -> true;
        Condition c4 = agent -> false;

        // When
        boolean hasTrue = c1.or(c2).or(c3).or(c4).evaluate(testAgent);
        boolean allFalse = c1.or(c2).or(c4).evaluate(testAgent);

        // Then
        assertThat(hasTrue).isTrue();
        assertThat(allFalse).isFalse();
    }

    // === NOT (Negate) ===

    @Test
    @DisplayName("negate() should invert true to false")
    void negateShouldInvertTrueToFalse() {
        // Given
        Condition trueCondition = agent -> true;

        // When
        Condition negated = trueCondition.negate();

        // Then
        assertThat(negated.evaluate(testAgent)).isFalse();
    }

    @Test
    @DisplayName("negate() should invert false to true")
    void negateShouldInvertFalseToTrue() {
        // Given
        Condition falseCondition = agent -> false;

        // When
        Condition negated = falseCondition.negate();

        // Then
        assertThat(negated.evaluate(testAgent)).isTrue();
    }

    @Test
    @DisplayName("double negation should return original value")
    void doubleNegationShouldReturnOriginal() {
        // Given
        Condition trueCondition = agent -> true;
        Condition falseCondition = agent -> false;

        // When
        boolean doubleNegatedTrue = trueCondition.negate().negate().evaluate(testAgent);
        boolean doubleNegatedFalse = falseCondition.negate().negate().evaluate(testAgent);

        // Then
        assertThat(doubleNegatedTrue).isTrue();
        assertThat(doubleNegatedFalse).isFalse();
    }

    // === Complex Combinations ===

    @Test
    @DisplayName("should combine and() and or() correctly")
    void shouldCombineAndOrCorrectly() {
        // Given
        Condition a = agent -> true;
        Condition b = agent -> false;
        Condition c = agent -> true;

        // When - (A AND B) OR C
        boolean result1 = a.and(b).or(c).evaluate(testAgent);

        // When - A AND (B OR C)
        boolean result2 = a.and(b.or(c)).evaluate(testAgent);

        // Then
        assertThat(result1).isTrue();  // (true AND false) OR true = true
        assertThat(result2).isTrue();  // true AND (false OR true) = true
    }

    @Test
    @DisplayName("should support De Morgan's law - NOT(A AND B) = NOT(A) OR NOT(B)")
    void shouldSupportDeMorgansLawAnd() {
        // Given
        Condition a = agent -> true;
        Condition b = agent -> false;

        // When
        boolean notAndResult = a.and(b).negate().evaluate(testAgent);
        boolean notAorNotB = a.negate().or(b.negate()).evaluate(testAgent);

        // Then
        assertThat(notAndResult).isEqualTo(notAorNotB);
    }

    @Test
    @DisplayName("should support De Morgan's law - NOT(A OR B) = NOT(A) AND NOT(B)")
    void shouldSupportDeMorgansLawOr() {
        // Given
        Condition a = agent -> true;
        Condition b = agent -> false;

        // When
        boolean notOrResult = a.or(b).negate().evaluate(testAgent);
        boolean notAandNotB = a.negate().and(b.negate()).evaluate(testAgent);

        // Then
        assertThat(notOrResult).isEqualTo(notAandNotB);
    }

    // === Integration with always() and never() ===

    @Test
    @DisplayName("always().and(condition) should equal condition")
    void alwaysAndConditionEqualsCondition() {
        // Given
        Condition trueCondition = agent -> true;
        Condition falseCondition = agent -> false;

        // When/Then
        assertThat(Condition.always().and(trueCondition).evaluate(testAgent)).isTrue();
        assertThat(Condition.always().and(falseCondition).evaluate(testAgent)).isFalse();
    }

    @Test
    @DisplayName("never().or(condition) should equal condition")
    void neverOrConditionEqualsCondition() {
        // Given
        Condition trueCondition = agent -> true;
        Condition falseCondition = agent -> false;

        // When/Then
        assertThat(Condition.never().or(trueCondition).evaluate(testAgent)).isTrue();
        assertThat(Condition.never().or(falseCondition).evaluate(testAgent)).isFalse();
    }

    @Test
    @DisplayName("always().negate() should equal never()")
    void alwaysNegateEqualsNever() {
        // When
        boolean alwaysNegated = Condition.always().negate().evaluate(testAgent);
        boolean never = Condition.never().evaluate(testAgent);

        // Then
        assertThat(alwaysNegated).isEqualTo(never);
    }

    @Test
    @DisplayName("never().negate() should equal always()")
    void neverNegateEqualsAlways() {
        // When
        boolean neverNegated = Condition.never().negate().evaluate(testAgent);
        boolean always = Condition.always().evaluate(testAgent);

        // Then
        assertThat(neverNegated).isEqualTo(always);
    }

    // === Agent State Access ===

    @Test
    @DisplayName("should access agent ID")
    void shouldAccessAgentId() {
        // Given
        Condition checkAgentId = agent -> "test-agent".equals(agent.getAgentId());

        // When/Then
        assertThat(checkAgentId.evaluate(testAgent)).isTrue();
    }

    @Test
    @DisplayName("should access agent name")
    void shouldAccessAgentName() {
        // Given
        Condition checkAgentName = agent -> "Test Agent".equals(agent.getAgentName());

        // When/Then
        assertThat(checkAgentName.evaluate(testAgent)).isTrue();
    }

    @Test
    @DisplayName("should access agent running state")
    void shouldAccessAgentRunningState() {
        // Given
        Condition checkRunning = Agent::isRunning;
        TestAgent agent = new TestAgent();

        // When
        agent.setRunning(true);
        boolean runningResult = checkRunning.evaluate(agent);

        agent.setRunning(false);
        boolean stoppedResult = checkRunning.evaluate(agent);

        // Then
        assertThat(runningResult).isTrue();
        assertThat(stoppedResult).isFalse();
    }

    // === Exception Handling ===

    @Test
    @DisplayName("should propagate exception from condition")
    void shouldPropagateExceptionFromCondition() {
        // Given
        Condition throwingCondition = agent -> {
            throw new RuntimeException("Test exception");
        };

        // When/Then
        assertThatThrownBy(() -> throwingCondition.evaluate(testAgent))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Test exception");
    }

    @Test
    @DisplayName("exception in first AND condition should not evaluate second")
    void exceptionInFirstAndShouldNotEvaluateSecond() {
        // Given
        AtomicInteger counter = new AtomicInteger(0);
        Condition throwingCondition = agent -> {
            throw new RuntimeException("Test exception");
        };
        Condition counterCondition = agent -> {
            counter.incrementAndGet();
            return true;
        };

        // When/Then
        assertThatThrownBy(() ->
                throwingCondition.and(counterCondition).evaluate(testAgent)
        ).isInstanceOf(RuntimeException.class);

        assertThat(counter.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("exception in first OR condition should not evaluate second")
    void exceptionInFirstOrShouldNotEvaluateSecond() {
        // Given
        AtomicInteger counter = new AtomicInteger(0);
        Condition throwingCondition = agent -> {
            throw new RuntimeException("Test exception");
        };
        Condition counterCondition = agent -> {
            counter.incrementAndGet();
            return true;
        };

        // When/Then
        assertThatThrownBy(() ->
                throwingCondition.or(counterCondition).evaluate(testAgent)
        ).isInstanceOf(RuntimeException.class);

        assertThat(counter.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("negate() should propagate exceptions")
    void negateShouldPropagateExceptions() {
        // Given
        Condition throwingCondition = agent -> {
            throw new RuntimeException("Test exception");
        };

        // When/Then
        assertThatThrownBy(() ->
                throwingCondition.negate().evaluate(testAgent)
        ).isInstanceOf(RuntimeException.class)
                .hasMessage("Test exception");
    }

    // === Minimal Agent Implementation (no runtime dependencies) ===

    static class TestAgent implements Agent {
        private final AtomicBoolean running = new AtomicBoolean(false);

        @Override
        public String getAgentId() {
            return "test-agent";
        }

        @Override
        public String getAgentName() {
            return "Test Agent";
        }

        @Override
        public CompletableFuture<Void> start() {
            running.set(true);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> stop() {
            running.set(false);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public boolean isRunning() {
            return running.get();
        }

        public void setRunning(boolean running) {
            this.running.set(running);
        }

        @Override
        public void addBehavior(Behavior behavior) {
            // No-op for testing
        }

        @Override
        public void removeBehavior(String behaviorId) {
            // No-op for testing
        }

        @Override
        public dev.jentic.core.messaging.MessageDispatcher getMessageDispatcher() {
            return null;
        }
    }
}