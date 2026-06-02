package dev.agenor.runtime.memory.llm;

import dev.agenor.core.llm.LLMMessage;
import dev.agenor.core.memory.MemoryEntry;
import dev.agenor.core.memory.MemoryQuery;
import dev.agenor.core.memory.MemoryScope;
import dev.agenor.core.memory.MemoryStore;
import dev.agenor.core.memory.llm.ContextWindowStrategy;
import dev.agenor.core.memory.llm.LLMMemoryManager;
import dev.agenor.core.memory.llm.TokenEstimator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Default implementation of LLMMemoryManager.
 *
 * @since 0.6.0
 */
public class DefaultLLMMemoryManager implements LLMMemoryManager {

    private final MemoryStore memoryStore;
    private final TokenEstimator tokenEstimator;
    private final String agentId;
    private final CopyOnWriteArrayList<LLMMessage> conversationHistory;

    public DefaultLLMMemoryManager(
        MemoryStore memoryStore,
        TokenEstimator tokenEstimator,
        String agentId
    ) {
        Objects.requireNonNull(memoryStore, "MemoryStore cannot be null");
        Objects.requireNonNull(tokenEstimator, "TokenEstimator cannot be null");
        Objects.requireNonNull(agentId, "Agent ID cannot be null");

        if (agentId.trim().isEmpty()) {
            throw new IllegalArgumentException("Agent ID cannot be empty");
        }

        this.memoryStore = memoryStore;
        this.tokenEstimator = tokenEstimator;
        this.agentId = agentId.trim();
        this.conversationHistory = new CopyOnWriteArrayList<>();
    }

    @Override
    public CompletableFuture<Void> addMessage(LLMMessage message) {
        Objects.requireNonNull(message, "Message cannot be null");
        return CompletableFuture.runAsync(() -> conversationHistory.add(message));
    }

    @Override
    public CompletableFuture<Void> addMessages(List<LLMMessage> messages) {
        Objects.requireNonNull(messages, "Messages cannot be null");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("Messages cannot be empty");
        }
        return CompletableFuture.runAsync(() -> conversationHistory.addAll(messages));
    }

    @Override
    public CompletableFuture<List<LLMMessage>> getConversationHistory(
        int maxTokens,
        ContextWindowStrategy strategy
    ) {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("Max tokens must be positive");
        }
        Objects.requireNonNull(strategy, "Strategy cannot be null");

        return CompletableFuture.supplyAsync(() -> {
            List<LLMMessage> allMessages = new ArrayList<>(conversationHistory);
            return strategy.selectMessages(allMessages, maxTokens, tokenEstimator);
        });
    }

    @Override
    public CompletableFuture<List<LLMMessage>> getAllMessages() {
        return CompletableFuture.supplyAsync(() -> new ArrayList<>(conversationHistory));
    }

    @Override
    public CompletableFuture<Void> remember(
        String key,
        String content,
        Map<String, Object> metadata
    ) {
        Objects.requireNonNull(key, "Key cannot be null");
        Objects.requireNonNull(content, "Content cannot be null");
        Objects.requireNonNull(metadata, "Metadata cannot be null");

        if (key.trim().isEmpty()) {
            throw new IllegalArgumentException("Key cannot be empty");
        }
        if (content.trim().isEmpty()) {
            throw new IllegalArgumentException("Content cannot be empty");
        }

        return CompletableFuture.runAsync(() -> {
            int tokens = tokenEstimator.estimateTokens(content);

            MemoryEntry.Builder builder = MemoryEntry.builder(content)
                .ownerId(agentId)
                .tokenCount(tokens)
                .metadata("key", key);

            for (Map.Entry<String, Object> metaEntry : metadata.entrySet()) {
                builder.metadata(metaEntry.getKey(), metaEntry.getValue());
            }

            MemoryEntry entry = builder.build();
            memoryStore.store(key, entry, MemoryScope.LONG_TERM).join();
        });
    }

    @Override
    public CompletableFuture<List<MemoryEntry>> retrieveRelevantContext(
        String query,
        int maxTokens
    ) {
        Objects.requireNonNull(query, "Query cannot be null");
        if (query.trim().isEmpty()) {
            throw new IllegalArgumentException("Query cannot be empty");
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("Max tokens must be positive");
        }

        return CompletableFuture.supplyAsync(() -> {
            MemoryQuery memQuery = MemoryQuery.builder()
                .text(query)
                .scope(MemoryScope.LONG_TERM)
                .ownerId(agentId)
                .limit(50)
                .build();

            List<MemoryEntry> results = memoryStore.search(memQuery).join();
            return filterByTokenBudget(results, maxTokens);
        });
    }

    private List<MemoryEntry> filterByTokenBudget(List<MemoryEntry> entries, int maxTokens) {
        List<MemoryEntry> filtered = new ArrayList<>();
        int currentTokens = 0;

        for (MemoryEntry entry : entries) {
            int entryTokens = entry.tokenCount();
            if (entryTokens == 0) {
                entryTokens = tokenEstimator.estimateTokens(entry.content());
            }

            if (currentTokens + entryTokens <= maxTokens) {
                filtered.add(entry);
                currentTokens += entryTokens;
            } else {
                break;
            }
        }

        return filtered;
    }

    @Override
    public CompletableFuture<String> summarizeOldMessages(int messagesToSummarize) {
        if (messagesToSummarize <= 0) {
            throw new IllegalArgumentException("Messages to summarize must be positive");
        }

        return CompletableFuture.supplyAsync(() -> {
            if (conversationHistory.size() < messagesToSummarize) {
                throw new IllegalStateException(
                    "Not enough messages to summarize. Have " +
                    conversationHistory.size() + ", requested " + messagesToSummarize
                );
            }

            List<LLMMessage> oldMessages = conversationHistory.subList(0, messagesToSummarize);
            String summary = generateSimpleSummary(oldMessages);

            conversationHistory.subList(0, messagesToSummarize).clear();
            conversationHistory.add(0, LLMMessage.system("Previous conversation summary: " + summary));

            return summary;
        });
    }

    private String generateSimpleSummary(List<LLMMessage> messages) {
        int userMessageCount = 0;
        int assistantMessageCount = 0;
        int totalTokens = 0;

        for (LLMMessage msg : messages) {
            if (msg.role() == LLMMessage.Role.USER) {
                userMessageCount++;
            } else if (msg.role() == LLMMessage.Role.ASSISTANT) {
                assistantMessageCount++;
            }
            totalTokens += tokenEstimator.estimateTokens(msg);
        }

        return String.format(
            "Earlier conversation with %d user messages and %d assistant responses (~%d tokens)",
            userMessageCount, assistantMessageCount, totalTokens
        );
    }

    @Override
    public CompletableFuture<Void> clearConversationHistory() {
        return CompletableFuture.runAsync(() -> conversationHistory.clear());
    }

    @Override
    public int getCurrentTokenCount() {
        return tokenEstimator.estimateTokens(new ArrayList<>(conversationHistory));
    }

    @Override
    public int getMessageCount() {
        return conversationHistory.size();
    }

    @Override
    public TokenEstimator getTokenEstimator() {
        return tokenEstimator;
    }

    @Override
    public String getAgentId() {
        return agentId;
    }

    @Override
    public String toString() {
        return String.format(
            "DefaultLLMMemoryManager{agentId='%s', messages=%d, tokens=%d}",
            agentId, getMessageCount(), getCurrentTokenCount()
        );
    }
}
