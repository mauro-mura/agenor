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

/**
 * Default implementation of LLMMemoryManager.
 *
 * @since 0.6.0
 */
public class DefaultLLMMemoryManager implements LLMMemoryManager {

    private final MemoryStore memoryStore;
    private final TokenEstimator tokenEstimator;
    private final String agentId;

    /** Serializes writers. A summary reads and rewrites the whole list, which must be one step. */
    private final Object historyLock = new Object();

    /** Never changed once published: a writer builds the next list and swaps it in under the lock. */
    private volatile List<LLMMessage> conversationHistory = List.of();

    /** The message the last summary wrote, compared by identity. Guarded by {@link #historyLock}. */
    private LLMMessage currentSummary;
    private int summarizedUserMessages;
    private int summarizedAssistantMessages;
    private int summarizedTokens;

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
    }

    @Override
    public CompletableFuture<Void> addMessage(LLMMessage message) {
        Objects.requireNonNull(message, "Message cannot be null");
        return CompletableFuture.runAsync(() -> append(List.of(message)));
    }

    private void append(List<LLMMessage> messages) {
        synchronized (historyLock) {
            List<LLMMessage> next = new ArrayList<>(conversationHistory.size() + messages.size());
            next.addAll(conversationHistory);
            next.addAll(messages);
            conversationHistory = next;
        }
    }

    @Override
    public CompletableFuture<Void> addMessages(List<LLMMessage> messages) {
        Objects.requireNonNull(messages, "Messages cannot be null");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("Messages cannot be empty");
        }
        return CompletableFuture.runAsync(() -> append(messages));
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
            synchronized (historyLock) {
                return replaceOldestWithSummary(messagesToSummarize);
            }
        });
    }

    /**
     * Replaces the oldest messages with one summary, which holds counts and none of their content.
     *
     * <p>System messages are left where they are and do not count towards {@code count}: the
     * first of them is usually the prompt, and a window can only select what is still here. The
     * previous summary is replaced rather than kept beside the new one, so that repeated passes
     * do not grow the list, and what it had counted carries over. Caller holds {@link #historyLock}.
     */
    private String replaceOldestWithSummary(int count) {
        List<LLMMessage> kept = new ArrayList<>(conversationHistory.size());
        int taken = 0;
        int users = 0;
        int assistants = 0;
        int tokens = 0;

        for (LLMMessage message : conversationHistory) {
            if (message == currentSummary) {
                continue;
            }
            if (message.role() == LLMMessage.Role.SYSTEM || taken == count) {
                kept.add(message);
                continue;
            }
            taken++;
            if (message.role() == LLMMessage.Role.USER) {
                users++;
            } else if (message.role() == LLMMessage.Role.ASSISTANT) {
                assistants++;
            }
            tokens += tokenEstimator.estimateTokens(message);
        }

        if (taken < count) {
            throw new IllegalStateException(
                "Not enough messages to summarize. Have " + taken + ", requested " + count
            );
        }

        summarizedUserMessages += users;
        summarizedAssistantMessages += assistants;
        summarizedTokens += tokens;
        String summary = String.format(
            "Earlier conversation with %d user messages and %d assistant responses (~%d tokens)",
            summarizedUserMessages, summarizedAssistantMessages, summarizedTokens
        );

        int leadingSystemMessages = 0;
        while (leadingSystemMessages < kept.size()
                && kept.get(leadingSystemMessages).role() == LLMMessage.Role.SYSTEM) {
            leadingSystemMessages++;
        }
        currentSummary = LLMMessage.system("Previous conversation summary: " + summary);
        kept.add(leadingSystemMessages, currentSummary);
        conversationHistory = kept;

        return summary;
    }

    @Override
    public CompletableFuture<Void> clearConversationHistory() {
        return CompletableFuture.runAsync(() -> {
            synchronized (historyLock) {
                conversationHistory = List.of();
                currentSummary = null;
                summarizedUserMessages = 0;
                summarizedAssistantMessages = 0;
                summarizedTokens = 0;
            }
        });
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
