package dev.jentic.runtime.guardrail;

import dev.jentic.core.guardrail.GuardrailContext;
import dev.jentic.core.guardrail.GuardrailResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ContentPolicyGuardrail")
class ContentPolicyGuardrailTest {

    private static final GuardrailContext CTX = GuardrailContext.of("test-agent");

    // -------------------------------------------------------------------------
    // Helper: build a guardrail from inline YAML string
    // -------------------------------------------------------------------------

    /** Builds a guardrail by writing the YAML to a temp file and loading it. */
    private static ContentPolicyGuardrail fromYaml(@TempDir Path dir, String yaml) throws Exception {
        Path file = dir.resolve("policy.yaml");
        Files.writeString(file, yaml);
        return new ContentPolicyGuardrail(file.toString());
    }

    /** Builds a guardrail directly from a PolicyFile record (no I/O). */
    private static ContentPolicyGuardrail fromPolicy(
            List<ContentPolicyGuardrail.BlockedPattern> patterns,
            List<String> topics) {
        return new ContentPolicyGuardrail(
                new ContentPolicyGuardrail.PolicyFile(patterns, topics));
    }

    private static ContentPolicyGuardrail.BlockedPattern bp(String pattern, String reason) {
        return new ContentPolicyGuardrail.BlockedPattern(pattern, reason);
    }

    // -------------------------------------------------------------------------
    // YAML loading
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("YAML loading")
    class YamlLoadingTests {

        @Test
        @DisplayName("loads valid YAML — blocked-patterns and blocked-topics parsed correctly")
        void loadsValidYaml(@TempDir Path dir) throws Exception {
            String yaml = """
                    content-policy:
                      blocked-patterns:
                        - pattern: "(?i)badword"
                          reason: "Company policy"
                      blocked-topics:
                        - "sensitive_topic"
                    """;
            ContentPolicyGuardrail g = fromYaml(dir, yaml);
            assertThat(g.ruleCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("YAML with only blocked-patterns loads without error")
        void loadsOnlyBlockedPatterns(@TempDir Path dir) throws Exception {
            String yaml = """
                    content-policy:
                      blocked-patterns:
                        - pattern: "(?i)forbidden"
                          reason: "Forbidden"
                    """;
            ContentPolicyGuardrail g = fromYaml(dir, yaml);
            assertThat(g.ruleCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("YAML with empty sections loads without error — ruleCount = 0")
        void loadsEmptyPolicy(@TempDir Path dir) throws Exception {
            String yaml = """
                    content-policy:
                      blocked-patterns: []
                      blocked-topics: []
                    """;
            ContentPolicyGuardrail g = fromYaml(dir, yaml);
            assertThat(g.ruleCount()).isZero();
        }

        @Test
        @DisplayName("non-existent file throws ContentPolicyLoadException at construction")
        void nonExistentFile_throwsAtConstruction() {
            assertThatThrownBy(() -> new ContentPolicyGuardrail("/does/not/exist/policy.yaml"))
                    .isInstanceOf(ContentPolicyGuardrail.ContentPolicyLoadException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        @DisplayName("missing classpath resource throws ContentPolicyLoadException")
        void missingClasspathResource_throws() {
            assertThatThrownBy(() -> new ContentPolicyGuardrail("classpath:no-such-policy.yaml"))
                    .isInstanceOf(ContentPolicyGuardrail.ContentPolicyLoadException.class)
                    .hasMessageContaining("not found");
        }
    }

    // -------------------------------------------------------------------------
    // blocked-patterns matching
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("blocked-patterns")
    class BlockedPatternTests {

        @Test
        @DisplayName("exact match on blocked pattern → Blocked with correct reason")
        void exactMatch_blocked() {
            var g = fromPolicy(List.of(bp("(?i)badword", "policy violation")), null);
            var result = g.evaluate("This contains badword in the text.");
            assertThat(result).isInstanceOf(GuardrailResult.Blocked.class);
            assertThat(((GuardrailResult.Blocked) result).reason()).isEqualTo("policy violation");
        }

        @Test
        @DisplayName("case-insensitive match via (?i) flag in pattern")
        void caseInsensitive_match() {
            var g = fromPolicy(List.of(bp("(?i)forbidden", "forbidden")), null);
            assertThat(g.evaluate("FORBIDDEN content")).isInstanceOf(GuardrailResult.Blocked.class);
            assertThat(g.evaluate("Forbidden word")).isInstanceOf(GuardrailResult.Blocked.class);
            assertThat(g.evaluate("forbidden text")).isInstanceOf(GuardrailResult.Blocked.class);
        }

        @Test
        @DisplayName("safe text → Passed")
        void safeText_passed() {
            var g = fromPolicy(List.of(bp("(?i)badword", "policy")), null);
            assertThat(g.evaluate("Hello, this is a perfectly safe message."))
                    .isInstanceOf(GuardrailResult.Passed.class);
        }

        @Test
        @DisplayName("missing reason in YAML → defaults to 'Content policy violation'")
        void missingReason_defaultMessage() {
            var g = fromPolicy(List.of(bp("(?i)trigger", null)), null);
            var result = g.evaluate("this triggers the rule");
            assertThat(result).isInstanceOf(GuardrailResult.Blocked.class);
            assertThat(((GuardrailResult.Blocked) result).reason()).isEqualTo("Content policy violation");
        }

        @Test
        @DisplayName("first matching pattern short-circuits — only its reason is returned")
        void firstMatchShortCircuits() {
            var g = fromPolicy(List.of(
                    bp("(?i)alpha", "alpha policy"),
                    bp("(?i)beta",  "beta policy")), null);
            // "alpha beta" matches both; only first reason should appear
            var result = g.evaluate("alpha beta text");
            assertThat(((GuardrailResult.Blocked) result).reason()).isEqualTo("alpha policy");
        }
    }

    // -------------------------------------------------------------------------
    // blocked-topics matching
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("blocked-topics")
    class BlockedTopicTests {

        @Test
        @DisplayName("plain topic → case-insensitive substring match")
        void plainTopic_caseInsensitive() {
            var g = fromPolicy(null, List.of("violence"));
            assertThat(g.evaluate("This text promotes Violence in society"))
                    .isInstanceOf(GuardrailResult.Blocked.class);
            assertThat(g.evaluate("Safe content"))
                    .isInstanceOf(GuardrailResult.Passed.class);
        }

        @Test
        @DisplayName("wildcard * at start → matches suffix")
        void wildcardAtStart() {
            var g = fromPolicy(null, List.of("*word"));
            assertThat(g.evaluate("some badword here")).isInstanceOf(GuardrailResult.Blocked.class);
            assertThat(g.evaluate("some other thing")).isInstanceOf(GuardrailResult.Passed.class);
        }

        @Test
        @DisplayName("wildcard * at end → matches prefix")
        void wildcardAtEnd() {
            var g = fromPolicy(null, List.of("hate*"));
            assertThat(g.evaluate("hateful speech")).isInstanceOf(GuardrailResult.Blocked.class);
            assertThat(g.evaluate("I love everyone")).isInstanceOf(GuardrailResult.Passed.class);
        }

        @Test
        @DisplayName("wildcard * in middle → matches around it")
        void wildcardInMiddle() {
            var g = fromPolicy(null, List.of("buy*now"));
            assertThat(g.evaluate("click here to buy this now")).isInstanceOf(GuardrailResult.Blocked.class);
            assertThat(g.evaluate("buy later")).isInstanceOf(GuardrailResult.Passed.class);
        }

        @Test
        @DisplayName("multiple wildcards in one topic")
        void multipleWildcards() {
            var g = fromPolicy(null, List.of("*click*here*"));
            assertThat(g.evaluate("please click right here for free stuff"))
                    .isInstanceOf(GuardrailResult.Blocked.class);
        }

        @Test
        @DisplayName("blocked-topics reason contains original topic name")
        void topicReason_containsTopicName() {
            var g = fromPolicy(null, List.of("violence"));
            var result = g.evaluate("violence in content");
            assertThat(((GuardrailResult.Blocked) result).reason()).contains("violence");
        }
    }

    // -------------------------------------------------------------------------
    // wildcardToRegex
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("wildcardToRegex")
    class WildcardToRegexTests {

        @Test
        @DisplayName("no wildcard → fully quoted string")
        void noWildcard() {
            String regex = ContentPolicyGuardrail.wildcardToRegex("hello.world");
            assertThat(regex).isEqualTo("\\Qhello.world\\E");
        }

        @Test
        @DisplayName("single * → .*")
        void singleWildcard() {
            String regex = ContentPolicyGuardrail.wildcardToRegex("*");
            assertThat(regex).isEqualTo(".*");
        }

        @Test
        @DisplayName("word*word → quoted segments joined by .*")
        void middleWildcard() {
            String regex = ContentPolicyGuardrail.wildcardToRegex("buy*now");
            assertThat(regex).isEqualTo("\\Qbuy\\E.*\\Qnow\\E");
        }
    }

    // -------------------------------------------------------------------------
    // async contract
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Async contract (CompletableFuture)")
    class AsyncTests {

        @Test
        @DisplayName("apply() as InputGuardrail returns Blocked future for matching content")
        void inputGuardrail_blockedFuture() throws Exception {
            var g = fromPolicy(List.of(bp("(?i)trigger", "blocked")), null);
            var result = g.apply("this triggers the policy", CTX).get();
            assertThat(result).isInstanceOf(GuardrailResult.Blocked.class);
        }

        @Test
        @DisplayName("apply() as OutputGuardrail returns Passed future for safe content")
        void outputGuardrail_passedFuture() throws Exception {
            var g = fromPolicy(List.of(bp("(?i)trigger", "blocked")), null);
            var result = g.apply("completely safe output", CTX).get();
            assertThat(result).isInstanceOf(GuardrailResult.Passed.class);
        }
    }
}