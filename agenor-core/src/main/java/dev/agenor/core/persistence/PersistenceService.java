package dev.agenor.core.persistence;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Core interface for agent state persistence in the Agenor framework.
 *
 * <p>Provides an asynchronous API for saving, loading, and managing agent state
 * across restarts, failures, or migrations. Implementations are free to use any
 * storage backend (file system, relational database, NoSQL, in-memory, etc.).
 *
 * <p><strong>Supported Operations:</strong>
 * <ul>
 *   <li><strong>CRUD</strong> - save, load, delete, exists</li>
 *   <li><strong>Snapshots</strong> - named point-in-time copies for rollback</li>
 * </ul>
 *
 * <p><strong>Concurrency:</strong> All methods return {@link CompletableFuture}
 * and must be non-blocking. Implementations must be thread-safe; concurrent
 * calls for the same {@code agentId} must be handled safely (e.g., via
 * read-write locks or optimistic versioning).
 *
 * <p><strong>Error Handling:</strong> When an operation fails, the returned
 * future completes exceptionally with a {@link PersistenceException}.
 * Callers should use {@link CompletableFuture#exceptionally} or
 * {@link CompletableFuture#handle} to handle errors.
 *
 * <p><strong>Example — save and restore lifecycle:</strong>
 * <pre>{@code
 * // On agent stop: capture and save state
 * AgentState state = agent.captureState();
 * persistenceService.saveState(agent.getAgentId(), state)
 *     .exceptionally(ex -> {
 *         log.error("Failed to persist state", ex);
 *         return null;
 *     });
 *
 * // On agent start: restore previous state if available
 * persistenceService.loadState(agent.getAgentId())
 *     .thenAccept(opt -> opt.ifPresent(agent::restoreState));
 * }</pre>
 *
 * @since 0.2.0
 * @see AgentState
 * @see Stateful
 * @see PersistenceStrategy
 */
public interface PersistenceService {

	/**
     * Saves (or overwrites) the state of an agent asynchronously.
     *
     * <p>If a state for the given {@code agentId} already exists, it is replaced.
     * Implementations should use the {@link AgentState#version()} field for
     * optimistic concurrency control when the storage backend supports it.
     *
     * <p><strong>Thread Safety:</strong> Concurrent saves for the same {@code agentId}
     * must be serialized to prevent data corruption.
     *
     * @param agentId the unique identifier of the agent, must not be null or blank
     * @param state   the state to persist, must not be null
     * @return a {@link CompletableFuture} that completes when the save is committed,
     *         or completes exceptionally with {@link PersistenceException}
     *         on I/O or serialization failure
     * @throws NullPointerException if {@code agentId} or {@code state} is null
     * @see AgentState#version()
     */
    CompletableFuture<Void> saveState(String agentId, AgentState state);

    /**
     * Loads the persisted state of an agent asynchronously.
     *
     * <p>Returns an empty {@link Optional} if no state has been saved for the
     * given {@code agentId}. Never returns a null future or a future containing
     * {@code null}.
     *
     * @param agentId the unique identifier of the agent, must not be null or blank
     * @return a {@link CompletableFuture} containing the saved state if present,
     *         or an empty {@code Optional} if not found; completes exceptionally
     *         with {@link PersistenceException} on failure
     * @throws NullPointerException if {@code agentId} is null
     */
    CompletableFuture<Optional<AgentState>> loadState(String agentId);

    /**
     * Deletes the persisted state of an agent asynchronously.
     *
     * <p><strong>Idempotency:</strong> If no state exists for the given
     * {@code agentId}, the operation succeeds silently (no-op).
     *
     * <p><strong>Snapshots:</strong> This method deletes only the current state.
     * Snapshots created via {@link #createSnapshot} are not automatically removed;
     * use implementation-specific cleanup or {@link #listSnapshots} to manage them.
     *
     * @param agentId the unique identifier of the agent, must not be null or blank
     * @return a {@link CompletableFuture} that completes when the state is deleted,
     *         or completes exceptionally with {@link PersistenceException}
     *         on failure
     * @throws NullPointerException if {@code agentId} is null
     */
    CompletableFuture<Void> deleteState(String agentId);

    /**
     * Checks whether a persisted state exists for an agent.
     *
     * <p>This is a lightweight probe that avoids deserializing the full state.
     * Prefer this over {@link #loadState} when you only need to know if state
     * exists, not its content.
     *
     * @param agentId the unique identifier of the agent, must not be null or blank
     * @return a {@link CompletableFuture} containing {@code true} if state exists,
     *         {@code false} otherwise; completes exceptionally with
     *         {@link PersistenceException} on failure
     * @throws NullPointerException if {@code agentId} is null
     */
    CompletableFuture<Boolean> existsState(String agentId);

    /**
     * Creates a named snapshot of the current persisted state of an agent.
     *
     * <p>A snapshot is an immutable copy of the state at the time of creation.
     * It can be used to implement rollback, auditing, or checkpoint-based recovery.
     * The current state (as saved via {@link #saveState}) must exist before a
     * snapshot can be created.
     *
     * <p>If {@code snapshotId} is {@code null} or blank, implementations should
     * auto-generate a unique identifier (e.g., timestamp-based).
     *
     * <p>Example:
     * <pre>{@code
     * String snapshotId = persistenceService
     *     .createSnapshot("order-processor-1", null)  // auto-generated ID
     *     .join();
     * log.info("Snapshot created: {}", snapshotId);
     * }</pre>
     *
     * @param agentId    the unique identifier of the agent, must not be null or blank
     * @param snapshotId an optional user-defined snapshot name; if null or blank,
     *                   the implementation generates one
     * @return a {@link CompletableFuture} containing the effective snapshot identifier;
     *         completes exceptionally with {@link PersistenceException}
     *         if the current state does not exist or the operation fails
     * @throws NullPointerException if {@code agentId} is null
     * @see #restoreSnapshot(String, String)
     * @see #listSnapshots(String)
     */
    CompletableFuture<String> createSnapshot(String agentId, String snapshotId);

    /**
     * Restores an agent's state from a previously created snapshot.
     *
     * <p>The restored state is returned but <strong>not</strong> automatically
     * written back as the current state. Callers must explicitly call
     * {@link #saveState} if they want the snapshot to become the active state.
     *
     * <p>Returns an empty {@link Optional} if no snapshot with the given
     * {@code snapshotId} exists for the agent.
     *
     * <p>Example:
     * <pre>{@code
     * persistenceService.restoreSnapshot("order-processor-1", snapshotId)
     *     .thenCompose(opt -> {
     *         if (opt.isPresent()) {
     *             agent.restoreState(opt.get());
     *             return persistenceService.saveState(agent.getAgentId(), opt.get());
     *         }
     *         return CompletableFuture.completedFuture(null);
     *     });
     * }</pre>
     *
     * @param agentId    the unique identifier of the agent, must not be null or blank
     * @param snapshotId the identifier of the snapshot to restore, must not be null or blank
     * @return a {@link CompletableFuture} containing the snapshot state if found,
     *         or an empty {@code Optional} if the snapshot does not exist; completes
     *         exceptionally with {@link PersistenceException}
     *         on failure
     * @throws NullPointerException if {@code agentId} or {@code snapshotId} is null
     * @see #createSnapshot(String, String)
     */
    CompletableFuture<Optional<AgentState>> restoreSnapshot(String agentId, String snapshotId);

    /**
     * Returns the list of snapshot identifiers available for an agent.
     *
     * <p>The order of the returned identifiers is implementation-dependent.
     * Returns an empty list if no snapshots exist; never returns {@code null}.
     *
     * @param agentId the unique identifier of the agent, must not be null or blank
     * @return a {@link CompletableFuture} containing the (possibly empty) list of
     *         snapshot identifiers; completes exceptionally with
     *         {@link PersistenceException} on failure
     * @throws NullPointerException if {@code agentId} is null
     * @see #createSnapshot(String, String)
     * @see #restoreSnapshot(String, String)
     */
    CompletableFuture<java.util.List<String>> listSnapshots(String agentId);
}
