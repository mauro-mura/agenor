package dev.agenor.runtime.memory.llm;

import static dev.agenor.runtime.memory.llm.ContextWindowStrategies.FIXED;
import static dev.agenor.runtime.memory.llm.ContextWindowStrategies.SLIDING;
import static dev.agenor.runtime.memory.llm.ContextWindowStrategies.SUMMARIZED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.agenor.core.llm.LLMMessage;
import dev.agenor.core.memory.MemoryEntry;
import dev.agenor.core.memory.MemoryScope;
import dev.agenor.core.memory.MemoryStore;
import dev.agenor.runtime.memory.InMemoryStore;

/**
 * Tests for DefaultLLMMemoryManager.
 */
class DefaultLLMMemoryManagerTest {

    private MemoryStore memoryStore;
    private SimpleTokenEstimator tokenEstimator;
    private DefaultLLMMemoryManager manager;

    @BeforeEach
    void setUp() {
        memoryStore = new InMemoryStore();
        tokenEstimator = new SimpleTokenEstimator();
        manager = new DefaultLLMMemoryManager(memoryStore, tokenEstimator, "test-agent");
    }

    // ========== CONSTRUCTION TESTS ==========

    @Test
    void constructor_shouldAcceptValidParameters() {
        // When
        DefaultLLMMemoryManager mgr = new DefaultLLMMemoryManager(
            memoryStore,
            tokenEstimator,
            "agent-123"
        );

        // Then
        assertThat(mgr.getAgentId()).isEqualTo("agent-123");
        assertThat(mgr.getTokenEstimator()).isSameAs(tokenEstimator);
        assertThat(mgr.getMessageCount()).isZero();
    }

    @Test
    void constructor_shouldTrimAgentId() {
        // When
        DefaultLLMMemoryManager mgr = new DefaultLLMMemoryManager(
            memoryStore,
            tokenEstimator,
            "  agent-123  "
        );

        // Then
        assertThat(mgr.getAgentId()).isEqualTo("agent-123");
    }

    @Test
    void constructor_shouldThrowForNullMemoryStore() {
        // When/Then
        assertThatThrownBy(() -> new DefaultLLMMemoryManager(null, tokenEstimator, "agent"))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("MemoryStore cannot be null");
    }

    @Test
    void constructor_shouldThrowForNullEstimator() {
        // When/Then
        assertThatThrownBy(() -> new DefaultLLMMemoryManager(memoryStore, null, "agent"))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("TokenEstimator cannot be null");
    }

    @Test
    void constructor_shouldThrowForNullAgentId() {
        // When/Then
        assertThatThrownBy(() -> new DefaultLLMMemoryManager(memoryStore, tokenEstimator, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Agent ID cannot be null");
    }

    @Test
    void constructor_shouldThrowForEmptyAgentId() {
        // When/Then
        assertThatThrownBy(() -> new DefaultLLMMemoryManager(memoryStore, tokenEstimator, "  "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Agent ID cannot be empty");
    }

    // ========== ADD MESSAGE TESTS ==========

    @Test
    void addMessage_shouldAddToConversationHistory() {
        // Given
        LLMMessage message = LLMMessage.user("Hello!");

        // When
        manager.addMessage(message).join();

        // Then
        assertThat(manager.getMessageCount()).isEqualTo(1);
        List<LLMMessage> history = manager.getAllMessages().join();
        assertThat(history).containsExactly(message);
    }

    @Test
    void addMessage_shouldHandleMultipleMessages() {
        // When
        manager.addMessage(LLMMessage.user("Hello")).join();
        manager.addMessage(LLMMessage.assistant("Hi!")).join();
        manager.addMessage(LLMMessage.user("How are you?")).join();

        // Then
        assertThat(manager.getMessageCount()).isEqualTo(3);
    }

    @Test
    void addMessage_shouldThrowForNullMessage() {
        // When/Then
        assertThatThrownBy(() -> manager.addMessage(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Message cannot be null");
    }

    @Test
    void addMessages_shouldAddMultipleMessages() {
        // Given
        List<LLMMessage> messages = List.of(
            LLMMessage.user("Hello"),
            LLMMessage.assistant("Hi!"),
            LLMMessage.user("Bye")
        );

        // When
        manager.addMessages(messages).join();

        // Then
        assertThat(manager.getMessageCount()).isEqualTo(3);
    }

    @Test
    void addMessages_shouldThrowForNullList() {
        // When/Then
        assertThatThrownBy(() -> manager.addMessages(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void addMessages_shouldThrowForEmptyList() {
        // When/Then
        assertThatThrownBy(() -> manager.addMessages(List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Messages cannot be empty");
    }

    // ========== GET CONVERSATION HISTORY TESTS ==========

    @Test
    void getConversationHistory_shouldReturnMessagesWithinBudget() {
        // Given
        manager.addMessage(LLMMessage.user("Hi")).join();
        manager.addMessage(LLMMessage.assistant("Hello!")).join();
        manager.addMessage(LLMMessage.user("How are you?")).join();

        // When - get with large budget
        List<LLMMessage> history = manager.getConversationHistory(1000, FIXED).join();

        // Then
        assertThat(history).hasSize(3);
    }

    @Test
    void getConversationHistory_shouldLimitByTokenBudget() {
        // Given - add many messages
        for (int i = 0; i < 20; i++) {
            manager.addMessage(LLMMessage.user("Message " + i)).join();
        }

        // When - get with small budget
        List<LLMMessage> history = manager.getConversationHistory(50, FIXED).join();

        // Then - should be limited
        assertThat(history.size()).isLessThan(20);
        int totalTokens = tokenEstimator.estimateTokens(history);
        assertThat(totalTokens).isLessThanOrEqualTo(50);
    }

    @Test
    void getConversationHistory_shouldWorkWithDifferentStrategies() {
        // Given
        for (int i = 0; i < 10; i++) {
            manager.addMessage(LLMMessage.user("Message " + i)).join();
        }

        // When
        List<LLMMessage> fixed = manager.getConversationHistory(100, FIXED).join();
        List<LLMMessage> sliding = manager.getConversationHistory(100, SLIDING).join();
        List<LLMMessage> summarized = manager.getConversationHistory(100, SUMMARIZED).join();

        // Then - all should respect budget
        assertThat(tokenEstimator.estimateTokens(fixed)).isLessThanOrEqualTo(100);
        assertThat(tokenEstimator.estimateTokens(sliding)).isLessThanOrEqualTo(100);
        assertThat(tokenEstimator.estimateTokens(summarized)).isLessThanOrEqualTo(100);
    }

    @Test
    void getConversationHistory_shouldThrowForInvalidMaxTokens() {
        // When/Then
        assertThatThrownBy(() -> manager.getConversationHistory(0, FIXED))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Max tokens must be positive");

        assertThatThrownBy(() -> manager.getConversationHistory(-100, FIXED))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getConversationHistory_shouldThrowForNullStrategy() {
        // When/Then
        assertThatThrownBy(() -> manager.getConversationHistory(1000, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Strategy cannot be null");
    }

    // ========== GET ALL MESSAGES TESTS ==========

    @Test
    void getAllMessages_shouldReturnAllMessages() {
        // Given
        manager.addMessage(LLMMessage.user("Message 1")).join();
        manager.addMessage(LLMMessage.user("Message 2")).join();
        manager.addMessage(LLMMessage.user("Message 3")).join();

        // When
        List<LLMMessage> all = manager.getAllMessages().join();

        // Then
        assertThat(all).hasSize(3);
    }

    @Test
    void getAllMessages_shouldReturnEmptyListWhenNoMessages() {
        // When
        List<LLMMessage> all = manager.getAllMessages().join();

        // Then
        assertThat(all).isEmpty();
    }

    @Test
    void getAllMessages_shouldReturnCopy() {
        // Given
        manager.addMessage(LLMMessage.user("Test")).join();

        // When
        List<LLMMessage> all1 = manager.getAllMessages().join();
        List<LLMMessage> all2 = manager.getAllMessages().join();

        // Then - should be different instances
        assertThat(all1).isNotSameAs(all2);
        assertThat(all1).isEqualTo(all2);
    }

    // ========== REMEMBER / RETRIEVE TESTS ==========

    @Test
    void remember_shouldStoreInLongTermMemory() {
        // When
        manager.remember("user-name", "Alice", Map.of("category", "profile")).join();

        // Then - should be in memory store
        MemoryEntry entry = memoryStore.retrieve("user-name", MemoryScope.LONG_TERM).join().orElse(null);
        assertThat(entry).isNotNull();
        assertThat(entry.content()).isEqualTo("Alice");
        assertThat(entry.ownerId()).isEqualTo("test-agent");
        assertThat(entry.tokenCount()).isGreaterThan(0);
    }

    @Test
    void remember_shouldCalculateTokenCount() {
        // When
        manager.remember("fact", "The sky is blue", Map.of()).join();

        // Then
        MemoryEntry entry = memoryStore.retrieve("fact", MemoryScope.LONG_TERM).join().orElse(null);
        assertThat(entry).isNotNull();
        assertThat(entry.tokenCount()).isEqualTo(
            tokenEstimator.estimateTokens("The sky is blue")
        );
    }

    @Test
    void remember_shouldThrowForNullKey() {
        // When/Then
        assertThatThrownBy(() -> manager.remember(null, "content", Map.of()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void remember_shouldThrowForEmptyKey() {
        // When/Then
        assertThatThrownBy(() -> manager.remember("  ", "content", Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Key cannot be empty");
    }

    @Test
    void remember_shouldThrowForNullContent() {
        // When/Then
        assertThatThrownBy(() -> manager.remember("key", null, Map.of()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void remember_shouldThrowForEmptyContent() {
        // When/Then
        assertThatThrownBy(() -> manager.remember("key", "  ", Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Content cannot be empty");
    }

    @Test
    void retrieveRelevantContext_shouldReturnMatchingEntries() {
        // Given
        manager.remember("fact1", "User likes blue", Map.of("category", "preference")).join();
        manager.remember("fact2", "User is from Paris", Map.of("category", "location")).join();
        manager.remember("fact3", "User prefers tea", Map.of("category", "preference")).join();

        // When - query with text that actually matches the content
        List<MemoryEntry> context = manager.retrieveRelevantContext("user", 1000).join();

        // Then
        assertThat(context).isNotEmpty();
        assertThat(context).allMatch(e -> e.ownerId().equals("test-agent"));
    }

    @Test
    void retrieveRelevantContext_shouldRespectTokenBudget() {
        // Given - add multiple facts
        for (int i = 0; i < 10; i++) {
            manager.remember("fact" + i, "This is a fact number " + i, Map.of()).join();
        }

        // When - retrieve with small budget
        List<MemoryEntry> context = manager.retrieveRelevantContext("fact", 50).join();

        // Then
        int totalTokens = context.stream()
            .mapToInt(e -> e.tokenCount() > 0 ? e.tokenCount() : tokenEstimator.estimateTokens(e.content()))
            .sum();
        assertThat(totalTokens).isLessThanOrEqualTo(50);
    }

    @Test
    void retrieveRelevantContext_shouldThrowForNullQuery() {
        // When/Then
        assertThatThrownBy(() -> manager.retrieveRelevantContext(null, 100))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void retrieveRelevantContext_shouldThrowForEmptyQuery() {
        // When/Then
        assertThatThrownBy(() -> manager.retrieveRelevantContext("  ", 100))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Query cannot be empty");
    }

    // ========== SUMMARIZE TESTS ==========

    @Test
    void summarizeOldMessages_shouldReplaceMessagesWithSummary() {
        // Given - 10 messages
        for (int i = 0; i < 10; i++) {
            manager.addMessage(LLMMessage.user("Message " + i)).join();
        }

        // When - summarize oldest 5
        String summary = manager.summarizeOldMessages(5).join();

        // Then
        assertThat(summary).isNotEmpty();
        assertThat(manager.getMessageCount()).isEqualTo(6);  // 5 removed, 1 summary + 5 remaining

        List<LLMMessage> messages = manager.getAllMessages().join();
        assertThat(messages.get(0).role()).isEqualTo(LLMMessage.Role.SYSTEM);
        assertThat(messages.get(0).content()).contains("Previous conversation summary");
    }

    @Test
    void summarizeOldMessages_shouldThrowWhenNotEnoughMessages() {
        // Given - only 2 messages
        manager.addMessage(LLMMessage.user("Hi")).join();
        manager.addMessage(LLMMessage.assistant("Hello")).join();

        // When/Then - try to summarize 5
        assertThatThrownBy(() -> manager.summarizeOldMessages(5).join())
            .hasCauseInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Not enough messages");
    }

    @Test
    void summarizeOldMessages_shouldThrowForInvalidCount() {
        // When/Then
        assertThatThrownBy(() -> manager.summarizeOldMessages(0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Messages to summarize must be positive");
    }

    // ========== CLEAR TESTS ==========

    @Test
    void clearConversationHistory_shouldRemoveAllMessages() {
        // Given
        manager.addMessage(LLMMessage.user("Message 1")).join();
        manager.addMessage(LLMMessage.user("Message 2")).join();

        // When
        manager.clearConversationHistory().join();

        // Then
        assertThat(manager.getMessageCount()).isZero();
        assertThat(manager.getAllMessages().join()).isEmpty();
    }

    @Test
    void clearConversationHistory_shouldNotAffectLongTermMemory() {
        // Given
        manager.addMessage(LLMMessage.user("Hi")).join();
        manager.remember("fact", "Important fact", Map.of()).join();

        // When
        manager.clearConversationHistory().join();

        // Then
        assertThat(manager.getMessageCount()).isZero();
        assertThat(memoryStore.retrieve("fact", MemoryScope.LONG_TERM).join()).isNotNull();  // Still there
    }

    // ========== TOKEN COUNT TESTS ==========

    @Test
    void getCurrentTokenCount_shouldReturnZeroInitially() {
        // Then
        assertThat(manager.getCurrentTokenCount()).isZero();
    }

    @Test
    void getCurrentTokenCount_shouldReturnCorrectCount() {
        // Given
        LLMMessage msg1 = LLMMessage.user("Hello");
        LLMMessage msg2 = LLMMessage.assistant("Hi there!");

        manager.addMessage(msg1).join();
        manager.addMessage(msg2).join();

        // When
        int tokenCount = manager.getCurrentTokenCount();

        // Then
        int expected = tokenEstimator.estimateTokens(msg1) + tokenEstimator.estimateTokens(msg2);
        assertThat(tokenCount).isEqualTo(expected);
    }

    @Test
    void getCurrentTokenCount_shouldUpdateAfterClear() {
        // Given
        manager.addMessage(LLMMessage.user("Test")).join();
        assertThat(manager.getCurrentTokenCount()).isGreaterThan(0);

        // When
        manager.clearConversationHistory().join();

        // Then
        assertThat(manager.getCurrentTokenCount()).isZero();
    }

    // ========== MESSAGE COUNT TESTS ==========

    @Test
    void getMessageCount_shouldReturnCorrectCount() {
        // Given
        manager.addMessage(LLMMessage.user("1")).join();
        manager.addMessage(LLMMessage.user("2")).join();
        manager.addMessage(LLMMessage.user("3")).join();

        // Then
        assertThat(manager.getMessageCount()).isEqualTo(3);
    }

    @Test
    void getMessageCount_shouldUpdateAfterAdd() {
        // Given
        assertThat(manager.getMessageCount()).isZero();

        // When
        manager.addMessage(LLMMessage.user("Test")).join();

        // Then
        assertThat(manager.getMessageCount()).isEqualTo(1);
    }

    // ========== GETTER TESTS ==========

    @Test
    void getTokenEstimator_shouldReturnInjectedEstimator() {
        // Then
        assertThat(manager.getTokenEstimator()).isSameAs(tokenEstimator);
    }

    @Test
    void getAgentId_shouldReturnCorrectId() {
        // Then
        assertThat(manager.getAgentId()).isEqualTo("test-agent");
    }

    // ========== TO STRING TEST ==========

    @Test
    void toString_shouldProvideUsefulInfo() {
        // Given
        manager.addMessage(LLMMessage.user("Test")).join();

        // When
        String str = manager.toString();

        // Then
        assertThat(str).contains("test-agent");
        assertThat(str).contains("messages=1");
    }

    // ========== THREAD SAFETY TESTS ==========

    @Test
    void addMessage_shouldBeThreadSafe() throws InterruptedException {
        // Given
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // When - add messages concurrently
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                manager.addMessage(LLMMessage.user("Message " + index)).join();
                latch.countDown();
            });
        }

        latch.await();
        executor.shutdown();

        // Then
        assertThat(manager.getMessageCount()).isEqualTo(threadCount);
    }

    @Test
    void getConversationHistory_shouldBeThreadSafe() throws InterruptedException {
        // Given - add some messages
        for (int i = 0; i < 20; i++) {
            manager.addMessage(LLMMessage.user("Message " + i)).join();
        }

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // When - read concurrently
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                List<LLMMessage> history = manager.getConversationHistory(1000, FIXED).join();
                assertThat(history).isNotEmpty();
                latch.countDown();
            });
        }

        latch.await();
        executor.shutdown();

        // Then - no exceptions thrown
    }
}
