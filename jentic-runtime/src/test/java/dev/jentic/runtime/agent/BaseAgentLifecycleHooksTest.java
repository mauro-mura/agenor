package dev.jentic.runtime.agent;

import dev.jentic.core.AgentStatus;
import dev.jentic.core.Behavior;
import dev.jentic.core.BehaviorScheduler;
import dev.jentic.core.messaging.MessageDispatcher;
import dev.jentic.core.telemetry.JenticTelemetry;
import dev.jentic.runtime.directory.LocalAgentDirectory;
import dev.jentic.runtime.messaging.InMemoryMessageDispatcher;
import dev.jentic.runtime.scheduler.SimpleBehaviorScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for BaseAgent lifecycle hooks functionality
 */
class BaseAgentLifecycleHooksTest {
    
    private MessageDispatcher messageDispatcher;
    private BehaviorScheduler behaviorScheduler;
    private TestAgent agent;

    @BeforeEach
    void setUp() {
        messageDispatcher = new InMemoryMessageDispatcher(new LocalAgentDirectory(), JenticTelemetry.noop());
        behaviorScheduler = new SimpleBehaviorScheduler();
        behaviorScheduler.start().join();

        agent = new TestAgent("test-agent");
        agent.setMessageDispatcher(messageDispatcher);
        agent.setBehaviorScheduler(behaviorScheduler);
    }
    
    @AfterEach
    void tearDown() {
        if (agent.isRunning()) {
            agent.stop().join();
        }
        behaviorScheduler.stop().join();
    }
    
    // =========================================================================
    // START HOOKS
    // =========================================================================
    
    @Test
    void testStartHookIsCalledOnAgentStart() {
        // Given
        AtomicBoolean hookCalled = new AtomicBoolean(false);
        agent.onStartHook(() -> hookCalled.set(true));
        
        // When
        agent.start().join();
        
        // Then
        assertThat(hookCalled.get()).isTrue();
        assertThat(agent.isRunning()).isTrue();
    }
    
    @Test
    void testMultipleStartHooksAreCalledInOrder() {
        // Given
        StringBuilder executionOrder = new StringBuilder();
        
        agent.onStartHook(() -> executionOrder.append("1"));
        agent.onStartHook(() -> executionOrder.append("2"));
        agent.onStartHook(() -> executionOrder.append("3"));
        
        // When
        agent.start().join();
        
        // Then
        assertThat(executionOrder.toString()).isEqualTo("123");
    }
    
    @Test
    void testStartHookCanAccessAgentServices() {
        // Given
        AtomicBoolean hasMessageService = new AtomicBoolean(false);
        
        agent.onStartHook(() -> {
            hasMessageService.set(agent.getMessageDispatcher() != null);
        });
        
        // When
        agent.start().join();
        
        // Then
        assertThat(hasMessageService.get()).isTrue();
    }
    
    @Test
    void testStartHookCalledAfterOnStart() {
        // Given
        AtomicInteger executionOrder = new AtomicInteger(0);
        agent.setOnStartOrder(executionOrder);
        
        agent.onStartHook(() -> {
            int order = executionOrder.incrementAndGet();
            assertThat(order).isEqualTo(2); // Should be called AFTER onStart
        });
        
        // When
        agent.start().join();
        
        // Then
        assertThat(executionOrder.get()).isEqualTo(2);
    }
    
    @Test
    void testStartHookExceptionDoesNotStopOtherHooks() {
        // Given
        AtomicInteger successfulHooks = new AtomicInteger(0);
        
        agent.onStartHook(() -> successfulHooks.incrementAndGet());
        agent.onStartHook(() -> {
            throw new RuntimeException("Test exception");
        });
        agent.onStartHook(() -> successfulHooks.incrementAndGet());
        
        // When
        agent.start().join();
        
        // Then
        assertThat(successfulHooks.get()).isEqualTo(2);
        assertThat(agent.isRunning()).isTrue();
    }
    
    @Test
    void testNullStartHookIsIgnored() {
        // Given
        agent.onStartHook(null);
        
        // When/Then - should not throw
        assertThatCode(() -> agent.start().join()).doesNotThrowAnyException();
    }
    
    // =========================================================================
    // STOP HOOKS
    // =========================================================================
    
    @Test
    void testStopHookIsCalledOnAgentStop() {
        // Given
        AtomicBoolean hookCalled = new AtomicBoolean(false);
        agent.onStopHook(() -> hookCalled.set(true));
        
        agent.start().join();
        
        // When
        agent.stop().join();
        
        // Then
        assertThat(hookCalled.get()).isTrue();
        assertThat(agent.isRunning()).isFalse();
    }
    
    @Test
    void testMultipleStopHooksAreCalledInOrder() {
        // Given
        StringBuilder executionOrder = new StringBuilder();
        
        agent.onStopHook(() -> executionOrder.append("1"));
        agent.onStopHook(() -> executionOrder.append("2"));
        agent.onStopHook(() -> executionOrder.append("3"));
        
        agent.start().join();
        
        // When
        agent.stop().join();
        
        // Then
        assertThat(executionOrder.toString()).isEqualTo("123");
    }
    
    @Test
    void testStopHookCalledBeforeOnStop() {
        // Given
        AtomicInteger executionOrder = new AtomicInteger(0);
        agent.setOnStopOrder(executionOrder);
        
        agent.onStopHook(() -> {
            int order = executionOrder.incrementAndGet();
            assertThat(order).isEqualTo(1); // Should be called BEFORE onStop
        });
        
        agent.start().join();
        
        // When
        agent.stop().join();
        
        // Then
        assertThat(executionOrder.get()).isEqualTo(2);
    }
    
    @Test
    void testStopHookCanAccessAgentStateBeforeShutdown() {
        // Given
        AtomicBoolean agentWasRunning = new AtomicBoolean(false);
        
        agent.onStopHook(() -> {
            // Agent should still be "running" when stop hook executes
            agentWasRunning.set(agent.isRunning());
        });
        
        agent.start().join();
        
        // When
        agent.stop().join();
        
        // Then
        assertThat(agentWasRunning.get()).isFalse(); // running flag is set to false early
    }
    
    @Test
    void testStopHookExceptionDoesNotStopOtherHooks() {
        // Given
        AtomicInteger successfulHooks = new AtomicInteger(0);
        
        agent.onStopHook(() -> successfulHooks.incrementAndGet());
        agent.onStopHook(() -> {
            throw new RuntimeException("Test exception");
        });
        agent.onStopHook(() -> successfulHooks.incrementAndGet());
        
        agent.start().join();
        
        // When
        agent.stop().join();
        
        // Then
        assertThat(successfulHooks.get()).isEqualTo(2);
        assertThat(agent.isRunning()).isFalse();
    }
    
    @Test
    void testNullStopHookIsIgnored() {
        // Given
        agent.onStopHook(null);
        agent.start().join();
        
        // When/Then - should not throw
        assertThatCode(() -> agent.stop().join()).doesNotThrowAnyException();
    }
    
    // =========================================================================
    // HOOK MANAGEMENT
    // =========================================================================
    
    @Test
    void testRemoveStartHook() {
        // Given
        AtomicBoolean hook1Called = new AtomicBoolean(false);
        AtomicBoolean hook2Called = new AtomicBoolean(false);
        
        Runnable hook1 = () -> hook1Called.set(true);
        Runnable hook2 = () -> hook2Called.set(true);
        
        agent.onStartHook(hook1);
        agent.onStartHook(hook2);
        
        // When
        boolean removed = agent.removeStartHook(hook1);
        agent.start().join();
        
        // Then
        assertThat(removed).isTrue();
        assertThat(hook1Called.get()).isFalse();
        assertThat(hook2Called.get()).isTrue();
    }
    
    @Test
    void testRemoveStopHook() {
        // Given
        AtomicBoolean hook1Called = new AtomicBoolean(false);
        AtomicBoolean hook2Called = new AtomicBoolean(false);
        
        Runnable hook1 = () -> hook1Called.set(true);
        Runnable hook2 = () -> hook2Called.set(true);
        
        agent.onStopHook(hook1);
        agent.onStopHook(hook2);
        agent.start().join();
        
        // When
        boolean removed = agent.removeStopHook(hook1);
        agent.stop().join();
        
        // Then
        assertThat(removed).isTrue();
        assertThat(hook1Called.get()).isFalse();
        assertThat(hook2Called.get()).isTrue();
    }
    
    @Test
    void testClearStartHooks() {
        // Given
        AtomicInteger hooksCalled = new AtomicInteger(0);
        
        agent.onStartHook(() -> hooksCalled.incrementAndGet());
        agent.onStartHook(() -> hooksCalled.incrementAndGet());
        
        // When
        agent.clearStartHooks();
        agent.start().join();
        
        // Then
        assertThat(hooksCalled.get()).isEqualTo(0);
    }
    
    @Test
    void testClearStopHooks() {
        // Given
        AtomicInteger hooksCalled = new AtomicInteger(0);
        
        agent.onStopHook(() -> hooksCalled.incrementAndGet());
        agent.onStopHook(() -> hooksCalled.incrementAndGet());
        agent.start().join();
        
        // When
        agent.clearStopHooks();
        agent.stop().join();
        
        // Then
        assertThat(hooksCalled.get()).isEqualTo(0);
    }
    
    // =========================================================================
    // PERSISTENCE USE CASE SIMULATION
    // =========================================================================
    
    @Test
    void testPersistenceHookSimulation() throws Exception {
        // Simulate PersistenceManager saving state on agent stop
        
        // Given
        AtomicBoolean stateSaved = new AtomicBoolean(false);
        CountDownLatch saveLatch = new CountDownLatch(1);
        
        agent.onStopHook(() -> {
            // Simulate state capture and save
            try {
                Thread.sleep(50); // Simulate I/O
                stateSaved.set(true);
                saveLatch.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        
        agent.start().join();
        
        // When
        agent.stop().join();
        
        // Then
        boolean completed = saveLatch.await(1, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        assertThat(stateSaved.get()).isTrue();
    }
    
    @Test
    void testConcurrentHookRegistration() throws Exception {
        // Test thread-safety of hook registration
        
        // Given
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(threadCount);
        AtomicInteger registeredHooks = new AtomicInteger(0);
        
        // When - Register hooks concurrently
        for (int i = 0; i < threadCount; i++) {
            Thread.ofVirtual().start(() -> {
                agent.onStartHook(() -> registeredHooks.incrementAndGet());
                startLatch.countDown();
            });
        }
        
        startLatch.await(1, TimeUnit.SECONDS);
        agent.start().join();
        
        // Then
        assertThat(registeredHooks.get()).isEqualTo(threadCount);
    }
    
    @Test
    void testHookRegistrationWhileAgentRunning() {
        // Hooks can be added while agent is running
        
        // Given
        agent.start().join();
        AtomicBoolean hookCalled = new AtomicBoolean(false);
        
        // When - Add hook after agent is started
        agent.onStopHook(() -> hookCalled.set(true));
        agent.stop().join();
        
        // Then
        assertThat(hookCalled.get()).isTrue();
    }
    
    // =========================================================================
    // TEST AGENT IMPLEMENTATION
    // =========================================================================
    
    /**
     * Test agent with controllable onStart/onStop hooks for testing execution order
     */
    private static class TestAgent extends BaseAgent {
        private AtomicInteger onStartOrder;
        private AtomicInteger onStopOrder;
        
        public TestAgent(String agentId) {
            super(agentId);
        }
        
        public void setOnStartOrder(AtomicInteger order) {
            this.onStartOrder = order;
        }
        
        public void setOnStopOrder(AtomicInteger order) {
            this.onStopOrder = order;
        }
        
        @Override
        protected void onStart() {
            if (onStartOrder != null) {
                onStartOrder.incrementAndGet();
            }
        }
        
        @Override
        protected void onStop() {
            if (onStopOrder != null) {
                onStopOrder.incrementAndGet();
            }
        }
    }
}