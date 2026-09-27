package dev.agenor.core.memory.llm;

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
 * <p>An unregistered model does not fail {@link #getLimit(String)} — it falls back to
 * {@link #DEFAULT_LIMIT}, silently, which is 4,096 tokens on a model whose real window may be
 * two orders of magnitude larger. {@link #isKnown(String)} lets a caller tell that fallback
 * apart from a real answer, which is what {@code SimpleTokenEstimator} uses to warn once per
 * model instead of trimming a conversation short with no explanation.
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
     *       {@code "gpt-4-0613"} and vice-versa. When more than one registered key matches,
     *       the longer (more specific) one wins, deterministically — not whichever the
     *       registry's internal map happens to visit first</li>
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
        Integer resolved = resolve(normalize(model));
        return resolved != null ? resolved : DEFAULT_LIMIT;
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
     * Return true if {@link #getLimit(String)} would resolve a real registered limit for
     * this model, by exact or prefix match — as opposed to falling back to
     * {@link #DEFAULT_LIMIT}.
     *
     * <p>Unlike {@link #hasModel(String)}, this also accounts for the prefix match
     * {@link #getLimit(String)} performs, so a caller can tell "this is a real answer"
     * from "this is the registry shrugging" before deciding whether a fallback is worth a
     * warning.
     *
     * @param model model identifier (may be null)
     * @return true if a registered limit would be used instead of the default
     * @since 0.36.0
     */
    public static boolean isKnown(String model) {
        return model != null && resolve(normalize(model)) != null;
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
        Integer resolved = resolve(normalize(model));
        return resolved != null ? resolved : defaultValue;
    }

    // -------------------------------------------------------------------------

    private static String normalize(String model) {
        return model.toLowerCase().trim();
    }

    /**
     * Exact-or-prefix lookup shared by every public resolution method.
     *
     * <p>When several registered keys are in a prefix relationship with {@code key} — in
     * either direction, per {@link #getLimit(String)}'s documented resolution order — the
     * longest registered key wins. That is the one property a {@code ConcurrentHashMap}'s
     * iteration order cannot be relied on to give: for {@code "gpt-4-turbo-2024-04-09"},
     * both {@code "gpt-4"} and {@code "gpt-4-turbo"} are valid prefixes, and returning
     * whichever the map visited first made the answer depend on hash-bucket layout instead
     * of on which registration is more specific.
     *
     * @param key already-normalized model identifier
     * @return the resolved limit, or {@code null} if nothing registered matches
     */
    private static Integer resolve(String key) {
        Integer exact = LIMITS.get(key);
        if (exact != null) {
            return exact;
        }

        String bestKey = null;
        Integer bestValue = null;
        for (Map.Entry<String, Integer> entry : LIMITS.entrySet()) {
            String candidate = entry.getKey();
            boolean matches = key.startsWith(candidate) || candidate.startsWith(key);
            if (matches && (bestKey == null || candidate.length() > bestKey.length())) {
                bestKey = candidate;
                bestValue = entry.getValue();
            }
        }
        return bestValue;
    }
}
