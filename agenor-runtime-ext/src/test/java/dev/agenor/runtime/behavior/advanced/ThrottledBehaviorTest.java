package dev.agenor.runtime.behavior.advanced;

import dev.agenor.core.ratelimit.RateLimit;
import dev.agenor.runtime.agent.BaseAgent;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;

class ThrottledBehaviorTest {

    @Test
    void shouldThrottleExecutions() throws InterruptedException {
        // Given
        AtomicInteger count = new AtomicInteger(0);
        RateLimit limit = RateLimit.perSecond(3);

        ThrottledBehavior behavior = ThrottledBehavior.fromSkipping(limit, count::incrementAndGet);
        behavior.setAgent(new TestAgent());

        // When - try 10 executions rapidly
        for (int i = 0; i < 10; i++) {
            behavior.execute().join();
        }

        // Then - only 3 should succeed (burst capacity)
        assertThat(count.get()).isLessThanOrEqualTo(3);
        assertThat(behavior.getRejectedExecutions()).isGreaterThan(0);
    }

    static class TestAgent extends BaseAgent {
        TestAgent() { super("test", "Test"); }
    }
}
