package dev.agenor.runtime.lifecycle;

import dev.agenor.core.AgentStatus;
import dev.agenor.runtime.agent.BaseAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit tests for LifecycleManager
 */
class LifecycleManagerTest {

    private LifecycleManager lifecycleManager;

    @BeforeEach
    void setUp() {
        lifecycleManager = new LifecycleManager();
    }

    @Test
    void shouldTrackAgentStatusDuringStartup() throws InterruptedException {
        // Given
        // Use a latch-based agent to hold onStart() until we have observed STARTING.
        // Without this, the FJP thread can complete start() before the test thread
        // reaches the assertion, making the STARTING check inherently racy.
        CountDownLatch startingObserved = new CountDownLatch(1); // released by test after checking STARTING
        CountDownLatch startFutures = new CountDownLatch(2);     // STARTING + RUNNING

        LatchAgent agent = new LatchAgent("test-agent", "Test Agent", startingObserved);

        lifecycleManager.addLifecycleListener((agentId, oldStatus, newStatus) -> {
            startFutures.countDown();
        });

        // When
        CompletableFuture<Void> startFuture = lifecycleManager.startAgent(agent, Duration.ofSeconds(5));

        // Then - STARTING is set synchronously by LifecycleManager before agent.start() executes
        assertThat(lifecycleManager.getAgentStatus("test-agent")).isEqualTo(AgentStatus.STARTING);

        // Allow the agent's onStart() to proceed
        startingObserved.countDown();

        startFuture.join();

        // Should end up in RUNNING state
        assertThat(lifecycleManager.getAgentStatus("test-agent")).isEqualTo(AgentStatus.RUNNING);
        assertThat(agent.isRunning()).isTrue();

        // Should have received both status changes (STARTING + RUNNING)
        assertThat(startFutures.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void shouldTrackAgentStatusDuringShutdown() {
        // Given
        CountDownLatch stoppingObserved = new CountDownLatch(1); // released by test after checking STOPPING
        StoppingLatchAgent agent = new StoppingLatchAgent("test-agent", "Test Agent", stoppingObserved);

        // Start an agent first
        lifecycleManager.startAgent(agent, Duration.ofSeconds(5)).join();
        assertThat(lifecycleManager.getAgentStatus("test-agent")).isEqualTo(AgentStatus.RUNNING);

        // When
        CompletableFuture<Void> stopFuture = lifecycleManager.stopAgent(agent, Duration.ofSeconds(5));

        // Then - STOPPING is set synchronously by LifecycleManager before agent.stop() executes
        assertThat(lifecycleManager.getAgentStatus("test-agent")).isEqualTo(AgentStatus.STOPPING);

        // Allow the agent's onStop() to proceed
        stoppingObserved.countDown();

        stopFuture.join();

        // Should end up in a STOPPED state
        assertThat(lifecycleManager.getAgentStatus("test-agent")).isEqualTo(AgentStatus.STOPPED);
        assertThat(agent.isRunning()).isFalse();
    }

    @Test
    void shouldHandleStartupTimeout() {
        // Given
        SlowStartAgent slowAgent = new SlowStartAgent("slow-agent", Duration.ofSeconds(10));
        Duration shortTimeout = Duration.ofMillis(500);

        // When
        CompletableFuture<Void> startFuture = lifecycleManager.startAgent(slowAgent, shortTimeout);

        // Then
        assertThatThrownBy(startFuture::join)
                .isInstanceOf(RuntimeException.class)
                .hasRootCauseInstanceOf(LifecycleException.class)
                .hasMessageContaining("timeout");

        assertThat(lifecycleManager.getAgentStatus("slow-agent")).isEqualTo(AgentStatus.ERROR);
    }

    @Test
    void shouldHandleStartupFailure() {
        // Given
        FailingAgent failingAgent = new FailingAgent("failing-agent", "Startup failure");

        // When
        CompletableFuture<Void> startFuture = lifecycleManager.startAgent(failingAgent, Duration.ofSeconds(5));

        // Then
        assertThatThrownBy(startFuture::join)
                .isInstanceOf(RuntimeException.class)
                .hasRootCauseInstanceOf(LifecycleException.class);

        assertThat(lifecycleManager.getAgentStatus("failing-agent")).isEqualTo(AgentStatus.CRASHED);
    }

    @Test
    void shouldHandleShutdownTimeout() {
        // Given
        SlowStopAgent slowAgent = new SlowStopAgent("slow-stop-agent", Duration.ofSeconds(10));
        Duration shortTimeout = Duration.ofMillis(500);

        // Start an agent first
        lifecycleManager.startAgent(slowAgent, Duration.ofSeconds(5)).join();

        // When
        CompletableFuture<Void> stopFuture = lifecycleManager.stopAgent(slowAgent, shortTimeout);

        // Then
        assertThatThrownBy(stopFuture::join)
                .isInstanceOf(RuntimeException.class)
                .hasRootCauseInstanceOf(LifecycleException.class)
                .hasMessageContaining("timeout");

        assertThat(lifecycleManager.getAgentStatus("slow-stop-agent")).isEqualTo(AgentStatus.ERROR);
    }

    @Test
    void shouldResetAgentFromErrorState() {
        // Given
        FailingAgent failingAgent = new FailingAgent("failing-agent", "Test failure");

        // Cause agent to fail
        assertThatThrownBy(() ->
                lifecycleManager.startAgent(failingAgent, Duration.ofSeconds(5)).join());
        assertThat(lifecycleManager.getAgentStatus("failing-agent")).isEqualTo(AgentStatus.CRASHED);
        assertThat(lifecycleManager.isInErrorState("failing-agent")).isTrue();

        // When
        lifecycleManager.resetAgent("failing-agent");

        // Then
        assertThat(lifecycleManager.getAgentStatus("failing-agent")).isEqualTo(AgentStatus.STOPPED);
        assertThat(lifecycleManager.isInErrorState("failing-agent")).isFalse();
    }

    @Test
    void shouldNotifyLifecycleListeners() throws InterruptedException {
        // Given
        TestAgent agent = new TestAgent("test-agent", "Test Agent");
        CountDownLatch latch = new CountDownLatch(2); // STARTING + RUNNING
        AtomicReference<String> lastAgentId = new AtomicReference<>();
        AtomicReference<AgentStatus> lastNewStatus = new AtomicReference<>();

        LifecycleListener listener = (agentId, oldStatus, newStatus) -> {
            lastAgentId.set(agentId);
            lastNewStatus.set(newStatus);
            latch.countDown();
        };

        lifecycleManager.addLifecycleListener(listener);

        // When
        lifecycleManager.startAgent(agent, Duration.ofSeconds(5)).join();

        // Then
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(lastAgentId.get()).isEqualTo("test-agent");
        assertThat(lastNewStatus.get()).isEqualTo(AgentStatus.RUNNING);
    }

    @Test
    void shouldGetAllAgentStates() {
        // Given
        TestAgent agent1 = new TestAgent("agent-1", "Agent 1");
        TestAgent agent2 = new TestAgent("agent-2", "Agent 2");

        lifecycleManager.startAgent(agent1, Duration.ofSeconds(5)).join();
        lifecycleManager.startAgent(agent2, Duration.ofSeconds(5)).join();

        // When
        var allStates = lifecycleManager.getAllAgentStates();

        // Then
        assertThat(allStates).hasSize(2)
                .containsEntry("agent-1", AgentStatus.RUNNING)
                .containsEntry("agent-2", AgentStatus.RUNNING);
    }

    @Test
    void shouldReturnUnknownForNonExistentAgent() {
        // When
        AgentStatus status = lifecycleManager.getAgentStatus("non-existent");

        // Then
        assertThat(status).isEqualTo(AgentStatus.UNKNOWN);
        assertThat(lifecycleManager.isInErrorState("non-existent")).isFalse();
    }

    @Test
    void shouldIgnoreResetForNonErrorStates() {
        // Given
        TestAgent agent = new TestAgent("test-agent", "Test Agent");
        lifecycleManager.startAgent(agent, Duration.ofSeconds(5)).join();
        assertThat(lifecycleManager.getAgentStatus("test-agent")).isEqualTo(AgentStatus.RUNNING);

        // When
        lifecycleManager.resetAgent("test-agent"); // Should be ignored

        // Then
        assertThat(lifecycleManager.getAgentStatus("test-agent")).isEqualTo(AgentStatus.RUNNING);
    }

    // ========== TEST AGENT IMPLEMENTATIONS ==========

    /**
     * Basic test agent
     */
    static class TestAgent extends BaseAgent {
        TestAgent(String agentId, String agentName) {
            super(agentId, agentName);
        }
    }

    /**
     * Agent that blocks in onStart() until the provided latch is released.
     * Used to reliably observe the STARTING state in tests.
     */
    static class LatchAgent extends BaseAgent {
        private final CountDownLatch proceedLatch;

        LatchAgent(String agentId, String agentName, CountDownLatch proceedLatch) {
            super(agentId, agentName);
            this.proceedLatch = proceedLatch;
        }

        @Override
        protected void onStart() {
            try {
                if (!proceedLatch.await(10, TimeUnit.SECONDS)) {
                    throw new RuntimeException("LatchAgent: timed out waiting for proceed latch");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("LatchAgent: interrupted during onStart", e);
            }
        }
    }

    /**
     * Agent that takes a long time to start
     */
    static class SlowStartAgent extends BaseAgent {
        private final Duration startDelay;

        SlowStartAgent(String agentId, Duration startDelay) {
            super(agentId, agentId);
            this.startDelay = startDelay;
        }

        @Override
        protected void onStart() {
            try {
                Thread.sleep(startDelay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted during slow start", e);
            }
        }
    }

    /**
     * Agent that takes a long time to stop
     */
    static class SlowStopAgent extends BaseAgent {
        private final Duration stopDelay;

        SlowStopAgent(String agentId, Duration stopDelay) {
            super(agentId, agentId);
            this.stopDelay = stopDelay;
        }

        @Override
        protected void onStop() {
            try {
                Thread.sleep(stopDelay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted during slow stop", e);
            }
        }
    }

    /**
     * Agent that blocks in onStop() until the provided latch is released.
     * Used to reliably observe the STOPPING state in tests.
     */
    static class StoppingLatchAgent extends BaseAgent {
        private final CountDownLatch proceedLatch;

        StoppingLatchAgent(String agentId, String agentName, CountDownLatch proceedLatch) {
            super(agentId, agentName);
            this.proceedLatch = proceedLatch;
        }

        @Override
        protected void onStop() {
            try {
                if (!proceedLatch.await(10, TimeUnit.SECONDS)) {
                    throw new RuntimeException("StoppingLatchAgent: timed out waiting for proceed latch");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("StoppingLatchAgent: interrupted during onStop", e);
            }
        }
    }

    /**
     * Agent that always fails to start
     */
    static class FailingAgent extends BaseAgent {
        private final String errorMessage;

        FailingAgent(String agentId, String errorMessage) {
            super(agentId, agentId);
            this.errorMessage = errorMessage;
        }

        @Override
        protected void onStart() {
            throw new RuntimeException(errorMessage);
        }
    }
}
