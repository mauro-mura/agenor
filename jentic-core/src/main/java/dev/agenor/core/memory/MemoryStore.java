package dev.agenor.core.memory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Core interface for agent memory storage.
 *
 * <p>Provides persistent and volatile storage for agent memories with support for:
 * <ul>
 *   <li>Short-term (volatile) and long-term (persistent) memory scopes</li>
 *   <li>Full-text search and metadata filtering</li>
 *   <li>Shared memory across multiple agents</li>
 *   <li>Automatic expiration of time-limited memories</li>
 * </ul>
 *
 * <p>All operations are asynchronous and return {@link CompletableFuture} for
 * non-blocking execution.
 *
 * <p><b>Thread Safety:</b> All implementations must be thread-safe.
 *
 * <p><b>Implementation Notes:</b>
 * <ul>
 *   <li>SHORT_TERM memories may be stored in-memory for fast access</li>
 *   <li>LONG_TERM memories should be persisted to durable storage</li>
 *   <li>Expired entries should be automatically removed during queries</li>
 *   <li>Search operations should support both exact and fuzzy matching</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * MemoryStore store = new InMemoryStore();
 *
 * // Store a memory
 * MemoryEntry entry = MemoryEntry.builder("User prefers email notifications")
 *     .ownerId("notification-agent")
 *     .metadata("preference", "email")
 *     .build();
 *
 * store.store("pref:user123:notification", entry, MemoryScope.LONG_TERM)
 *     .thenRun(() -> System.out.println("Memory stored"));
 *
 * // Retrieve a memory
 * store.retrieve("pref:user123:notification", MemoryScope.LONG_TERM)
 *     .thenAccept(opt -> opt.ifPresent(e ->
 *         System.out.println("Found: " + e.content())));
 *
 * // Search memories
 * MemoryQuery query = MemoryQuery.builder()
 *     .text("notification")
 *     .scope(MemoryScope.LONG_TERM)
 *     .ownerId("notification-agent")
 *     .limit(10)
 *     .build();
 *
 * store.search(query)
 *     .thenAccept(results ->
 *         System.out.println("Found " + results.size() + " memories"));
 * }</pre>
 *
 * @since 0.6.0
 * @see MemoryEntry
 * @see MemoryQuery
 * @see MemoryScope
 */
public interface MemoryStore {

    /**
     * Stores a memory entry.
     *
     * <p>If an entry with the same key and scope already exists, it will be replaced.
     *
     * @param key unique identifier for this memory
     * @param entry the memory entry to store
     * @param scope the memory scope (SHORT_TERM or LONG_TERM)
     * @return a future that completes when the entry is stored
     * @throws MemoryException if the storage operation fails
     * @throws IllegalArgumentException if key is null or blank
     */
    CompletableFuture<Void> store(String key, MemoryEntry entry, MemoryScope scope);

    /**
     * Retrieves a memory entry by key.
     *
     * <p>Expired entries are automatically filtered out and return empty.
     *
     * @param key the memory key
     * @param scope the memory scope to search in
     * @return a future containing the memory entry, or empty if not found
     * @throws MemoryException if the retrieval operation fails
     * @throws IllegalArgumentException if key is null or blank
     */
    CompletableFuture<Optional<MemoryEntry>> retrieve(String key, MemoryScope scope);

    /**
     * Searches for memory entries matching the query criteria.
     *
     * <p>Results are filtered by:
     * <ul>
     *   <li>Text content (if specified)</li>
     *   <li>Owner ID (if specified)</li>
     *   <li>Metadata filters (if specified)</li>
     *   <li>Non-expired entries only</li>
     * </ul>
     *
     * <p>Results may be returned in any order unless the implementation
     * specifies otherwise.
     *
     * @param query the search query parameters
     * @return a future containing matching memory entries (may be empty)
     * @throws MemoryException if the search operation fails
     * @throws IllegalArgumentException if query is null
     */
    CompletableFuture<List<MemoryEntry>> search(MemoryQuery query);

    /**
     * Deletes a memory entry.
     *
     * <p>If the entry does not exist, this operation completes successfully
     * without error (idempotent).
     *
     * @param key the memory key to delete
     * @param scope the memory scope
     * @return a future that completes when the entry is deleted
     * @throws MemoryException if the deletion operation fails
     * @throws IllegalArgumentException if key is null or blank
     */
    CompletableFuture<Void> delete(String key, MemoryScope scope);

    /**
     * Clears all memory entries in the specified scope.
     *
     * <p><b>Warning:</b> This operation cannot be undone. Use with caution.
     *
     * @param scope the memory scope to clear
     * @return a future that completes when all entries are cleared
     * @throws MemoryException if the clear operation fails
     */
    CompletableFuture<Void> clear(MemoryScope scope);

    /**
     * Lists all memory keys in the specified scope.
     *
     * <p>This operation may be expensive for large memory stores.
     * Consider using {@link #search(MemoryQuery)} with pagination instead.
     *
     * @param scope the memory scope
     * @return a future containing all keys (may be empty)
     * @throws MemoryException if the list operation fails
     */
    CompletableFuture<List<String>> listKeys(MemoryScope scope);

    /**
     * Gets statistics about memory usage.
     *
     * <p>Statistics may be cached and not reflect the absolute current state.
     *
     * @return memory usage statistics
     */
    default MemoryStats getStats() {
        return MemoryStats.empty();
    }

    /**
     * Checks if a memory entry exists.
     *
     * <p>This is more efficient than {@link #retrieve(String, MemoryScope)}
     * when only existence needs to be checked.
     *
     * @param key the memory key
     * @param scope the memory scope
     * @return a future containing true if the entry exists and is not expired
     * @throws MemoryException if the check operation fails
     */
    default CompletableFuture<Boolean> exists(String key, MemoryScope scope) {
        return retrieve(key, scope)
            .thenApply(Optional::isPresent);
    }

    /**
     * Gets the name of this memory store implementation.
     *
     * <p>Used for logging and monitoring.
     *
     * @return the store name (e.g., "InMemoryStore", "DatabaseStore")
     */
    default String getStoreName() {
        return getClass().getSimpleName();
    }
}
