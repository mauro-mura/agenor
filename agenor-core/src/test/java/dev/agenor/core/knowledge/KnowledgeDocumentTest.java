package dev.agenor.core.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class KnowledgeDocumentTest {

    private static final String ID = "doc-001";
    private static final String TITLE = "Return Policy";
    private static final String CONTENT = "Items can be returned within 30 days of purchase.";
    private static final Set<String> KEYWORDS = Set.of("return", "refund", "policy");

    private KnowledgeDocument<String> doc(String title, String content, Set<String> keywords) {
        return new KnowledgeDocument<>(ID, title, content, "RETURNS", keywords);
    }

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("compact constructor sets relatedIds to empty list")
    void compactConstructorHasEmptyRelatedIds() {
        var d = new KnowledgeDocument<>(ID, TITLE, CONTENT, "CAT", KEYWORDS);
        assertThat(d.relatedIds()).isEmpty();
    }

    @Test
    @DisplayName("canonical constructor preserves relatedIds")
    void canonicalConstructorPreservesRelatedIds() {
        var related = List.of("doc-002", "doc-003");
        var d = new KnowledgeDocument<>(ID, TITLE, CONTENT, "CAT", KEYWORDS, related);
        assertThat(d.relatedIds()).containsExactlyElementsOf(related);
    }

    // -------------------------------------------------------------------------
    // relevanceScore — null / blank query
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    @DisplayName("relevanceScore returns 0.0 for null or blank query")
    void relevanceScoreZeroForBlankQuery(String query) {
        var d = doc(TITLE, CONTENT, KEYWORDS);
        assertThat(d.relevanceScore(query)).isEqualTo(0.0);
    }

    // -------------------------------------------------------------------------
    // relevanceScore — title match (weight 2)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("title match scores higher than content-only match")
    void titleMatchScoresHigherThanContentMatch() {
        var withTitleMatch    = doc("Return Policy", "Unrelated text.", Set.of());
        var withContentMatch  = doc("Shipping Info",  "Return items within 30 days.", Set.of());

        double titleScore   = withTitleMatch.relevanceScore("return");
        double contentScore = withContentMatch.relevanceScore("return");

        assertThat(titleScore).isGreaterThan(contentScore);
    }

    // -------------------------------------------------------------------------
    // relevanceScore — keyword match
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("keyword match increases score")
    void keywordMatchIncreasesScore() {
        var withKeyword    = doc("Info", "No match here.", Set.of("refund"));
        var withoutKeyword = doc("Info", "No match here.", Set.of());

        assertThat(withKeyword.relevanceScore("refund"))
            .isGreaterThan(withoutKeyword.relevanceScore("refund"));
    }

    @Test
    @DisplayName("partial keyword match (term contains keyword) scores positively")
    void partialKeywordMatch() {
        var d = doc("Info", "Nothing.", Set.of("refund"));
        // "refunds" contains "refund"
        assertThat(d.relevanceScore("refunds")).isGreaterThan(0.0);
    }

    // -------------------------------------------------------------------------
    // relevanceScore — no match
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("unrelated query scores 0.0")
    void unrelatedQueryScoresZero() {
        var d = doc(TITLE, CONTENT, KEYWORDS);
        assertThat(d.relevanceScore("completely unrelated xyz")).isEqualTo(0.0);
    }

    // -------------------------------------------------------------------------
    // relevanceScore — score in range
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("score is normalised within [0.0, 1.0]")
    void scoreIsNormalised() {
        var d = doc(TITLE, CONTENT, KEYWORDS);
        double score = d.relevanceScore("return refund policy");
        assertThat(score).isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("adding non-matching tokens to a query reduces the score")
    void nonMatchingTokensReduceScore() {
        // The formula normalises by terms.length * (2 + keywords + 1).
        // Adding tokens with no matches increases the denominator without
        // increasing the numerator, so the normalised score decreases.
        var d = doc(TITLE, CONTENT, KEYWORDS);
        double matchingOnly    = d.relevanceScore("return");
        double withNonMatching = d.relevanceScore("return zxqwerty");
        assertThat(withNonMatching).isLessThan(matchingOnly);
    }

    @Test
    @DisplayName("a token that hits title, keyword and content produces score 1.0")
    void perfectPerTermMatchScoresOne() {
        // title(+2) + keyword(+1) + content(+1) = 4 = maxPossible(1 * (2+1+1))
        var d = new KnowledgeDocument<>("id", "Return", "Return", "CAT", Set.of("return"));
        assertThat(d.relevanceScore("return")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("more keyword matches on the same token increase score")
    void moreKeywordMatchesIncreaseScore() {
        // same title/content, but different keyword sets -> more kw hits → higher score
        var fewer = doc("Return Policy", "Nothing.", Set.of());
        var more  = doc("Return Policy", "Nothing.", Set.of("return"));
        assertThat(more.relevanceScore("return"))
            .isGreaterThan(fewer.relevanceScore("return"));
    }

    // -------------------------------------------------------------------------
    // Case insensitivity
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("matching is case-insensitive")
    void matchingIsCaseInsensitive() {
        var d = doc("Return Policy", CONTENT, KEYWORDS);
        assertThat(d.relevanceScore("RETURN")).isEqualTo(d.relevanceScore("return"));
    }
}
