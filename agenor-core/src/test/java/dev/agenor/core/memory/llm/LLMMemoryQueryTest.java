package dev.agenor.core.memory.llm;

import dev.agenor.core.memory.MemoryScope;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for LLMMemoryQuery.
 */
class LLMMemoryQueryTest {

    @Test
    void shouldCreateSimpleQuery() {
        // When
        LLMMemoryQuery query = LLMMemoryQuery.simple("user preferences");

        // Then
        assertThat(query.query()).isEqualTo("user preferences");
        assertThat(query.maxTokens()).isEqualTo(LLMMemoryQuery.DEFAULT_MAX_TOKENS);
        assertThat(query.scope()).isEqualTo(MemoryScope.LONG_TERM);
        assertThat(query.maxResults()).isEqualTo(LLMMemoryQuery.DEFAULT_MAX_RESULTS);
        assertThat(query.metadataFilters()).isEmpty();
        assertThat(query.includeTimestamps()).isFalse();
        assertThat(query.includeMetadata()).isFalse();
        assertThat(query.formatAsMessages()).isFalse();
    }

    @Test
    void shouldCreateQueryWithTokens() {
        // When
        LLMMemoryQuery query = LLMMemoryQuery.withTokens("test query", 500);

        // Then
        assertThat(query.query()).isEqualTo("test query");
        assertThat(query.maxTokens()).isEqualTo(500);
    }

    @Test
    void shouldBuildQueryWithAllOptions() {
        // Given
        Map<String, Object> filters = Map.of(
            "category", "support",
            "priority", "high"
        );

        // When
        LLMMemoryQuery query = LLMMemoryQuery.builder("customer issue")
            .maxTokens(1500)
            .scope(MemoryScope.SHORT_TERM)
            .maxResults(15)
            .metadataFilters(filters)
            .includeTimestamps(true)
            .includeMetadata(true)
            .formatAsMessages(true)
            .build();

        // Then
        assertThat(query.query()).isEqualTo("customer issue");
        assertThat(query.maxTokens()).isEqualTo(1500);
        assertThat(query.scope()).isEqualTo(MemoryScope.SHORT_TERM);
        assertThat(query.maxResults()).isEqualTo(15);
        assertThat(query.metadataFilters()).isEqualTo(filters);
        assertThat(query.includeTimestamps()).isTrue();
        assertThat(query.includeMetadata()).isTrue();
        assertThat(query.formatAsMessages()).isTrue();
    }

    @Test
    void shouldBuildQueryWithFluentMetadata() {
        // When
        LLMMemoryQuery query = LLMMemoryQuery.builder("test")
            .metadata("key1", "value1")
            .metadata("key2", 42)
            .metadata("key3", true)
            .build();

        // Then
        assertThat(query.metadataFilters()).hasSize(3);
        assertThat(query.metadataFilters().get("key1")).isEqualTo("value1");
        assertThat(query.metadataFilters().get("key2")).isEqualTo(42);
        assertThat(query.metadataFilters().get("key3")).isEqualTo(true);
    }

    @Test
    void shouldValidateRequiredQuery() {
        // When/Then
        assertThatThrownBy(() -> LLMMemoryQuery.builder(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Query cannot be null");

        assertThatThrownBy(() -> LLMMemoryQuery.builder(""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Query cannot be null or empty");

        assertThatThrownBy(() -> LLMMemoryQuery.builder("  "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Query cannot be null or empty");
    }

    @Test
    void shouldValidateMaxTokens() {
        // When/Then
        assertThatThrownBy(() ->
            LLMMemoryQuery.builder("test").maxTokens(0).build()
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("maxTokens must be positive");

        assertThatThrownBy(() ->
            LLMMemoryQuery.builder("test").maxTokens(-1).build()
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("maxTokens must be positive");
    }

    @Test
    void shouldValidateMaxResults() {
        // When/Then
        assertThatThrownBy(() ->
            LLMMemoryQuery.builder("test").maxResults(0).build()
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("maxResults must be positive");

        assertThatThrownBy(() ->
            LLMMemoryQuery.builder("test").maxResults(-5).build()
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("maxResults must be positive");
    }

    @Test
    void shouldValidateScope() {
        // When/Then
        assertThatThrownBy(() ->
            LLMMemoryQuery.builder("test").scope(null).build()
        ).isInstanceOf(NullPointerException.class)
         .hasMessageContaining("Scope cannot be null");
    }

    @Test
    void shouldTrimQuery() {
        // When
        LLMMemoryQuery query = LLMMemoryQuery.builder("  test query  ").build();

        // Then
        assertThat(query.query()).isEqualTo("test query");
    }

    @Test
    void shouldDefensiveCopyMetadataFilters() {
        // Given
        var mutableMap = new java.util.HashMap<String, Object>();
        mutableMap.put("key", "value");

        // When
        LLMMemoryQuery query = LLMMemoryQuery.builder("test")
            .metadataFilters(mutableMap)
            .build();

        // Modify original map
        mutableMap.put("key", "modified");
        mutableMap.put("new", "value");

        // Then - query should be unaffected
        assertThat(query.metadataFilters()).hasSize(1);
        assertThat(query.metadataFilters().get("key")).isEqualTo("value");
        assertThat(query.metadataFilters().get("new")).isNull();
    }

    @Test
    void shouldHandleNullMetadataFilters() {
        // When
        LLMMemoryQuery query = LLMMemoryQuery.builder("test")
            .metadataFilters(null)
            .build();

        // Then
        assertThat(query.metadataFilters()).isEmpty();
        assertThat(query.hasMetadataFilters()).isFalse();
    }

    @Test
    void shouldCheckHasMetadataFilters() {
        // Given
        LLMMemoryQuery withFilters = LLMMemoryQuery.builder("test")
            .metadata("key", "value")
            .build();

        LLMMemoryQuery withoutFilters = LLMMemoryQuery.builder("test").build();

        // Then
        assertThat(withFilters.hasMetadataFilters()).isTrue();
        assertThat(withoutFilters.hasMetadataFilters()).isFalse();
    }

    @Test
    void shouldGetMetadataFilter() {
        // Given
        LLMMemoryQuery query = LLMMemoryQuery.builder("test")
            .metadata("category", "support")
            .metadata("priority", 5)
            .build();

        // Then
        assertThat(query.getMetadataFilter("category")).isEqualTo("support");
        assertThat(query.getMetadataFilter("priority")).isEqualTo(5);
        assertThat(query.getMetadataFilter("nonexistent")).isNull();
    }

    @Test
    void shouldCheckScopeTypes() {
        // Given
        LLMMemoryQuery longTerm = LLMMemoryQuery.builder("test")
            .scope(MemoryScope.LONG_TERM)
            .build();

        LLMMemoryQuery shortTerm = LLMMemoryQuery.builder("test")
            .scope(MemoryScope.SHORT_TERM)
            .build();

        // Then
        assertThat(longTerm.isLongTermOnly()).isTrue();
        assertThat(longTerm.isShortTermOnly()).isFalse();

        assertThat(shortTerm.isLongTermOnly()).isFalse();
        assertThat(shortTerm.isShortTermOnly()).isTrue();
    }

    @Test
    void shouldBeImmutable() {
        // Given
        LLMMemoryQuery query = LLMMemoryQuery.builder("test")
            .metadata("key", "value")
            .build();

        // When/Then - should not be able to modify metadata
        assertThatThrownBy(() ->
            query.metadataFilters().put("new", "value")
        ).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldHaveReadableToString() {
        // Given
        LLMMemoryQuery query = LLMMemoryQuery.builder("user preferences for dark mode")
            .maxTokens(500)
            .scope(MemoryScope.LONG_TERM)
            .metadata("category", "ui")
            .build();

        // When
        String str = query.toString();

        // Then
        assertThat(str).contains("user preferences");
        assertThat(str).contains("500");
        assertThat(str).contains("LONG_TERM");
        assertThat(str).contains("filters=1");
    }

    @Test
    void shouldTruncateLongQueryInToString() {
        // Given
        String longQuery = "This is a very long query that exceeds fifty characters and should be truncated";
        LLMMemoryQuery query = LLMMemoryQuery.builder(longQuery).build();

        // When
        String str = query.toString();

        // Then
        assertThat(str).contains("...");
        assertThat(str.length()).isLessThan(longQuery.length() + 100);
    }

    @Test
    void shouldSupportEqualityComparison() {
        // Given
        LLMMemoryQuery query1 = LLMMemoryQuery.builder("test")
            .maxTokens(500)
            .metadata("key", "value")
            .build();

        LLMMemoryQuery query2 = LLMMemoryQuery.builder("test")
            .maxTokens(500)
            .metadata("key", "value")
            .build();

        LLMMemoryQuery different = LLMMemoryQuery.builder("test")
            .maxTokens(1000)
            .build();

        // Then
        assertThat(query1).isEqualTo(query2);
        assertThat(query1).isNotEqualTo(different);
        assertThat(query1.hashCode()).isEqualTo(query2.hashCode());
    }
}
