package dev.agenor.runtime.memory.llm;

import dev.agenor.core.llm.LLMMessage;
import dev.agenor.core.memory.MemoryEntry;
import dev.agenor.core.memory.MemoryScope;
import dev.agenor.core.memory.MemoryStore;
import dev.agenor.core.memory.llm.LLMMemoryManager;
import dev.agenor.core.memory.llm.ModelTokenLimits;
import dev.agenor.runtime.memory.InMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.agenor.runtime.memory.llm.ContextWindowStrategies.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for complete LLM Memory Management system.
 *
 * <p>These tests demonstrate the full integration of:
 * <ul>
 *   <li>Week 1: MemoryStore (InMemoryStore)</li>
 *   <li>Day 1: Core interfaces (LLMMemoryManager, TokenEstimator, ContextWindowStrategy)</li>
 *   <li>Day 2: Token management (SimpleTokenEstimator, ModelTokenLimits)</li>
 *   <li>Day 3: Context strategies (Fixed, Sliding, Summarized)</li>
 *   <li>Day 4: DefaultLLMMemoryManager implementation</li>
 * </ul>
 */
@DisplayName("LLM Memory Management Integration Tests")
class LLMMemoryIntegrationTest {

    private MemoryStore memoryStore;
    private SimpleTokenEstimator tokenEstimator;
    private LLMMemoryManager llmMemory;

    @BeforeEach
    void setUp() {
        // Week 1: InMemoryStore
        memoryStore = new InMemoryStore();

        // Day 2: SimpleTokenEstimator
        tokenEstimator = new SimpleTokenEstimator();

        // Day 4: DefaultLLMMemoryManager
        llmMemory = new DefaultLLMMemoryManager(
            memoryStore,
            tokenEstimator,
            "integration-test-agent"
        );
    }

    @Test
    @DisplayName("Should handle complete conversation workflow")
    void shouldHandleCompleteConversationWorkflow() {
        // Given - Start a conversation
        llmMemory.addMessage(LLMMessage.system("You are a helpful assistant.")).join();
        llmMemory.addMessage(LLMMessage.user("Hi, my name is Alice.")).join();
        llmMemory.addMessage(LLMMessage.assistant("Hello Alice! Nice to meet you.")).join();
        llmMemory.addMessage(LLMMessage.user("I'm from Paris and I love blue.")).join();
        llmMemory.addMessage(LLMMessage.assistant("That's wonderful! Paris is beautiful.")).join();

        // When - Get conversation history with token budget
        List<LLMMessage> history = llmMemory.getConversationHistory(1000, FIXED).join();

        // Then
        assertThat(history).isNotEmpty();
        assertThat(llmMemory.getMessageCount()).isEqualTo(5);
        assertThat(llmMemory.getCurrentTokenCount()).isGreaterThan(0);

        // Verify token budget is respected
        int totalTokens = tokenEstimator.estimateTokens(history);
        assertThat(totalTokens).isLessThanOrEqualTo(1000);
    }

    @Test
    @DisplayName("Should extract and store important facts in long-term memory")
    void shouldStoreImportantFactsInLongTermMemory() {
        // Given - Conversation with important facts
        llmMemory.addMessage(LLMMessage.user("My name is Bob")).join();
        llmMemory.addMessage(LLMMessage.assistant("Nice to meet you, Bob!")).join();
        llmMemory.addMessage(LLMMessage.user("I prefer coffee over tea")).join();
        llmMemory.addMessage(LLMMessage.assistant("Noted! You prefer coffee.")).join();

        // When - Extract and store facts in long-term memory
        llmMemory.remember("user-name", "Bob", Map.of("category", "profile")).join();
        llmMemory.remember("drink-preference", "coffee", Map.of("category", "preference")).join();

        // Then - Facts should be retrievable
        List<MemoryEntry> context = llmMemory.retrieveRelevantContext("bob", 500).join();

        assertThat(context).isNotEmpty();
        assertThat(context).anyMatch(e -> e.content().contains("Bob"));
    }

    @Test
    @DisplayName("Should use different context window strategies effectively")
    void shouldUseDifferentStrategiesEffectively() {
        // Given - Long conversation
        for (int i = 0; i < 20; i++) {
            llmMemory.addMessage(LLMMessage.user("Question " + i)).join();
            llmMemory.addMessage(LLMMessage.assistant("Answer " + i)).join();
        }

        // When - Get history with different strategies
        int tokenBudget = 100;

        List<LLMMessage> fixedHistory = llmMemory.getConversationHistory(tokenBudget, FIXED).join();
        List<LLMMessage> slidingHistory = llmMemory.getConversationHistory(tokenBudget, SLIDING).join();
        List<LLMMessage> summarizedHistory = llmMemory.getConversationHistory(tokenBudget, SUMMARIZED).join();

        // Then - All should respect budget but may select different messages
        assertThat(tokenEstimator.estimateTokens(fixedHistory)).isLessThanOrEqualTo(tokenBudget);
        assertThat(tokenEstimator.estimateTokens(slidingHistory)).isLessThanOrEqualTo(tokenBudget);
        assertThat(tokenEstimator.estimateTokens(summarizedHistory)).isLessThanOrEqualTo(tokenBudget);

        // Fixed: most recent
        assertThat(fixedHistory).isNotEmpty();

        // Sliding: may include important older messages
        assertThat(slidingHistory).isNotEmpty();
    }

    @Test
    @DisplayName("Should manage token budgets across conversation and context")
    void shouldManageTokenBudgetsAcrossConversationAndContext() {
        // Given - Add conversation
        for (int i = 0; i < 10; i++) {
            llmMemory.addMessage(LLMMessage.user("Message " + i)).join();
        }

        // Store some facts
        llmMemory.remember("fact1", "Important fact one", Map.of()).join();
        llmMemory.remember("fact2", "Important fact two", Map.of()).join();
        llmMemory.remember("fact3", "Important fact three", Map.of()).join();

        // When - Allocate budget
        int totalBudget = 1000;
        int conversationBudget = 700;
        int contextBudget = 300;

        List<LLMMessage> conversation = llmMemory.getConversationHistory(
            conversationBudget,
            SLIDING
        ).join();

        List<MemoryEntry> context = llmMemory.retrieveRelevantContext(
            "facts",
            contextBudget
        ).join();

        // Then - Should fit within budgets
        int convTokens = tokenEstimator.estimateTokens(conversation);
        int contextTokens = context.stream()
            .mapToInt(e -> e.tokenCount() > 0 ? e.tokenCount() : tokenEstimator.estimateTokens(e.content()))
            .sum();

        assertThat(convTokens).isLessThanOrEqualTo(conversationBudget);
        assertThat(contextTokens).isLessThanOrEqualTo(contextBudget);
        assertThat(convTokens + contextTokens).isLessThanOrEqualTo(totalBudget);
    }

    @Test
    @DisplayName("Should handle summarization of old conversations")
    void shouldSummarizeOldConversations() {
        // Given - Long conversation
        for (int i = 0; i < 15; i++) {
            llmMemory.addMessage(LLMMessage.user("User message " + i)).join();
            llmMemory.addMessage(LLMMessage.assistant("Assistant response " + i)).join();
        }

        int initialCount = llmMemory.getMessageCount();
        // When - Summarize oldest 10 messages
        String summary = llmMemory.summarizeOldMessages(10).join();

        // Then
        assertThat(summary).isNotEmpty();
        assertThat(summary).contains("conversation");

        // Message count reduced (10 removed, 1 summary added)
        assertThat(llmMemory.getMessageCount()).isEqualTo(initialCount - 10 + 1);

        // First message is now the summary
        List<LLMMessage> messages = llmMemory.getAllMessages().join();
        assertThat(messages.get(0).role()).isEqualTo(LLMMessage.Role.SYSTEM);
        assertThat(messages.get(0).content()).contains("summary");
    }

    @Test
    @DisplayName("Should separate short-term and long-term memory")
    void shouldSeparateShortTermAndLongTermMemory() {
        // Given - Add to both memories
        llmMemory.addMessage(LLMMessage.user("Hi")).join();
        llmMemory.addMessage(LLMMessage.assistant("Hello")).join();

        llmMemory.remember("permanent-fact", "This is important", Map.of()).join();

        // When - Clear conversation (short-term)
        llmMemory.clearConversationHistory().join();

        // Then - Short-term cleared, long-term preserved
        assertThat(llmMemory.getMessageCount()).isZero();
        assertThat(llmMemory.getAllMessages().join()).isEmpty();

        // Long-term memory still accessible
        MemoryEntry entry = memoryStore.retrieve("permanent-fact", MemoryScope.LONG_TERM).join().orElse(null);
        assertThat(entry).isNotNull();
        assertThat(entry.content()).isEqualTo("This is important");
    }

    @Test
    @DisplayName("Should integrate with model token limits")
    void shouldIntegrateWithModelTokenLimits() {
        // Given - Add many messages with substantial content
        for (int i = 0; i < 50; i++) {
            llmMemory.addMessage(LLMMessage.user(
                "Message " + i + ": This is a longer message with more content " +
                "to demonstrate token limit differences between models. " +
                "Each message should consume a meaningful number of tokens."
            )).join();
        }

        // Use smaller budgets to show the difference
        List<LLMMessage> gpt4History = llmMemory.getConversationHistory(
            1000,  // Small budget for GPT-4
            SLIDING
        ).join();

        List<LLMMessage> claudeHistory = llmMemory.getConversationHistory(
            3000,  // Larger budget for Claude
            SLIDING
        ).join();

        // Then - Claude can fit more messages with larger budget
        assertThat(gpt4History.size()).isLessThan(claudeHistory.size());
    }

    @Test
    @DisplayName("Should build complete LLM prompt with conversation and context")
    void shouldBuildCompleteLLMPromptWithConversationAndContext() {
        // Given - Conversation
        llmMemory.addMessage(LLMMessage.system("You are helpful")).join();
        llmMemory.addMessage(LLMMessage.user("Hi, I'm Alice")).join();
        llmMemory.addMessage(LLMMessage.assistant("Hello Alice!")).join();

        // Long-term facts
        llmMemory.remember("user-name", "Alice", Map.of()).join();
        llmMemory.remember("user-location", "Paris", Map.of()).join();

        // When - Build prompt for new question
        LLMMessage newQuestion = LLMMessage.user("What's my name?");

        // Get conversation history
        List<LLMMessage> history = llmMemory.getConversationHistory(1500, SLIDING).join();

        // Get relevant context
        List<MemoryEntry> context = llmMemory.retrieveRelevantContext("alice", 500).join();

        // Build full prompt
        List<LLMMessage> prompt = new ArrayList<>();

        // Add context as system message
        if (!context.isEmpty()) {
            String contextStr = context.stream()
                .map(e -> e.content())
                .collect(java.util.stream.Collectors.joining(", "));
            prompt.add(LLMMessage.system("Relevant facts: " + contextStr));
        }

        // Add conversation history
        prompt.addAll(history);

        // Add new question
        prompt.add(newQuestion);

        // Then - Prompt should be complete
        assertThat(prompt).isNotEmpty();
        assertThat(prompt).contains(newQuestion);

        // Should contain both conversation and context
        boolean hasConversation = prompt.stream()
            .anyMatch(m -> m.content().contains("Alice"));
        boolean hasContext = prompt.stream()
            .anyMatch(m -> m.content().contains("Relevant facts"));

        assertThat(hasConversation).isTrue();
        assertThat(hasContext).isTrue();

        // Total tokens should be reasonable
        int totalTokens = tokenEstimator.estimateTokens(prompt);
        assertThat(totalTokens).isLessThan(ModelTokenLimits.getLimit("gpt-4"));
    }

    @Test
    @DisplayName("Should handle agent lifecycle")
    void shouldHandleAgentLifecycle() {
        // Given - Agent starts conversation
        llmMemory.addMessage(LLMMessage.system("You are helpful")).join();

        // Agent has multiple interactions
        for (int i = 0; i < 5; i++) {
            llmMemory.addMessage(LLMMessage.user("Question " + i)).join();
            llmMemory.addMessage(LLMMessage.assistant("Answer " + i)).join();

            // Store important facts
            if (i % 2 == 0) {
                llmMemory.remember("fact-" + i, "Important fact " + i, Map.of()).join();
            }
        }

        assertThat(llmMemory.getMessageCount()).isEqualTo(11);  // 1 system + 10 messages

        // Agent restarts (conversation cleared, but facts remain)
        llmMemory.clearConversationHistory().join();

        assertThat(llmMemory.getMessageCount()).isZero();

        // Facts still accessible
        List<MemoryEntry> facts = llmMemory.retrieveRelevantContext("fact", 1000).join();
        assertThat(facts).isNotEmpty();

        // Agent continues with new conversation
        llmMemory.addMessage(LLMMessage.user("What do you remember about me?")).join();

        // Can retrieve past facts
        List<MemoryEntry> context = llmMemory.retrieveRelevantContext("fact", 500).join();
        assertThat(context).isNotEmpty();
    }
}
