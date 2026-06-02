package dev.agenor.core.condition;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Context for passing additional information to condition evaluation.
 *
 * <p>The {@code ConditionContext} is a flexible container for storing and
 * retrieving arbitrary key-value data during condition evaluation. It provides
 * a mechanism to pass extra information to conditions without modifying the
 * {@link Condition} interface.
 *
 * <p><strong>Primary Use Cases:</strong>
 * <table border="1">
 *   <caption>Primary use cases for condition context</caption>
 *   <tr>
 *     <th>Use Case</th>
 *     <th>Description</th>
 *     <th>Example</th>
 *   </tr>
 *   <tr>
 *     <td>System Metrics</td>
 *     <td>Share system state across conditions</td>
 *     <td>CPU usage, memory, thread count</td>
 *   </tr>
 *   <tr>
 *     <td>Environment Variables</td>
 *     <td>External configuration</td>
 *     <td>Feature flags, thresholds</td>
 *   </tr>
 *   <tr>
 *     <td>Runtime State</td>
 *     <td>Transient execution context</td>
 *     <td>Current user, request ID</td>
 *   </tr>
 *   <tr>
 *     <td>Custom Data</td>
 *     <td>Domain-specific information</td>
 *     <td>Business metrics, cache data</td>
 *   </tr>
 *   <tr>
 *     <td>Performance Cache</td>
 *     <td>Cache expensive lookups</td>
 *     <td>Database queries, API calls</td>
 *   </tr>
 * </table>
 *
 * <p><strong>Thread Safety:</strong>
 * This class is thread-safe. It uses {@link ConcurrentHashMap} internally,
 * allowing concurrent read and write operations from multiple threads without
 * external synchronization.
 *
 * <p><strong>Type Safety:</strong>
 * The context uses {@code Object} for values, requiring casting when retrieving.
 * The {@link #get(String, Class)} method provides type-safe retrieval with
 * automatic casting.
 *
 * <p><strong>Example - System Metrics Sharing:</strong>
 * <pre>{@code
 * // Create context with system metrics
 * ConditionContext context = new ConditionContext();
 * context.set("cpu.usage", SystemMetrics.current().cpuUsage());
 * context.set("memory.usage", SystemMetrics.current().memoryUsage());
 *
 * // Use in condition evaluator
 * ConditionEvaluator evaluator = new ConditionEvaluator(context);
 *
 * // Conditions can access shared metrics
 * Condition cpuCondition = agent -> {
 *     Double cpuUsage = context.get("cpu.usage", Double.class);
 *     return cpuUsage != null && cpuUsage < 80.0;
 * };
 * }</pre>
 *
 * <p><strong>Example - Feature Flags:</strong>
 * <pre>{@code
 * // Configure feature flags
 * ConditionContext context = new ConditionContext();
 * context.set("feature.newAlgorithm", true);
 * context.set("feature.experimentalMode", false);
 * context.set("config.maxQueueSize", 100);
 *
 * // Condition checks feature flag
 * Condition useNewAlgorithm = agent -> {
 *     return context.getOrDefault("feature.newAlgorithm", false);
 * };
 *
 * // Condition checks configuration
 * Condition queueSizeOk = agent -> {
 *     int maxSize = context.getOrDefault("config.maxQueueSize", 50);
 *     return getQueueSize() < maxSize;
 * };
 * }</pre>
 *
 * <p><strong>Example - Performance Optimization:</strong>
 * <pre>{@code
 * // Cache expensive lookups in context
 * ConditionContext context = new ConditionContext();
 *
 * // First condition computes and caches
 * Condition computeMetrics = agent -> {
 *     if (!context.has("cached.metrics")) {
 *         ExpensiveMetrics metrics = computeExpensiveMetrics();
 *         context.set("cached.metrics", metrics);
 *     }
 *     return true;
 * };
 *
 * // Subsequent conditions reuse cached value
 * Condition useMetrics = agent -> {
 *     ExpensiveMetrics metrics = context.get("cached.metrics", ExpensiveMetrics.class);
 *     return metrics != null && metrics.isHealthy();
 * };
 * }</pre>
 *
 * <p><strong>Example - Request Context:</strong>
 * <pre>{@code
 * // Store request-specific data
 * ConditionContext context = new ConditionContext();
 * context.set("request.id", UUID.randomUUID().toString());
 * context.set("request.user", currentUser);
 * context.set("request.timestamp", Instant.now());
 *
 * // Conditions can access request data
 * Condition userAuthorized = agent -> {
 *     User user = context.get("request.user", User.class);
 *     return user != null && user.hasPermission("process-orders");
 * };
 *
 * Condition withinTimeWindow = agent -> {
 *     Instant timestamp = context.get("request.timestamp", Instant.class);
 *     return timestamp != null &&
 *            Duration.between(timestamp, Instant.now()).toMinutes() < 5;
 * };
 * }</pre>
 *
 * <p><strong>Lifecycle Management:</strong>
 * The context can be cleared after use to free memory:
 * <pre>{@code
 * try {
 *     // Use context for batch of conditions
 *     context.set("batch.id", batchId);
 *     evaluateConditions(context);
 * } finally {
 *     // Clean up
 *     context.clear();
 * }
 * }</pre>
 *
 * <p><strong>Integration Patterns:</strong>
 * <ul>
 *   <li><strong>Thread-Local Context</strong> - Store per-thread context</li>
 *   <li><strong>Request-Scoped Context</strong> - One context per request</li>
 *   <li><strong>Global Shared Context</strong> - Shared across all conditions</li>
 *   <li><strong>Hierarchical Context</strong> - Chain contexts for inheritance</li>
 * </ul>
 *
 * <p><strong>Best Practices:</strong>
 * <ul>
 *   <li>Use consistent naming conventions (e.g., "category.name")</li>
 *   <li>Document expected keys and their types</li>
 *   <li>Provide default values for optional keys</li>
 *   <li>Clear context when no longer needed to prevent memory leaks</li>
 *   <li>Don't store large objects; use references instead</li>
 * </ul>
 *
 * @since 0.2.0
 * @see Condition
 */
public class ConditionContext {

    private final Map<String, Object> properties = new ConcurrentHashMap<>();

    /**
     * Sets a property value in the context.
     *
     * <p>Stores the given value under the specified key. If a value already
     * exists for this key, it is replaced.
     *
     * <p><strong>Thread Safety:</strong>
     * This method is thread-safe and can be called concurrently from multiple
     * threads.
     *
     * <p><strong>Null Values:</strong>
     * Both {@code null} keys and {@code null} values are allowed, though
     * {@code null} keys should be avoided as they may cause issues with
     * {@link #has(String)}.
     *
     * <p>Example:
     * <pre>{@code
     * context.set("cpu.usage", 75.5);
     * context.set("feature.enabled", true);
     * context.set("user.name", "admin");
     * context.set("metrics", SystemMetrics.current());
     * }</pre>
     *
     * @param key the key under which to store the value
     * @param value the value to store
     */
    public void set(String key, Object value) {
        properties.put(key, value);
    }

    /**
     * Retrieves a typed property value from the context.
     *
     * <p>Attempts to retrieve the value associated with the given key and cast
     * it to the specified type. If the key doesn't exist, returns {@code null}.
     *
     * <p><strong>Type Safety:</strong>
     * This method performs an unchecked cast. Ensure the type parameter matches
     * the actual type of the stored value, or a {@code ClassCastException} will
     * be thrown when the returned value is used.
     *
     * <p><strong>Null Handling:</strong>
     * Returns {@code null} if:
     * <ul>
     *   <li>The key doesn't exist in the context</li>
     *   <li>The value associated with the key is {@code null}</li>
     * </ul>
     *
     * Use {@link #has(String)} to distinguish between missing keys and {@code null} values.
     *
     * <p>Example:
     * <pre>{@code
     * Double cpu = context.get("cpu.usage", Double.class);
     * if (cpu != null && cpu > 80.0) {
     *     // Handle high CPU
     * }
     *
     * SystemMetrics metrics = context.get("metrics", SystemMetrics.class);
     * User user = context.get("request.user", User.class);
     * }</pre>
     *
     * @param <T> the expected type of the value
     * @param key the key whose associated value is to be returned
     * @param type the class of the expected type (for clarity, not actual type checking)
     * @return the value cast to type T, or {@code null} if the key doesn't exist
     *         or the value is {@code null}
     * @throws ClassCastException if the value cannot be cast to type T
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Object value = properties.get(key);
        if (value == null) {
            return null;
        }
        return (T) value;
    }

    /**
     * Retrieves a property value or returns a default if not present.
     *
     * <p>This is a convenience method that retrieves the value for the given key,
     * or returns the provided default value if the key doesn't exist or the value
     * is {@code null}.
     *
     * <p><strong>Type Inference:</strong>
     * The return type is inferred from the default value parameter, so no explicit
     * type parameter is needed:
     * <pre>{@code
     * // Type is inferred as Integer
     * int maxSize = context.getOrDefault("config.maxSize", 100);
     *
     * // Type is inferred as Boolean
     * boolean enabled = context.getOrDefault("feature.enabled", false);
     *
     * // Type is inferred as String
     * String name = context.getOrDefault("user.name", "anonymous");
     * }</pre>
     *
     * <p><strong>Null Safety:</strong>
     * If the default value is {@code null}, this method may still return {@code null}.
     * Prefer non-null defaults when possible.
     *
     * <p>Example:
     * <pre>{@code
     * // Configuration with defaults
     * int maxRetries = context.getOrDefault("config.maxRetries", 3);
     * double threshold = context.getOrDefault("config.threshold", 80.0);
     * boolean debugMode = context.getOrDefault("config.debug", false);
     *
     * // Use in conditions
     * Condition checkThreshold = agent -> {
     *     double threshold = context.getOrDefault("cpu.threshold", 80.0);
     *     return getCurrentCpu() < threshold;
     * };
     * }</pre>
     *
     * @param <T> the type of the value
     * @param key the key whose associated value is to be returned
     * @param defaultValue the value to return if the key doesn't exist or value is null
     * @return the value associated with the key, or defaultValue if not present
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrDefault(String key, T defaultValue) {
        T value = (T) properties.get(key);
        return value != null ? value : defaultValue;
    }

    /**
     * Checks if a property exists in the context.
     *
     * <p>Returns {@code true} if the context contains a mapping for the specified
     * key, even if the value is {@code null}.
     *
     * <p><strong>Use Case:</strong>
     * Distinguish between "key not set" and "key set to null":
     * <pre>{@code
     * if (context.has("override.value")) {
     *     // Key exists, use it (even if null)
     *     Object value = context.get("override.value", Object.class);
     * } else {
     *     // Key doesn't exist, use default
     *     Object value = getDefaultValue();
     * }
     * }</pre>
     *
     * <p>Example:
     * <pre>{@code
     * // Check before expensive computation
     * if (!context.has("cached.result")) {
     *     Result result = expensiveComputation();
     *     context.set("cached.result", result);
     * }
     *
     * // Conditional behavior based on key presence
     * if (context.has("debug.mode")) {
     *     log.debug("Processing in debug mode");
     * }
     * }</pre>
     *
     * @param key the key whose presence is to be tested
     * @return {@code true} if this context contains a mapping for the key,
     *         {@code false} otherwise
     */
    public boolean has(String key) {
        return properties.containsKey(key);
    }

    /**
     * Removes all properties from the context.
     *
     * <p>This method clears all stored key-value pairs, effectively resetting
     * the context to an empty state. Use this to:
     * <ul>
     *   <li>Free memory after processing a batch of conditions</li>
     *   <li>Reset context between requests or operations</li>
     *   <li>Prevent stale data from persisting</li>
     * </ul>
     *
     * <p><strong>Thread Safety:</strong>
     * This method is thread-safe but may race with concurrent {@link #set(String, Object)}
     * operations. For predictable behavior, ensure no concurrent modifications
     * occur during clearing.
     *
     * <p>Example:
     * <pre>{@code
     * // Process batch with context
     * ConditionContext context = new ConditionContext();
     * try {
     *     context.set("batch.id", batchId);
     *     context.set("batch.size", items.size());
     *     processItems(items, context);
     * } finally {
     *     // Clean up to free memory
     *     context.clear();
     * }
     * }</pre>
     *
     * <p>Example - Request scope:
     * <pre>{@code
     * // Request-scoped context
     * public void handleRequest(Request request) {
     *     ConditionContext context = getThreadLocalContext();
     *     try {
     *         context.set("request", request);
     *         processRequest(context);
     *     } finally {
     *         context.clear();  // Clean up after request
     *     }
     * }
     * }</pre>
     */
    public void clear() {
        properties.clear();
    }

    /**
     * Returns an immutable snapshot of all properties in the context.
     *
     * <p>Creates and returns an unmodifiable copy of the current property map.
     * Changes to the context after this call do not affect the returned map.
     *
     * <p><strong>Immutability:</strong>
     * The returned map is immutable. Attempts to modify it will throw
     * {@code UnsupportedOperationException}.
     *
     * <p><strong>Use Cases:</strong>
     * <ul>
     *   <li>Debugging: Inspect all context properties</li>
     *   <li>Logging: Log entire context state</li>
     *   <li>Serialization: Convert context to JSON/XML</li>
     *   <li>Testing: Verify expected context state</li>
     * </ul>
     *
     * <p><strong>Performance:</strong>
     * This method creates a copy of the underlying map. For large contexts,
     * this may be expensive. Use judiciously in performance-critical code.
     *
     * <p>Example - Debugging:
     * <pre>{@code
     * // Log all context properties
     * Map<String, Object> snapshot = context.asMap();
     * log.debug("Context state: {}", snapshot);
     *
     * // Inspect specific properties
     * for (Map.Entry<String, Object> entry : snapshot.entrySet()) {
     *     log.debug("{} = {}", entry.getKey(), entry.getValue());
     * }
     * }</pre>
     *
     * <p>Example - Testing:
     * <pre>{@code
     * // Verify context state in tests
     * @Test
     * void shouldPopulateContext() {
     *     ConditionContext context = new ConditionContext();
     *     populateContext(context);
     *
     *     Map<String, Object> properties = context.asMap();
     *     assertThat(properties).containsKey("cpu.usage");
     *     assertThat(properties).containsEntry("feature.enabled", true);
     * }
     * }</pre>
     *
     * @return an immutable map containing a snapshot of all current properties
     */
    public Map<String, Object> asMap() {
        return Map.copyOf(properties);
    }
}
