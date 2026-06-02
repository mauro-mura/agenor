package dev.agenor.core.persistence;

import dev.agenor.core.AgentStatus;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;

/**
 * Immutable record representing the complete persisted state of an agent.
 *
 * <p>This record contains all information necessary to restore an agent to
 * its previous state after a restart, failure, or migration. It serves as
 * the primary data structure for agent persistence operations.
 *
 * <p><strong>State Components:</strong>
 * <table border="1">
 *   <caption>Agent state components and properties</caption>
 *   <tr>
 *     <th>Component</th>
 *     <th>Purpose</th>
 *     <th>Example</th>
 *   </tr>
 *   <tr>
 *     <td>Identity (agentId, agentName, agentType)</td>
 *     <td>Identify the agent</td>
 *     <td>id: "order-proc-1", name: "Order Processor"</td>
 *   </tr>
 *   <tr>
 *     <td>Status</td>
 *     <td>Current operational state</td>
 *     <td>RUNNING, IDLE, ERROR</td>
 *   </tr>
 *   <tr>
 *     <td>Data</td>
 *     <td>Business state (flexible Map)</td>
 *     <td>orders processed, current order, counters</td>
 *   </tr>
 *   <tr>
 *     <td>Metadata</td>
 *     <td>Technical state (String-only)</td>
 *     <td>configuration, timestamps, flags</td>
 *   </tr>
 *   <tr>
 *     <td>Version</td>
 *     <td>Optimistic concurrency control</td>
 *     <td>Incremented on each save</td>
 *   </tr>
 *   <tr>
 *     <td>Timestamp (savedAt)</td>
 *     <td>When state was persisted</td>
 *     <td>Used for audit and debugging</td>
 *   </tr>
 * </table>
 *
 * <p><strong>Data vs Metadata:</strong>
 * <ul>
 *   <li><strong>data</strong>: Business state with any Object values
 *       (String, Integer, List, custom POJOs, etc.). This is the primary
 *       application state that agents need to preserve.</li>
 *   <li><strong>metadata</strong>: Technical/system state with String values only.
 *       Used for configuration, feature flags, timestamps, etc.</li>
 * </ul>
 *
 * <p><strong>Versioning:</strong>
 * The {@code version} field enables optimistic locking to prevent lost updates
 * when multiple processes try to save state concurrently:
 * <pre>{@code
 * // Load current state
 * AgentState current = persistence.load("agent-1").join();
 *
 * // Modify state
 * AgentState modified = AgentState.builder(current.agentId())
 *     .data(current.data())
 *     .data("counter", current.getData("counter", Integer.class) + 1)
 *     .version(current.version() + 1)  // Increment version
 *     .build();
 *
 * // Save - will fail if another process saved in the meantime
 * try {
 *     persistence.save(modified).join();
 * } catch (ConcurrentModificationException e) {
 *     // Retry with fresh state
 * }
 * }</pre>
 *
 * <p><strong>Serialization:</strong>
 * This record is designed to be easily serializable to various formats:
 * <ul>
 *   <li><strong>JSON</strong> - Via Jackson annotations (default)</li>
 *   <li><strong>Binary</strong> - Via Java serialization (Serializable)</li>
 *   <li><strong>Database</strong> - Map to columns/documents</li>
 * </ul>
 *
 * <p><strong>Immutability:</strong>
 * All collections ({@code data}, {@code metadata}) are defensively copied
 * to ensure immutability. Use the builder pattern to create modified copies.
 *
 * <p><strong>Example Usage:</strong>
 * <pre>{@code
 * // Create initial state
 * AgentState state = AgentState.builder("order-processor-1")
 *     .agentName("Order Processor #1")
 *     .agentType("processor")
 *     .status(AgentStatus.RUNNING)
 *     .data("ordersProcessed", 0)
 *     .data("currentBatch", List.of())
 *     .metadata("startTime", Instant.now().toString())
 *     .metadata("environment", "production")
 *     .build();
 *
 * // Save state
 * persistenceService.save(state).join();
 *
 * // Later, load and update state
 * AgentState loaded = persistenceService.load("order-processor-1").join();
 * int processed = loaded.getData("ordersProcessed", Integer.class);
 *
 * AgentState updated = AgentState.builder(loaded.agentId())
 *     .agentName(loaded.agentName())
 *     .agentType(loaded.agentType())
 *     .status(loaded.status())
 *     .data(loaded.data())  // Copy existing data
 *     .data("ordersProcessed", processed + 10)  // Update counter
 *     .metadata(loaded.metadata())  // Copy existing metadata
 *     .version(loaded.version() + 1)  // Increment version
 *     .build();
 *
 * persistenceService.save(updated).join();
 * }</pre>
 *
 * <p><strong>Recovery Pattern:</strong>
 * <pre>{@code
 * // On agent startup, restore previous state
 * public class OrderProcessorAgent extends BaseAgent implements Stateful {
 *
 *     @Override
 *     public CompletableFuture<Void> start() {
 *         return persistenceService.load(getAgentId())
 *             .thenCompose(state -> {
 *                 if (state != null) {
 *                     restoreState(state);
 *                     log.info("Restored state from {}", state.savedAt());
 *                 }
 *                 return super.start();
 *             });
 *     }
 *
 *     private void restoreState(AgentState state) {
 *         this.ordersProcessed = state.getData("ordersProcessed", Integer.class);
 *         this.currentBatch = state.getData("currentBatch", List.class);
 *         // Restore other fields...
 *     }
 * }
 * }</pre>
 *
 * @param agentId unique identifier for the agent, must not be null
 * @param agentName human-readable display name, may be null
 * @param agentType logical type/category of the agent (e.g., "processor", "monitor"),
 *        defaults to "unknown" if null
 * @param status current operational status, defaults to UNKNOWN if null
 * @param data application-specific state data with Object values, never null (empty map if not provided)
 * @param metadata system/technical metadata with String values, never null (empty map if not provided)
 * @param version optimistic locking version number, incremented on each save
 * @param savedAt timestamp when this state was persisted, defaults to current time if null
 *
 * @since 0.1.0
 * @see PersistenceService
 * @see Stateful
 * @see AgentStatus
 */
public record AgentState(
        @JsonProperty("agentId") String agentId,
        @JsonProperty("agentName") String agentName,
        @JsonProperty("agentType") String agentType,
        @JsonProperty("status") AgentStatus status,
        @JsonProperty("data") Map<String, Object> data,
        @JsonProperty("metadata") Map<String, String> metadata,
        @JsonProperty("version") long version,
        @JsonProperty("savedAt") Instant savedAt
) {

    /**
     * Canonical constructor with defensive copying and default values.
     *
     * <p>This constructor ensures:
     * <ul>
     *   <li>Collections are immutable (defensive copy)</li>
     *   <li>Null values get sensible defaults</li>
     *   <li>Invariants are maintained</li>
     * </ul>
     *
     * @throws NullPointerException if agentId is null
     */
    @JsonCreator
    public AgentState(
            @JsonProperty("agentId") String agentId,
            @JsonProperty("agentName") String agentName,
            @JsonProperty("agentType") String agentType,
            @JsonProperty("status") AgentStatus status,
            @JsonProperty("data") Map<String, Object> data,
            @JsonProperty("metadata") Map<String, String> metadata,
            @JsonProperty("version") long version,
            @JsonProperty("savedAt") Instant savedAt
    ) {
        this.agentId = agentId;
        this.agentName = agentName;
        this.agentType = agentType != null ? agentType : "unknown";
        this.status = status != null ? status : AgentStatus.UNKNOWN;
        this.data = data != null ? Map.copyOf(data) : Map.of();
        this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        this.version = version;
        this.savedAt = savedAt != null ? savedAt : Instant.now();
    }

    /**
     * Creates a new builder for constructing {@link AgentState} instances.
     *
     * <p>The builder pattern is the recommended way to create state objects,
     * especially when dealing with multiple fields or creating modified copies
     * of existing state.
     *
     * @param agentId the required agent identifier
     * @return a new builder instance
     * @throws NullPointerException if agentId is null
     */
    public static AgentStateBuilder builder(String agentId) {
        return new AgentStateBuilder(agentId);
    }

    /**
     * Retrieves a typed data value by key.
     *
     * <p>This is a convenience method that performs type casting for you.
     * Use this instead of manually casting values from the {@code data} map.
     *
     * <p><strong>Type Safety:</strong> This method performs an unchecked cast.
     * Ensure you're requesting the correct type, or a {@code ClassCastException}
     * will be thrown at runtime.
     *
     * <p>Example:
     * <pre>{@code
     * Integer count = state.getData("ordersProcessed", Integer.class);
     * List<String> batch = state.getData("currentBatch", List.class);
     * CustomOrder order = state.getData("pendingOrder", CustomOrder.class);
     * }</pre>
     *
     * @param <T> the expected type of the value
     * @param key the data key
     * @param type the class of the expected type (used for clarity, not actual type checking)
     * @return the value cast to type T, or null if the key doesn't exist
     * @throws ClassCastException if the value cannot be cast to type T
     */
    @SuppressWarnings("unchecked")
    public <T> T getData(String key, Class<T> type) {
        Object value = data.get(key);
        if (value == null) {
            return null;
        }
        return (T) value;
    }

    /**
     * Retrieves a metadata value by key.
     *
     * <p>This is a convenience method equivalent to calling
     * {@code state.metadata().get(key)}, but more readable.
     *
     * @param key the metadata key
     * @return the metadata value, or null if the key doesn't exist
     */
    public String getMetadata(String key) {
        return metadata.get(key);
    }

    /**
     * Fluent builder for constructing {@link AgentState} instances.
     *
     * <p>This builder makes it easy to create state objects incrementally
     * and to create modified copies of existing state (useful for updates).
     *
     * <p><strong>Thread Safety:</strong> This builder is NOT thread-safe.
     * Each thread should use its own builder instance.
     */
    public static class AgentStateBuilder {
        private final String agentId;
        private String agentName;
        private String agentType;
        private AgentStatus status;
        private Map<String, Object> data = Map.of();
        private Map<String, String> metadata = Map.of();
        private long version = 1;
        private Instant savedAt;

        private AgentStateBuilder(String agentId) {
            this.agentId = agentId;
        }

        public AgentStateBuilder agentName(String agentName) {
            this.agentName = agentName;
            return this;
        }

        public AgentStateBuilder agentType(String agentType) {
            this.agentType = agentType;
            return this;
        }

        public AgentStateBuilder status(AgentStatus status) {
            this.status = status;
            return this;
        }

        /**
         * Sets all data at once, merging with any previously set data.
         */
        public AgentStateBuilder data(Map<String, Object> data) {
            if (data != null) {
                var allData = new java.util.HashMap<>(this.data);
                allData.putAll(data);
                this.data = Map.copyOf(allData);
            }
            return this;
        }

        /**
         * Adds a single data entry, preserving existing data.
         */
        public AgentStateBuilder data(String key, Object value) {
            if (key != null && value != null) {
                var newData = new java.util.HashMap<>(this.data);
                newData.put(key, value);
                this.data = Map.copyOf(newData);
            }
            return this;
        }

        /**
         * Sets all metadata at once, merging with any previously set metadata.
         */
        public AgentStateBuilder metadata(Map<String, String> metadata) {
            if (metadata != null) {
                var allMetadata = new java.util.HashMap<>(this.metadata);
                allMetadata.putAll(metadata);
                this.metadata = Map.copyOf(allMetadata);
            }
            return this;
        }

        /**
         * Adds a single metadata entry, preserving existing metadata.
         */
        public AgentStateBuilder metadata(String key, String value) {
            if (key != null && value != null) {
                var newMetadata = new java.util.HashMap<>(this.metadata);
                newMetadata.put(key, value);
                this.metadata = Map.copyOf(newMetadata);
            }
            return this;
        }

        public AgentStateBuilder version(long version) {
            this.version = version;
            return this;
        }

        public AgentStateBuilder savedAt(Instant savedAt) {
            this.savedAt = savedAt;
            return this;
        }

        /**
         * Builds an immutable {@link AgentState} with the accumulated values.
         *
         * @return a new immutable state instance
         */
        public AgentState build() {
            return new AgentState(agentId, agentName, agentType, status,
                    data, metadata, version, savedAt);
        }
    }
}
