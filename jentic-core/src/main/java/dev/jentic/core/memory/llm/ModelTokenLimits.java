package dev.jentic.core.memory.llm;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of LLM model context window sizes (in tokens).
 *
 * <p>This class is a pure registry: it stores token limits but does NOT
 * pre-register any vendor-specific models. Each adapter (OpenAI, Anthropic,
 * Ollama, ...) is responsible for registering its own models in a static
 * initializer. This keeps runtime free of provider-specific knowledge and
 * avoids obsolescence caused by centralized vendor model lists.
 *
 * <p>Custom or external models can be registered at any time:
 * <pre>{@code
 * ModelTokenLimits.register("my-custom-model", 32_768);
 * }</pre>
 *
 * <p><b>Thread Safety:</b> This class uses a ConcurrentHashMap and is thread-safe.
 *
 * @since 0.15.0
 */
public final class ModelTokenLimits {

    /** Fallback limit returned for any model not present in the registry. */
    public static final int DEFAULT_LIMIT = 4_096;

    private static final Map<String, Integer> LIMITS = new ConcurrentHashMap<>();

    private ModelTokenLimits() {
        throw new AssertionError("Cannot instantiate ModelTokenLimits");
    }

    /**
     * Return the context window size for the given model.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Exact match (case-insensitive)</li>
     *   <li>Prefix match — allows versioned aliases, e.g. {@code "gpt-4"} matches
     *       {@code "gpt-4-0613"} and vice-versa</li>
     *   <li>{@link #DEFAULT_LIMIT} for unknown models</li>
     * </ol>
     *
     * @param model model identifier (non-null)
     * @return context window size in tokens
     * @throws IllegalArgumentException if model is null
     */
    public static int getLimit(String model) {
        if (model == null) {
            throw new IllegalArgumentException("Model cannot be null");
        }
        String key = normalize(model);

        Integer exact = LIMITS.get(key);
        if (exact != null) {
            return exact;
        }

        for (Map.Entry<String, Integer> entry : LIMITS.entrySet()) {
            if (key.startsWith(entry.getKey()) || entry.getKey().startsWith(key)) {
                return entry.getValue();
            }
        }
        return DEFAULT_LIMIT;
    }

    /**
     * Register a model and its context window size.
     *
     * <p>Can be used to add new models or override existing limits.
     * Typically called from a provider adapter's static initializer.
     *
     * @param model model identifier (non-null, non-blank)
     * @param limit context window size in tokens (must be &gt; 0)
     * @throws IllegalArgumentException if model is null/blank or limit ≤ 0
     */
    public static void register(String model, int limit) {
        if (model == null || model.trim().isEmpty()) {
            throw new IllegalArgumentException("Model cannot be null or empty");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("Limit must be positive");
        }
        LIMITS.put(normalize(model), limit);
    }

    /**
     * Return true if a model has been registered (exact match, case-insensitive).
     *
     * @param model model identifier
     * @return true if the model is known
     */
    public static boolean hasModel(String model) {
        return model != null && LIMITS.containsKey(normalize(model));
    }

    /**
     * Return an unmodifiable view of all registered model identifiers.
     *
     * @return set of registered model names
     */
    public static Set<String> getAllModels() {
        return Collections.unmodifiableSet(LIMITS.keySet());
    }

    /**
     * Remove a model from the registry.
     *
     * <p>No-op if the model was not registered. Primarily useful in tests.
     *
     * @param model model identifier (non-null)
     */
    public static void unregister(String model) {
        if (model != null) {
            LIMITS.remove(normalize(model));
        }
    }

    /**
     * Return the context window size for the given model, or a caller-supplied
     * default when the model is not registered.
     *
     * <p>Unlike {@link #getLimit(String)}, this variant lets the caller specify
     * a meaningful fallback instead of the global {@link #DEFAULT_LIMIT}.
     *
     * @param model        model identifier (may be null)
     * @param defaultValue value to return when the model is unknown
     * @return registered limit, or {@code defaultValue} if not found
     */
    public static int getLimitOrDefault(String model, int defaultValue) {
        if (model == null) {
            return defaultValue;
        }
        String key = normalize(model);

        Integer exact = LIMITS.get(key);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, Integer> entry : LIMITS.entrySet()) {
            if (key.startsWith(entry.getKey()) || entry.getKey().startsWith(key)) {
                return entry.getValue();
            }
        }
        return defaultValue;
    }

    // -------------------------------------------------------------------------

    private static String normalize(String model) {
        return model.toLowerCase().trim();
    }
}
