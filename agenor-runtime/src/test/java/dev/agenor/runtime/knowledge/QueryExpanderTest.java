package dev.agenor.runtime.knowledge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class QueryExpanderTest {

    private QueryExpander expander;

    @BeforeEach
    void setUp() {
        expander = new QueryExpander();
        expander.addSynonyms("refund", "return", "money back");
        expander.addSynonyms("cancel", "cancellation");
    }

    // -------------------------------------------------------------------------
    // Edge cases — null / blank
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("expand returns query unchanged for null or blank input")
    void expandReturnsSameForBlankInput(String query) {
        assertThat(expander.expand(query)).isEqualTo(query);
    }

    // -------------------------------------------------------------------------
    // No synonyms registered for token
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("expand returns original tokens when no synonyms are registered")
    void expandNoSynonyms() {
        QueryExpander empty = new QueryExpander();
        assertThat(empty.expand("hello world")).isEqualTo("hello world");
    }

    // -------------------------------------------------------------------------
    // Synonym expansion
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("expand adds synonyms for known token")
    void expandAddsSynonyms() {
        String result = expander.expand("refund");
        assertThat(result).contains("refund", "return", "money back");
    }

    @Test
    @DisplayName("expand preserves unknown tokens unchanged")
    void expandPreservesUnknownTokens() {
        String result = expander.expand("where is my order");
        assertThat(result).contains("where", "is", "my", "order");
    }

    @Test
    @DisplayName("expand handles mixed known and unknown tokens")
    void expandMixedTokens() {
        String result = expander.expand("I want a refund please");
        assertThat(result)
            .contains("i", "want", "a", "please")
            .contains("refund", "return", "money back");
    }

    // -------------------------------------------------------------------------
    // Case insensitivity
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("expand is case-insensitive")
    void expandCaseInsensitive() {
        String lower = expander.expand("refund");
        String upper = expander.expand("REFUND");
        assertThat(lower).isEqualTo(upper);
    }

    // -------------------------------------------------------------------------
    // Deduplication
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("expand deduplicates repeated tokens")
    void expandDeduplicates() {
        String result = expander.expand("refund refund");
        long count = java.util.Arrays.stream(result.split(" "))
            .filter("refund"::equals).count();
        assertThat(count).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Multiple synonym groups
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("expand handles multiple synonym groups in the same query")
    void expandMultipleGroups() {
        String result = expander.expand("refund cancel");
        assertThat(result)
            .contains("refund", "return", "money back")
            .contains("cancel", "cancellation");
    }

    // -------------------------------------------------------------------------
    // addSynonyms merging
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("addSynonyms merges overlapping groups")
    void addSynonymsMergesOverlappingGroups() {
        QueryExpander e = new QueryExpander();
        e.addSynonyms("a", "b");
        e.addSynonyms("a", "c"); // extends the "a" group with "c"
        String result = e.expand("a");
        assertThat(result).contains("a", "b", "c");
    }
}
