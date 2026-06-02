package dev.agenor.runtime.guardrail;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.agenor.core.exceptions.JenticException;
import dev.agenor.core.guardrail.GuardrailContext;
import dev.agenor.core.guardrail.GuardrailResult;
import dev.agenor.core.guardrail.InputGuardrail;
import dev.agenor.core.guardrail.OutputGuardrail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Blocks content that matches a configurable blocklist loaded from a YAML file.
 *
 * <p>Implements both {@link InputGuardrail} and {@link OutputGuardrail}.
 * Returns {@link GuardrailResult.Blocked} on the first match; {@link GuardrailResult.Passed}
 * when no rule matches.
 *
 * <p><b>YAML format</b></p>
 * <pre>{@code
 * content-policy:
 *   blocked-patterns:
 *     - pattern: "(?i)forbidden_word"
 *       reason:  "Company policy violation"
 *   blocked-topics:
 *     - "sensitive_topic"
 * }</pre>
 *
 * <ul>
 *   <li>{@code blocked-patterns} — full Java regex; matched case-insensitively by default
 *       unless the pattern already includes inline flags.</li>
 *   <li>{@code blocked-topics} — plain strings; matched as substrings, case-insensitive;
 *       support {@code *} as a wildcard (converted to {@code .*} regex).</li>
 * </ul>
 *
 * <p>Example:
 * <pre>{@code
 * // From filesystem path
 * new ContentPolicyGuardrail("/etc/agenor/policy.yaml")
 *
 * // From classpath resource
 * new ContentPolicyGuardrail("classpath:guardrails/policy.yaml")
 * }</pre>
 *
 * @since 0.13.0
 */
public class ContentPolicyGuardrail implements InputGuardrail, OutputGuardrail {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    /** Compiled rules ready for matching; built once at construction time. */
    private final List<CompiledRule> rules;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Loads the content policy from the given path.
     *
     * <p>Prefix the path with {@code classpath:} to load from the classpath,
     * otherwise it is treated as a filesystem path.
     *
     * @param policyPath path to the YAML policy file; never {@code null}
     * @throws ContentPolicyLoadException if the file is not found or cannot be parsed
     */
    public ContentPolicyGuardrail(String policyPath) {
        Objects.requireNonNull(policyPath, "policyPath must not be null");
        PolicyFile policy = load(policyPath);
        this.rules = compile(policy);
    }

    /** Package-private constructor for testing with an already-parsed policy. */
    ContentPolicyGuardrail(PolicyFile policy) {
        Objects.requireNonNull(policy, "policy must not be null");
        this.rules = compile(policy);
    }

    // -------------------------------------------------------------------------
    // InputGuardrail / OutputGuardrail
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<GuardrailResult> apply(String content, GuardrailContext ctx) {
        return CompletableFuture.completedFuture(evaluate(content));
    }

    // -------------------------------------------------------------------------
    // Core evaluation
    // -------------------------------------------------------------------------

    GuardrailResult evaluate(String content) {
        if (content == null || content.isEmpty()) {
            return new GuardrailResult.Passed();
        }

        for (CompiledRule rule : rules) {
            if (rule.pattern().matcher(content).find()) {
                return new GuardrailResult.Blocked(rule.reason());
            }
        }

        return new GuardrailResult.Passed();
    }

    int ruleCount() {
        return rules.size();
    }

    // -------------------------------------------------------------------------
    // YAML loading
    // -------------------------------------------------------------------------

    private static PolicyFile load(String policyPath) {
        try {
            if (policyPath.startsWith("classpath:")) {
                return loadFromClasspath(policyPath.substring("classpath:".length()));
            }
            return loadFromFilesystem(policyPath);
        } catch (IOException e) {
            throw new ContentPolicyLoadException(
                    "Failed to load content policy from: " + policyPath, e);
        }
    }

    private static PolicyFile loadFromFilesystem(String path) throws IOException {
        Path p = Paths.get(path);
        if (!Files.exists(p)) {
            throw new ContentPolicyLoadException("Content policy file not found: " + path);
        }
        try (InputStream is = Files.newInputStream(p)) {
            return YAML_MAPPER.readValue(is, PolicyFileWrapper.class).contentPolicy();
        }
    }

    private static PolicyFile loadFromClasspath(String resource) throws IOException {
        String normalized = resource.startsWith("/") ? resource.substring(1) : resource;
        InputStream is = ContentPolicyGuardrail.class.getClassLoader()
                .getResourceAsStream(normalized);
        if (is == null) {
            throw new ContentPolicyLoadException(
                    "Content policy classpath resource not found: " + normalized);
        }
        try (is) {
            return YAML_MAPPER.readValue(is, PolicyFileWrapper.class).contentPolicy();
        }
    }

    // -------------------------------------------------------------------------
    // Pattern compilation
    // -------------------------------------------------------------------------

    private static List<CompiledRule> compile(PolicyFile policy) {
        List<CompiledRule> compiled = new ArrayList<>();

        if (policy.blockedPatterns() != null) {
            for (BlockedPattern bp : policy.blockedPatterns()) {
                // Patterns in the YAML may already include inline flags like (?i).
                // We compile as-is; case-insensitivity is the author's responsibility.
                compiled.add(new CompiledRule(
                        Pattern.compile(bp.pattern()),
                        bp.reason() != null ? bp.reason() : "Content policy violation"));
            }
        }

        if (policy.blockedTopics() != null) {
            for (String topic : policy.blockedTopics()) {
                // Convert wildcard '*' to regex '.*', then wrap for case-insensitive substring match
                String regex = "(?i)" + wildcardToRegex(topic);
                compiled.add(new CompiledRule(
                        Pattern.compile(regex),
                        "Blocked topic: " + topic));
            }
        }

        return List.copyOf(compiled);
    }

    /**
     * Converts a wildcard pattern (only {@code *} supported) to a regex fragment.
     * All regex metacharacters in the original string are quoted first.
     */
    static String wildcardToRegex(String wildcard) {
        // Split on '*', quote each non-empty segment, join with '.*'
        String[] parts = wildcard.split("\\*", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(".*");
            if (!parts[i].isEmpty()) sb.append(Pattern.quote(parts[i]));
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Internal record types
    // -------------------------------------------------------------------------

    private record CompiledRule(Pattern pattern, String reason) {}

    // -------------------------------------------------------------------------
    // YAML binding types
    // -------------------------------------------------------------------------

    /** Top-level wrapper matching {@code content-policy:} root key. */
    record PolicyFileWrapper(
            @JsonProperty("content-policy") PolicyFile contentPolicy) {}

    /** The {@code content-policy} object. */
    record PolicyFile(
            @JsonProperty("blocked-patterns") List<BlockedPattern> blockedPatterns,
            @JsonProperty("blocked-topics")   List<String>         blockedTopics) {}

    /** One entry under {@code blocked-patterns}. */
    record BlockedPattern(
            @JsonProperty("pattern") String pattern,
            @JsonProperty("reason")  String reason) {}

    // -------------------------------------------------------------------------
    // Exception
    // -------------------------------------------------------------------------

    /** Thrown when the YAML policy file cannot be found or parsed. */
    public static class ContentPolicyLoadException extends JenticException {
		private static final long serialVersionUID = -3037695906265543020L;
		public ContentPolicyLoadException(String message) {
            super(message);
        }
        public ContentPolicyLoadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
