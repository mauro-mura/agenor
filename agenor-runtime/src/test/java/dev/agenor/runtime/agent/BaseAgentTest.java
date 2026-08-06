package dev.agenor.runtime.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.agenor.core.telemetry.AgenorTelemetry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;

import dev.agenor.core.AgentDirectory;
import dev.agenor.core.AgentStatus;
import dev.agenor.core.BehaviorScheduler;
import dev.agenor.core.messaging.MessageDispatcher;
import dev.agenor.core.memory.MemoryEntry;
import dev.agenor.core.memory.MemoryQuery;
import dev.agenor.core.memory.MemoryScope;
import dev.agenor.core.memory.MemoryStats;
import dev.agenor.core.memory.MemoryStore;
import dev.agenor.runtime.behavior.OneShotBehavior;
import dev.agenor.runtime.directory.InMemoryAgentDirectory;
import dev.agenor.runtime.messaging.InMemoryMessageDispatcher;
import dev.agenor.runtime.scheduler.SimpleBehaviorScheduler;

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
        agentDirectory = new InMemoryAgentDirectory();
        messageDispatcher = new InMemoryMessageDispatcher(agentDirectory, AgenorTelemetry.noop());
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
        FakeMemoryStore memoryStore = new FakeMemoryStore();
        agent.setMemoryStore(memoryStore);

        agent.rememberShort("key1", "value1").join();

        Optional<String> recalled = agent.recall("key1", MemoryScope.SHORT_TERM).join();

        assertThat(recalled).isPresent();
        assertThat(recalled.get()).isEqualTo("value1");
    }

    @Test
    @DisplayName("Should store short-term memory with TTL")
    void testShortTermWithTTL() {
        FakeMemoryStore memoryStore = new FakeMemoryStore();
        agent.setMemoryStore(memoryStore);

        agent.rememberShort("key1", "value1", Duration.ofHours(1)).join();

        Optional<String> recalled = agent.recall("key1", MemoryScope.SHORT_TERM).join();
        assertThat(recalled).isPresent();
    }

    @Test
    @DisplayName("Should store and recall long-term memory")
    void testLongTermMemory() {
        FakeMemoryStore memoryStore = new FakeMemoryStore();
        agent.setMemoryStore(memoryStore);

        agent.rememberLong("key1", "value1").join();

        Optional<String> recalled = agent.recall("key1", MemoryScope.LONG_TERM).join();

        assertThat(recalled).isPresent();
        assertThat(recalled.get()).isEqualTo("value1");
    }

    @Test
    @DisplayName("Should store long-term memory with metadata")
    void testLongTermWithMetadata() {
        FakeMemoryStore memoryStore = new FakeMemoryStore();
        agent.setMemoryStore(memoryStore);

        Map<String, Object> metadata = Map.of("category", "test", "priority", 5);
        agent.rememberLong("key1", "value1", metadata).join();

        Optional<String> recalled = agent.recall("key1", MemoryScope.LONG_TERM).join();
        assertThat(recalled).isPresent();
    }

    @Test
    @DisplayName("Should share memory between agents")
    void testSharedMemory() {
        FakeMemoryStore memoryStore = new FakeMemoryStore();
        TestAgent agent1 = new TestAgent("agent-1", "Agent 1");
        TestAgent agent2 = new TestAgent("agent-2", "Agent 2");

        agent1.setMemoryStore(memoryStore);
        agent2.setMemoryStore(memoryStore);

        agent1.shareMemory("task-context", "Processing order #123", "agent-2").join();

        Optional<String> recalled = agent2.recallShared("task-context").join();

        assertThat(recalled).isPresent();
        assertThat(recalled.get()).isEqualTo("Processing order #123");
    }

    @Test
    @DisplayName("Should search memories")
    void testSearchMemory() {
        FakeMemoryStore memoryStore = new FakeMemoryStore();
        agent.setMemoryStore(memoryStore);

        agent.rememberShort("key1", "Hello world").join();
        agent.rememberShort("key2", "Hello there").join();
        agent.rememberShort("key3", "Goodbye").join();

        List<String> results = agent.searchMemory("hello", MemoryScope.SHORT_TERM).join();

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(s -> s.toLowerCase().contains("hello"));
    }

    @Test
    @DisplayName("Should forget memories")
    void testForgetMemory() {
        FakeMemoryStore memoryStore = new FakeMemoryStore();
        agent.setMemoryStore(memoryStore);

        agent.rememberShort("key1", "value1").join();
        agent.forget("key1", MemoryScope.SHORT_TERM).join();

        Optional<String> recalled = agent.recall("key1", MemoryScope.SHORT_TERM).join();
        assertThat(recalled).isEmpty();
    }

    @Test
    @DisplayName("Should get memory statistics")
    void testMemoryStats() {
        FakeMemoryStore memoryStore = new FakeMemoryStore();
        agent.setMemoryStore(memoryStore);

        agent.rememberShort("key1", "value1").join();
        agent.rememberShort("key2", "value2").join();
        agent.rememberLong("key3", "value3").join();

        MemoryStats stats = agent.getMemoryStats();

        assertThat(stats.totalCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should isolate memories between agents")
    void testMemoryIsolation() {
        FakeMemoryStore memoryStore = new FakeMemoryStore();
        TestAgent agent1 = new TestAgent("agent-1", "Agent 1");
        TestAgent agent2 = new TestAgent("agent-2", "Agent 2");

        agent1.setMemoryStore(memoryStore);
        agent2.setMemoryStore(memoryStore);

        agent1.rememberShort("same-key", "value1").join();
        agent2.rememberShort("same-key", "value2").join();

        Optional<String> recalled1 = agent1.recall("same-key", MemoryScope.SHORT_TERM).join();
        Optional<String> recalled2 = agent2.recall("same-key", MemoryScope.SHORT_TERM).join();

        assertThat(recalled1.get()).isEqualTo("value1");
        assertThat(recalled2.get()).isEqualTo("value2");
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

    /**
     * Minimal {@link MemoryStore} fixture used to test {@link BaseAgent}'s memory
     * delegation without depending on a specific implementation ({@code InMemoryStore}
     * lives in {@code agenor-runtime-ext}, a module {@code agenor-runtime} cannot
     * depend on). {@code InMemoryStore}'s own behavior is covered by its dedicated
     * test in {@code agenor-runtime-ext}.
     */
    static class FakeMemoryStore implements MemoryStore {
        private final Map<MemoryScope, ConcurrentHashMap<String, MemoryEntry>> byScope =
                new ConcurrentHashMap<>();

        private ConcurrentHashMap<String, MemoryEntry> scope(MemoryScope scope) {
            return byScope.computeIfAbsent(scope, s -> new ConcurrentHashMap<>());
        }

        @Override
        public CompletableFuture<Void> store(String key, MemoryEntry entry, MemoryScope scope) {
            scope(scope).put(key, entry);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Optional<MemoryEntry>> retrieve(String key, MemoryScope scope) {
            MemoryEntry entry = scope(scope).get(key);
            if (entry != null && entry.isExpired()) {
                scope(scope).remove(key);
                entry = null;
            }
            return CompletableFuture.completedFuture(Optional.ofNullable(entry));
        }

        @Override
        public CompletableFuture<List<MemoryEntry>> search(MemoryQuery query) {
            var results = scope(query.scope()).values().stream()
                    .filter(e -> !e.isExpired())
                    .filter(e -> !query.hasOwnerFilter() || e.isOwnedBy(query.ownerId()))
                    .filter(e -> !query.hasTextFilter()
                            || e.content().toLowerCase().contains(query.text().toLowerCase()))
                    .limit(query.limit())
                    .toList();
            return CompletableFuture.completedFuture(results);
        }

        @Override
        public CompletableFuture<Void> delete(String key, MemoryScope scope) {
            scope(scope).remove(key);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> clear(MemoryScope scope) {
            scope(scope).clear();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<List<String>> listKeys(MemoryScope scope) {
            return CompletableFuture.completedFuture(List.copyOf(scope(scope).keySet()));
        }

        @Override
        public MemoryStats getStats() {
            return MemoryStats.builder()
                    .shortTermCount(scope(MemoryScope.SHORT_TERM).size())
                    .longTermCount(scope(MemoryScope.LONG_TERM).size())
                    .build();
        }
    }
}
