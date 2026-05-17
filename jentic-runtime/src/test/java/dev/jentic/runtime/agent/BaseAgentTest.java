package dev.jentic.runtime.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.jentic.core.AgentDirectory;
import dev.jentic.core.AgentStatus;
import dev.jentic.core.BehaviorScheduler;
import dev.jentic.core.messaging.MessageDispatcher;
import dev.jentic.core.memory.MemoryScope;
import dev.jentic.core.memory.MemoryStats;
import dev.jentic.core.telemetry.JenticTelemetry;
import dev.jentic.runtime.behavior.OneShotBehavior;
import dev.jentic.runtime.directory.LocalAgentDirectory;
import dev.jentic.runtime.memory.InMemoryStore;
import dev.jentic.runtime.messaging.InMemoryMessageDispatcher;
import dev.jentic.runtime.scheduler.SimpleBehaviorScheduler;

/**
 * Unit tests for BaseAgent
 */
class BaseAgentTest {
    
    private MessageDispatcher messageDispatcher;
    private AgentDirectory agentDirectory;
    private BehaviorScheduler behaviorScheduler;
    private TestAgent agent;

    @BeforeEach
    void setUp() {
        agentDirectory = new LocalAgentDirectory();
        messageDispatcher = new InMemoryMessageDispatcher(agentDirectory, JenticTelemetry.noop());
        behaviorScheduler = new SimpleBehaviorScheduler();
        behaviorScheduler.start().join();

        agent = new TestAgent("test-agent", "Test Agent");
        agent.setMessageDispatcher(messageDispatcher);
        agent.setAgentDirectory(agentDirectory);
        agent.setBehaviorScheduler(behaviorScheduler);
    }
    
    @Test
    void shouldHaveCorrectIdentity() {
        assertThat(agent.getAgentId()).isEqualTo("test-agent");
        assertThat(agent.getAgentName()).isEqualTo("Test Agent");
        assertThat(agent.isRunning()).isFalse();
    }
    
    @Test
    void shouldStartAndStop() {
        // Initially stopped
        assertThat(agent.isRunning()).isFalse();
        
        // Start agent
        agent.start().join();
        assertThat(agent.isRunning()).isTrue();
        assertThat(agent.startCalled).isTrue();
        
        // Stop agent
        agent.stop().join();
        assertThat(agent.isRunning()).isFalse();
        assertThat(agent.stopCalled).isTrue();
    }
    
    @Test
    void shouldNotStartTwice() {
        // Given
        agent.start().join();
        assertThat(agent.isRunning()).isTrue();
        
        // When
        CompletableFuture<Void> secondStart = agent.start();
        
        // Then
        assertThat(secondStart).isCompletedWithValue(null);
        assertThat(agent.isRunning()).isTrue();
    }
    
    @Test
    void shouldNotStopTwice() {
        // Given
        agent.start().join();
        agent.stop().join();
        assertThat(agent.isRunning()).isFalse();
        
        // When
        CompletableFuture<Void> secondStop = agent.stop();
        
        // Then
        assertThat(secondStop).isCompletedWithValue(null);
        assertThat(agent.isRunning()).isFalse();
    }
    
    @Test
    void shouldAddAndRemoveBehaviors() throws InterruptedException {
        // Given
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean executed = new AtomicBoolean(false);
        
        OneShotBehavior behavior = OneShotBehavior.from("test-behavior", () -> {
            executed.set(true);
            latch.countDown();
        });
        
        agent.start().join();
        
        // When
        agent.addBehavior(behavior);
        
        // Then
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(executed.get()).isTrue();
        
        // When
        agent.removeBehavior(behavior.getBehaviorId());
        
        // Behavior should be stopped (though OneShotBehavior stops itself anyway)
        assertThat(behavior.isActive()).isFalse();
    }
    
    @Test
    void shouldRegisterWithDirectory() {
        // When
        agent.start().join();
        
        // Then
        var descriptor = agentDirectory.findById("test-agent").join();
        assertThat(descriptor).isPresent();
        assertThat(descriptor.get().agentId()).isEqualTo("test-agent");
        assertThat(descriptor.get().agentName()).isEqualTo("Test Agent");
        assertThat(descriptor.get().status()).isEqualTo(AgentStatus.RUNNING);
    }
    
    @Test
    void shouldUnregisterFromDirectory() {
        // Given
        agent.start().join();
        assertThat(agentDirectory.findById("test-agent").join()).isPresent();
        
        // When
        agent.stop().join();
        
        // Then
        assertThat(agentDirectory.findById("test-agent").join()).isEmpty();
    }
    
    @Test
    void shouldGetMessageDispatcher() {
        assertThat(agent.getMessageDispatcher()).isEqualTo(messageDispatcher);
    }

// ========== MEMORY FUNCTIONALITY TESTS ==========
    
    @Test
    @DisplayName("Should store and recall short-term memory")
    void testShortTermMemory() {
        InMemoryStore memoryStore = new InMemoryStore();
        agent.setMemoryStore(memoryStore);
        
        try {
            agent.rememberShort("key1", "value1").join();
            
            Optional<String> recalled = agent.recall("key1", MemoryScope.SHORT_TERM).join();
            
            assertThat(recalled).isPresent();
            assertThat(recalled.get()).isEqualTo("value1");
        } finally {
            memoryStore.shutdown();
        }
    }
    
    @Test
    @DisplayName("Should store short-term memory with TTL")
    void testShortTermWithTTL() {
        InMemoryStore memoryStore = new InMemoryStore();
        agent.setMemoryStore(memoryStore);
        
        try {
            agent.rememberShort("key1", "value1", Duration.ofHours(1)).join();
            
            Optional<String> recalled = agent.recall("key1", MemoryScope.SHORT_TERM).join();
            assertThat(recalled).isPresent();
        } finally {
            memoryStore.shutdown();
        }
    }
    
    @Test
    @DisplayName("Should store and recall long-term memory")
    void testLongTermMemory() {
        InMemoryStore memoryStore = new InMemoryStore();
        agent.setMemoryStore(memoryStore);
        
        try {
            agent.rememberLong("key1", "value1").join();
            
            Optional<String> recalled = agent.recall("key1", MemoryScope.LONG_TERM).join();
            
            assertThat(recalled).isPresent();
            assertThat(recalled.get()).isEqualTo("value1");
        } finally {
            memoryStore.shutdown();
        }
    }
    
    @Test
    @DisplayName("Should store long-term memory with metadata")
    void testLongTermWithMetadata() {
        InMemoryStore memoryStore = new InMemoryStore();
        agent.setMemoryStore(memoryStore);
        
        try {
            Map<String, Object> metadata = Map.of("category", "test", "priority", 5);
            agent.rememberLong("key1", "value1", metadata).join();
            
            Optional<String> recalled = agent.recall("key1", MemoryScope.LONG_TERM).join();
            assertThat(recalled).isPresent();
        } finally {
            memoryStore.shutdown();
        }
    }
    
    @Test
    @DisplayName("Should share memory between agents")
    void testSharedMemory() {
        InMemoryStore memoryStore = new InMemoryStore();
        TestAgent agent1 = new TestAgent("agent-1", "Agent 1");
        TestAgent agent2 = new TestAgent("agent-2", "Agent 2");
        
        agent1.setMemoryStore(memoryStore);
        agent2.setMemoryStore(memoryStore);
        
        try {
            agent1.shareMemory("task-context", "Processing order #123", "agent-2").join();
            
            Optional<String> recalled = agent2.recallShared("task-context").join();
            
            assertThat(recalled).isPresent();
            assertThat(recalled.get()).isEqualTo("Processing order #123");
        } finally {
            memoryStore.shutdown();
        }
    }
    
    @Test
    @DisplayName("Should search memories")
    void testSearchMemory() {
        InMemoryStore memoryStore = new InMemoryStore();
        agent.setMemoryStore(memoryStore);
        
        try {
            agent.rememberShort("key1", "Hello world").join();
            agent.rememberShort("key2", "Hello there").join();
            agent.rememberShort("key3", "Goodbye").join();
            
            List<String> results = agent.searchMemory("hello", MemoryScope.SHORT_TERM).join();
            
            assertThat(results).hasSize(2);
            assertThat(results).allMatch(s -> s.toLowerCase().contains("hello"));
        } finally {
            memoryStore.shutdown();
        }
    }
    
    @Test
    @DisplayName("Should forget memories")
    void testForgetMemory() {
        InMemoryStore memoryStore = new InMemoryStore();
        agent.setMemoryStore(memoryStore);
        
        try {
            agent.rememberShort("key1", "value1").join();
            agent.forget("key1", MemoryScope.SHORT_TERM).join();
            
            Optional<String> recalled = agent.recall("key1", MemoryScope.SHORT_TERM).join();
            assertThat(recalled).isEmpty();
        } finally {
            memoryStore.shutdown();
        }
    }
    
    @Test
    @DisplayName("Should get memory statistics")
    void testMemoryStats() {
        InMemoryStore memoryStore = new InMemoryStore();
        agent.setMemoryStore(memoryStore);
        
        try {
            agent.rememberShort("key1", "value1").join();
            agent.rememberShort("key2", "value2").join();
            agent.rememberLong("key3", "value3").join();
            
            MemoryStats stats = agent.getMemoryStats();
            
            assertThat(stats.totalCount()).isEqualTo(3);
        } finally {
            memoryStore.shutdown();
        }
    }
    
    @Test
    @DisplayName("Should isolate memories between agents")
    void testMemoryIsolation() {
        InMemoryStore memoryStore = new InMemoryStore();
        TestAgent agent1 = new TestAgent("agent-1", "Agent 1");
        TestAgent agent2 = new TestAgent("agent-2", "Agent 2");
        
        agent1.setMemoryStore(memoryStore);
        agent2.setMemoryStore(memoryStore);
        
        try {
            agent1.rememberShort("same-key", "value1").join();
            agent2.rememberShort("same-key", "value2").join();
            
            Optional<String> recalled1 = agent1.recall("same-key", MemoryScope.SHORT_TERM).join();
            Optional<String> recalled2 = agent2.recall("same-key", MemoryScope.SHORT_TERM).join();
            
            assertThat(recalled1.get()).isEqualTo("value1");
            assertThat(recalled2.get()).isEqualTo("value2");
        } finally {
            memoryStore.shutdown();
        }
    }
    
    // Test agent implementation
    static class TestAgent extends BaseAgent {
        boolean startCalled = false;
        boolean stopCalled = false;
        
        TestAgent(String agentId, String agentName) {
            super(agentId, agentName);
        }
        
        @Override
        protected void onStart() {
            startCalled = true;
        }
        
        @Override
        protected void onStop() {
            stopCalled = true;
        }
    }
}