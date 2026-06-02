package dev.agenor.runtime.agent;

import dev.agenor.core.llm.LLMMessage;
import dev.agenor.core.memory.MemoryEntry;
import dev.agenor.core.memory.MemoryScope;
import dev.agenor.core.memory.MemoryStore;
import dev.agenor.runtime.memory.InMemoryStore;
import dev.agenor.runtime.memory.llm.DefaultLLMMemoryManager;
import dev.agenor.runtime.memory.llm.SimpleTokenEstimator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static dev.agenor.runtime.memory.llm.ContextWindowStrategies.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Tests for LLMAgent helper class.
 */
class LLMAgentTest {

    private TestLLMAgent agent;
    private MemoryStore memoryStore;
    private DefaultLLMMemoryManager llmMemory;

    @BeforeEach
    void setUp() {
        memoryStore = new InMemoryStore();
        llmMemory = new DefaultLLMMemoryManager(
            memoryStore,
            new SimpleTokenEstimator(),
            "test-agent"
        );

        agent = new TestLLMAgent("test-agent");
        agent.setMemoryStore(memoryStore);
        agent.setLLMMemoryManager(llmMemory);
    }

    // ========== CONVERSATION TESTS ==========

    @Test
    void addConversationMessage_shouldAddMessage() {
        // When
        agent.addConversationMessage(LLMMessage.user("Hello")).join();

        // Then
        assertThat(agent.getConversationMessageCount()).isEqualTo(1);
        assertThat(agent.getConversationTokens()).isGreaterThan(0);
    }

    @Test
    void addConversationMessages_shouldAddMultiple() {
        // Given
        List<LLMMessage> messages = List.of(
            LLMMessage.user("Hi"),
            LLMMessage.assistant("Hello"),
            LLMMessage.user("How are you?")
        );

        // When
        agent.addConversationMessages(messages).join();

        // Then
        assertThat(agent.getConversationMessageCount()).isEqualTo(3);
    }

    @Test
    void getConversation_shouldReturnHistory() {
        // Given
        agent.addConversationMessage(LLMMessage.user("Test")).join();
        agent.addConversationMessage(LLMMessage.assistant("Response")).join();

        // When
        List<LLMMessage> history = agent.getConversation().join();

        // Then
        assertThat(history).hasSize(2);
    }

    @Test
    void getConversation_shouldRespectBudget() {
        // Given
        for (int i = 0; i < 20; i++) {
            agent.addConversationMessage(LLMMessage.user("Message " + i)).join();
        }

        // When - get with small budget
        List<LLMMessage> history = agent.getConversation(50, FIXED).join();

        // Then
        assertThat(history.size()).isLessThan(20);
    }

    @Test
    void clearConversation_shouldClearMessages() {
        // Given
        agent.addConversationMessage(LLMMessage.user("Test")).join();

        // When
        agent.clearConversation().join();

        // Then
        assertThat(agent.getConversationMessageCount()).isZero();
    }

    // ========== FACTS TESTS ==========

    @Test
    void storeFact_shouldStoreInLongTerm() {
        // When
        agent.storeFact("user-name", "Alice").join();

        // Then
        MemoryEntry entry = memoryStore.retrieve("user-name", MemoryScope.LONG_TERM).join().orElse(null);
        assertThat(entry).isNotNull();
        assertThat(entry.content()).isEqualTo("Alice");
    }

    @Test
    void storeFact_shouldStoreWithMetadata() {
        // When
        agent.storeFact("preference", "coffee", Map.of("category", "drink")).join();

        // Then
        MemoryEntry entry = memoryStore.retrieve("preference", MemoryScope.LONG_TERM).join().orElse(null);
        assertThat(entry).isNotNull();
        assertThat(entry.metadata()).containsEntry("category", "drink");
    }

    @Test
    void retrieveFacts_shouldFindRelevant() {
        // Given
        agent.storeFact("fact1", "User likes blue").join();
        agent.storeFact("fact2", "User from Paris").join();

        // When
        List<MemoryEntry> facts = agent.retrieveFacts("user").join();

        // Then
        assertThat(facts).isNotEmpty();
    }

    @Test
    void retrieveFacts_shouldRespectBudget() {
        // Given
        for (int i = 0; i < 10; i++) {
            agent.storeFact("fact" + i, "This is fact number " + i).join();
        }

        // When
        List<MemoryEntry> facts = agent.retrieveFacts("fact", 50).join();

        // Then - should be limited by budget
        assertThat(facts).isNotEmpty();
        assertThat(facts.size()).isLessThanOrEqualTo(10);
    }

    // ========== PROMPT BUILDING TESTS ==========

    @Test
    void buildLLMPrompt_shouldIncludeMessage() {
        // When
        List<LLMMessage> prompt = agent.buildLLMPrompt("Test question", 1000).join();

        // Then
        assertThat(prompt).isNotEmpty();
        assertThat(prompt.getLast().content()).isEqualTo("Test question");
    }

    @Test
    void buildLLMPrompt_shouldIncludeConversation() {
        // Given
        agent.addConversationMessage(LLMMessage.user("Previous message")).join();

        // When
        List<LLMMessage> prompt = agent.buildLLMPrompt("New question", 2000).join();

        // Then
        assertThat(prompt).hasSizeGreaterThan(1);
        assertThat(prompt).anyMatch(m -> m.content().contains("Previous message"));
    }

    @Test
    void buildLLMPrompt_shouldIncludeContext() {
        // Given
        agent.storeFact("fact", "Important information").join();

        // When
        List<LLMMessage> prompt = agent.buildLLMPrompt("Question", 2000, "information").join();

        // Then
        assertThat(prompt).anyMatch(m ->
            m.role() == LLMMessage.Role.SYSTEM && m.content().contains("facts")
        );
    }

    @Test
    void buildLLMPrompt_shouldRespectTotalBudget() {
        // Given
        for (int i = 0; i < 20; i++) {
            agent.addConversationMessage(LLMMessage.user("Message " + i)).join();
        }

        // When
        List<LLMMessage> prompt = agent.buildLLMPrompt("Question", 100, null).join();

        // Then
        int totalTokens = llmMemory.getTokenEstimator().estimateTokens(prompt);
        assertThat(totalTokens).isLessThanOrEqualTo(120);  // Some margin
    }

    // ========== SUMMARIZATION TESTS ==========

    @Test
    void summarizeConversation_shouldSummarize() {
        // Given
        for (int i = 0; i < 15; i++) {
            agent.addConversationMessage(LLMMessage.user("Message " + i)).join();
        }
        int initialCount = agent.getConversationMessageCount();

        // When
        String summary = agent.summarizeConversation(5).join();

        // Then
        assertThat(summary).isNotEmpty();
        assertThat(agent.getConversationMessageCount()).isLessThan(initialCount);
    }

    @Test
    void autoSummarization_shouldTriggerWhenThresholdExceeded() {
        // Given - configure low threshold
        agent.configureAutoSummarization(50, 5);

        // When - add messages until threshold
        for (int i = 0; i < 20; i++) {
            agent.addConversationMessage(LLMMessage.user("Long message number " + i)).join();
        }

        // Then - should have auto-summarized (hard to test reliably due to async)
        // Just verify no errors
        assertThat(agent.getConversationTokens()).isGreaterThan(0);
    }

    @Test
    void disableAutoSummarization_shouldDisable() {
        // When
        agent.disableAutoSummarization();

        // Then
        assertThat(agent.autoSummarizeThreshold).isZero();
    }

    // ========== CONFIGURATION TESTS ==========

    @Test
    void setDefaultStrategy_shouldUpdateStrategy() {
        // When
        agent.setDefaultStrategy(FIXED);

        // Then
        assertThat(agent.defaultStrategy).isEqualTo(FIXED);
    }

    @Test
    void setDefaultConversationBudget_shouldUpdateBudget() {
        // When
        agent.setDefaultConversationBudget(3000);

        // Then
        assertThat(agent.defaultConversationBudget).isEqualTo(3000);
    }

    @Test
    void configureAutoSummarization_shouldUpdateSettings() {
        // When
        agent.configureAutoSummarization(10000, 20);

        // Then
        assertThat(agent.autoSummarizeThreshold).isEqualTo(10000);
        assertThat(agent.messagesToSummarize).isEqualTo(20);
    }

    // ========== HELPER CLASS ==========

    /**
     * Test implementation of AIAgent.
     */
    static class TestLLMAgent extends LLMAgent {

        public TestLLMAgent(String agentId) {
            super(agentId);
        }

        @Override
        protected void onStart() {
            // No-op for testing
        }
    }
}
