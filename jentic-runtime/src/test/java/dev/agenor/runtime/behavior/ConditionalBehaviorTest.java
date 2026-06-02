package dev.agenor.runtime.behavior;

import dev.agenor.core.condition.Condition;
import dev.agenor.core.telemetry.AgenorTelemetry;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.behavior.advanced.ConditionalBehavior;
import dev.agenor.runtime.directory.InMemoryAgentDirectory;
import dev.agenor.runtime.messaging.InMemoryMessageDispatcher;
import dev.agenor.runtime.scheduler.SimpleBehaviorScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for ConditionalBehavior
 */
class ConditionalBehaviorTest {

    private TestAgent agent;
    private SimpleBehaviorScheduler scheduler;

    @BeforeEach
    void setUp() {
        agent = new TestAgent();
        agent.setMessageDispatcher(new InMemoryMessageDispatcher(new InMemoryAgentDirectory(), AgenorTelemetry.noop()));
        agent.setAgentDirectory(new InMemoryAgentDirectory());

        scheduler = new SimpleBehaviorScheduler();
        scheduler.start().join();
        agent.setBehaviorScheduler(scheduler);
    }

    @Test
    void shouldExecuteWhenConditionIsTrue() throws InterruptedException {
        // Given
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger executionCount = new AtomicInteger(0);

        Condition alwaysTrue = Condition.always();
        ConditionalBehavior behavior = ConditionalBehavior.from(alwaysTrue, () -> {
            executionCount.incrementAndGet();
            latch.countDown();
        });
        behavior.setAgent(agent);

        // When
        behavior.execute().join();

        // Then
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(executionCount.get()).isEqualTo(1);
        assertThat(behavior.getSuccessfulExecutions()).isEqualTo(1);
        assertThat(behavior.getSkippedExecutions()).isEqualTo(0);
    }

    @Test
    void shouldSkipExecutionWhenConditionIsFalse() throws InterruptedException {
        // Given
        AtomicInteger executionCount = new AtomicInteger(0);

        Condition alwaysFalse = Condition.never();
        ConditionalBehavior behavior = ConditionalBehavior.from(alwaysFalse, () -> {
            executionCount.incrementAndGet();
        });
        behavior.setAgent(agent);

        // When
        behavior.execute().join();

        // Give it time to potentially execute
        Thread.sleep(500);

        // Then
        assertThat(executionCount.get()).isEqualTo(0);
        assertThat(behavior.getSuccessfulExecutions()).isEqualTo(0);
        assertThat(behavior.getSkippedExecutions()).isEqualTo(1);
    }

    @Test
    void shouldTrackSatisfactionRate() {
        // Given
        AtomicInteger counter = new AtomicInteger(0);

        // Condition that alternates true/false
        Condition alternating = agent -> counter.incrementAndGet() % 2 == 0;

        ConditionalBehavior behavior = ConditionalBehavior.from(alternating, () -> {});
        behavior.setAgent(agent);

        // When
        for (int i = 0; i < 10; i++) {
            behavior.execute().join();
        }

        // Then
        assertThat(behavior.getSuccessfulExecutions()).isEqualTo(5);
        assertThat(behavior.getSkippedExecutions()).isEqualTo(5);
        assertThat(behavior.getSatisfactionRate()).isEqualTo(0.5);
    }

    @Test
    void shouldExecuteCyclicConditionalBehavior() throws InterruptedException {
        // Given
        CountDownLatch latch = new CountDownLatch(3);
        AtomicInteger executionCount = new AtomicInteger(0);

        Condition alwaysTrue = Condition.always();
        ConditionalBehavior behavior = ConditionalBehavior.cyclic(
            alwaysTrue,
            Duration.ofMillis(100),
            () -> {
                executionCount.incrementAndGet();
                latch.countDown();
            }
        );
        behavior.setAgent(agent);

        // When
        scheduler.schedule(behavior).join();

        // Then
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(executionCount.get()).isGreaterThanOrEqualTo(3);

        // Cleanup
        behavior.stop();
    }

    @Test
    void shouldHandleConditionEvaluationError() {
        // Given
        Condition errorCondition = agent -> {
            throw new RuntimeException("Condition evaluation error");
        };

        AtomicInteger executionCount = new AtomicInteger(0);
        ConditionalBehavior behavior = ConditionalBehavior.from(errorCondition, () -> {
            executionCount.incrementAndGet();
        });
        behavior.setAgent(agent);

        // When
        behavior.execute().join();

        // Then - should not execute action on condition error
        assertThat(executionCount.get()).isEqualTo(0);
        assertThat(behavior.getSkippedExecutions()).isEqualTo(1);
    }

    static class TestAgent extends BaseAgent {
        TestAgent() {
            super("test-agent", "Test Agent");
        }
    }
}
