package dev.agenor.runtime.memory;

import dev.agenor.core.memory.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * In-memory implementation of MemoryStore.
 *
 * <p>This implementation:
 * <ul>
 *   <li>Stores memories in concurrent hash maps (thread-safe)</li>
 *   <li>Automatically cleans up expired entries</li>
 *   <li>Provides fast lookups and searches</li>
 *   <li>Does NOT persist to disk (volatile storage)</li>
 * </ul>
 *
 * <p>Suitable for:
 * <ul>
 *   <li>Development and testing</li>
 *   <li>Short-lived processes</li>
 *   <li>Caching layer</li>
 *   <li>Single-instance deployments</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * MemoryStore store = new InMemoryStore();
 *
 * MemoryEntry entry = MemoryEntry.builder("Important fact")
 *     .ownerId("my-agent")
 *     .build();
 *
 * store.store("fact-1", entry, MemoryScope.SHORT_TERM).join();
 * }</pre>
 *
 * @since 0.6.0
 */
public class InMemoryStore implements MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryStore.class);

    private final Map<String, MemoryEntry> shortTermStore = new ConcurrentHashMap<>();
    private final Map<String, MemoryEntry> longTermStore = new ConcurrentHashMap<>();

    private final ScheduledExecutorService cleanupExecutor;
    private final int maxEntriesPerScope;
    private final long cleanupIntervalSeconds;

    private volatile MemoryStats cachedStats;
    private volatile long lastStatsUpdate;

    /**
     * Creates an InMemoryStore with default configuration.
     */
    public InMemoryStore() {
        this(10000, 60);
    }

    /**
     * Creates an InMemoryStore with custom configuration.
     *
     * @param maxEntriesPerScope maximum entries per scope (0 = unlimited)
     * @param cleanupIntervalSeconds interval for cleanup task in seconds
     */
    public InMemoryStore(int maxEntriesPerScope, long cleanupIntervalSeconds) {
        this.maxEntriesPerScope = maxEntriesPerScope;
        this.cleanupIntervalSeconds = cleanupIntervalSeconds;
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "memory-cleanup");
            t.setDaemon(true);
            return t;
        });

        startCleanupTask();
        log.info("InMemoryStore initialized: maxEntries={}, cleanupInterval={}s",
                 maxEntriesPerScope, cleanupIntervalSeconds);
    }

    @Override
    public CompletableFuture<Void> store(String key, MemoryEntry entry, MemoryScope scope) {
        return CompletableFuture.runAsync(() -> {
            validateKey(key);
            Objects.requireNonNull(entry, "Entry cannot be null");
            Objects.requireNonNull(scope, "Scope cannot be null");

            Map<String, MemoryEntry> store = getStore(scope);

            // Check quota
            if (maxEntriesPerScope > 0 && store.size() >= maxEntriesPerScope) {
                if (!store.containsKey(key)) {
                    throw MemoryException.quotaExceeded(
                        getStoreName(),
                        String.format("Maximum entries exceeded: %d", maxEntriesPerScope)
                    );
                }
            }

            store.put(key, entry);
            invalidateStatsCache();

            log.debug("Stored memory: key={}, scope={}, ownerId={}",
                     key, scope, entry.ownerId());
        });
    }

    @Override
    public CompletableFuture<Optional<MemoryEntry>> retrieve(String key, MemoryScope scope) {
        return CompletableFuture.supplyAsync(() -> {
            validateKey(key);
            Objects.requireNonNull(scope, "Scope cannot be null");

            Map<String, MemoryEntry> store = getStore(scope);
            MemoryEntry entry = store.get(key);

            // Check expiration
            if (entry != null && entry.isExpired()) {
                store.remove(key);
                invalidateStatsCache();
                log.debug("Removed expired memory: key={}, scope={}", key, scope);
                return Optional.empty();
            }

            return Optional.ofNullable(entry);
        });
    }

    @Override
    public CompletableFuture<List<MemoryEntry>> search(MemoryQuery query) {
        return CompletableFuture.supplyAsync(() -> {
            Objects.requireNonNull(query, "Query cannot be null");

            Map<String, MemoryEntry> store = getStore(query.scope());

            return store.values().stream()
                .filter(entry -> matchesQuery(entry, query))
                .limit(query.limit())
                .collect(Collectors.toList());
        });
    }

    @Override
    public CompletableFuture<Void> delete(String key, MemoryScope scope) {
        return CompletableFuture.runAsync(() -> {
            validateKey(key);
            Objects.requireNonNull(scope, "Scope cannot be null");

            Map<String, MemoryEntry> store = getStore(scope);
            MemoryEntry removed = store.remove(key);

            if (removed != null) {
                invalidateStatsCache();
                log.debug("Deleted memory: key={}, scope={}", key, scope);
            }
        });
    }

    @Override
    public CompletableFuture<Void> clear(MemoryScope scope) {
        return CompletableFuture.runAsync(() -> {
            Objects.requireNonNull(scope, "Scope cannot be null");

            Map<String, MemoryEntry> store = getStore(scope);
            int size = store.size();
            store.clear();
            invalidateStatsCache();

            log.info("Cleared {} entries from scope: {}", size, scope);
        });
    }

    @Override
    public CompletableFuture<List<String>> listKeys(MemoryScope scope) {
        return CompletableFuture.supplyAsync(() -> {
            Objects.requireNonNull(scope, "Scope cannot be null");

            Map<String, MemoryEntry> store = getStore(scope);
            return new ArrayList<>(store.keySet());
        });
    }

    @Override
    public MemoryStats getStats() {
        long now = System.currentTimeMillis();

        // Return cached stats if recent (within 1 second)
        if (cachedStats != null && (now - lastStatsUpdate) < 1000) {
            return cachedStats;
        }

        // Compute fresh stats
        int shortTermCount = shortTermStore.size();
        int longTermCount = longTermStore.size();

        int estimatedTokens = estimateTokens(shortTermStore) + estimateTokens(longTermStore);
        long estimatedSize = estimateSize(shortTermStore) + estimateSize(longTermStore);

        cachedStats = MemoryStats.builder()
            .shortTermCount(shortTermCount)
            .longTermCount(longTermCount)
            .estimatedTokens(estimatedTokens)
            .estimatedSizeBytes(estimatedSize)
            .lastUpdated(Instant.now())
            .build();

        lastStatsUpdate = now;
        return cachedStats;
    }

    /**
     * Shuts down the cleanup executor.
     * Should be called when the store is no longer needed.
     */
    public void shutdown() {
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
            log.info("InMemoryStore shut down successfully");
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ========== PRIVATE HELPERS ==========

    private Map<String, MemoryEntry> getStore(MemoryScope scope) {
        return scope == MemoryScope.SHORT_TERM ? shortTermStore : longTermStore;
    }

    private void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw MemoryException.validationError("Key cannot be null or blank");
        }
        if (key.length() > 255) {
            throw MemoryException.validationError(
                "Key length cannot exceed 255 characters: " + key.length()
            );
        }
    }

    private boolean matchesQuery(MemoryEntry entry, MemoryQuery query) {
        // Check expiration first
        if (entry.isExpired()) {
            return false;
        }

        // Text filter
        if (query.hasTextFilter()) {
            String text = query.text().toLowerCase();
            if (!entry.content().toLowerCase().contains(text)) {
                return false;
            }
        }

        // Owner filter
        if (query.hasOwnerFilter()) {
            if (!query.ownerId().equals(entry.ownerId())) {
                return false;
            }
        }

        // Metadata filters
        if (query.hasMetadataFilters()) {
            for (Map.Entry<String, Object> filter : query.filters().entrySet()) {
                Object entryValue = entry.metadata().get(filter.getKey());
                if (!Objects.equals(entryValue, filter.getValue())) {
                    return false;
                }
            }
        }

        return true;
    }

    private void startCleanupTask() {
        cleanupExecutor.scheduleAtFixedRate(
            this::cleanupExpiredEntries,
            cleanupIntervalSeconds,
            cleanupIntervalSeconds,
            TimeUnit.SECONDS
        );

        log.debug("Started cleanup task with interval: {}s", cleanupIntervalSeconds);
    }

    private void cleanupExpiredEntries() {
        try {
            int removed = removeExpiredFrom(shortTermStore) + removeExpiredFrom(longTermStore);

            if (removed > 0) {
                invalidateStatsCache();
                log.debug("Cleanup removed {} expired entries", removed);
            }
        } catch (Exception e) {
            log.error("Error during cleanup task", e);
        }
    }

    private int removeExpiredFrom(Map<String, MemoryEntry> store) {
        int removed = 0;
        Iterator<Map.Entry<String, MemoryEntry>> iterator = store.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<String, MemoryEntry> entry = iterator.next();
            if (entry.getValue().isExpired()) {
                iterator.remove();
                removed++;
            }
        }

        return removed;
    }

    private void invalidateStatsCache() {
        cachedStats = null;
    }

    private int estimateTokens(Map<String, MemoryEntry> store) {
        return store.values().stream()
            .mapToInt(entry -> entry.content().length() / 4)
            .sum();
    }

    private long estimateSize(Map<String, MemoryEntry> store) {
        return store.values().stream()
            .mapToLong(entry -> {
                // Rough estimate: content + metadata
                long contentSize = entry.content().length() * 2L; // UTF-16 chars
                long metadataSize = entry.metadata().size() * 64L; // rough estimate
                return contentSize + metadataSize + 128; // overhead
            })
            .sum();
    }
}
