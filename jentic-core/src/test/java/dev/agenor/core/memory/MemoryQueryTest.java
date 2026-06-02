package dev.agenor.core.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("MemoryQuery Tests")
class MemoryQueryTest {

    @Test
    @DisplayName("Should create query with builder")
    void testBuilderCreation() {
        MemoryQuery query = MemoryQuery.builder()
            .text("search term")
            .scope(MemoryScope.LONG_TERM)
            .ownerId("agent-1")
            .filter("status", "active")
            .filter("priority", 5)
            .limit(20)
            .build();

        assertThat(query.text()).isEqualTo("search term");
        assertThat(query.scope()).isEqualTo(MemoryScope.LONG_TERM);
        assertThat(query.ownerId()).isEqualTo("agent-1");
        assertThat(query.filters()).containsEntry("status", "active");
        assertThat(query.filters()).containsEntry("priority", 5);
        assertThat(query.limit()).isEqualTo(20);
    }

    @Test
    @DisplayName("Should create minimal query with defaults")
    void testMinimalQuery() {
        MemoryQuery query = MemoryQuery.builder().build();

        assertThat(query.text()).isNull();
        assertThat(query.scope()).isEqualTo(MemoryScope.SHORT_TERM);
        assertThat(query.ownerId()).isNull();
        assertThat(query.filters()).isEmpty();
        assertThat(query.limit()).isEqualTo(10);
    }

    @Test
    @DisplayName("Should reject null scope")
    void testNullScope() {
        assertThatThrownBy(() ->
            MemoryQuery.builder()
                .scope(null)
                .build()
        ).isInstanceOf(NullPointerException.class)
         .hasMessageContaining("Scope cannot be null");
    }

    @Test
    @DisplayName("Should reject zero limit")
    void testZeroLimit() {
        assertThatThrownBy(() ->
            MemoryQuery.builder()
                .limit(0)
                .build()
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("Limit must be positive");
    }

    @Test
    @DisplayName("Should reject negative limit")
    void testNegativeLimit() {
        assertThatThrownBy(() ->
            MemoryQuery.builder()
                .limit(-5)
                .build()
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("Limit must be positive");
    }

    @Test
    @DisplayName("Should reject limit over 1000")
    void testLimitTooHigh() {
        assertThatThrownBy(() ->
            MemoryQuery.builder()
                .limit(1001)
                .build()
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("Limit cannot exceed 1000");
    }

    @Test
    @DisplayName("Should accept limit at boundary")
    void testLimitBoundary() {
        MemoryQuery query = MemoryQuery.builder()
            .limit(1000)
            .build();

        assertThat(query.limit()).isEqualTo(1000);
    }

    @Test
    @DisplayName("Should check text filter presence")
    void testHasTextFilter() {
        MemoryQuery withText = MemoryQuery.builder()
            .text("search")
            .build();
        assertThat(withText.hasTextFilter()).isTrue();

        MemoryQuery withoutText = MemoryQuery.builder().build();
        assertThat(withoutText.hasTextFilter()).isFalse();

        MemoryQuery withBlankText = MemoryQuery.builder()
            .text("   ")
            .build();
        assertThat(withBlankText.hasTextFilter()).isFalse();
    }

    @Test
    @DisplayName("Should check owner filter presence")
    void testHasOwnerFilter() {
        MemoryQuery withOwner = MemoryQuery.builder()
            .ownerId("agent-1")
            .build();
        assertThat(withOwner.hasOwnerFilter()).isTrue();

        MemoryQuery withoutOwner = MemoryQuery.builder().build();
        assertThat(withoutOwner.hasOwnerFilter()).isFalse();

        MemoryQuery withBlankOwner = MemoryQuery.builder()
            .ownerId("   ")
            .build();
        assertThat(withBlankOwner.hasOwnerFilter()).isFalse();
    }

    @Test
    @DisplayName("Should check metadata filter presence")
    void testHasMetadataFilters() {
        MemoryQuery withFilters = MemoryQuery.builder()
            .filter("key", "value")
            .build();
        assertThat(withFilters.hasMetadataFilters()).isTrue();

        MemoryQuery withoutFilters = MemoryQuery.builder().build();
        assertThat(withoutFilters.hasMetadataFilters()).isFalse();
    }

    @Test
    @DisplayName("Should get typed filter")
    void testTypedFilter() {
        MemoryQuery query = MemoryQuery.builder()
            .filter("stringKey", "value")
            .filter("intKey", 42)
            .filter("boolKey", true)
            .build();

        assertThat(query.getFilter("stringKey", String.class)).isEqualTo("value");
        assertThat(query.getFilter("intKey", Integer.class)).isEqualTo(42);
        assertThat(query.getFilter("boolKey", Boolean.class)).isTrue();
        assertThat(query.getFilter("missing", String.class)).isNull();
    }

    @Test
    @DisplayName("Should support bulk filters")
    void testBulkFilters() {
        Map<String, Object> filters = Map.of(
            "key1", "value1",
            "key2", 42,
            "key3", true
        );

        MemoryQuery query = MemoryQuery.builder()
            .filters(filters)
            .build();

        assertThat(query.filters()).containsAllEntriesOf(filters);
    }

    @Test
    @DisplayName("Should be immutable")
    void testImmutability() {
        Map<String, Object> filters = Map.of("key", "value");

        MemoryQuery query = MemoryQuery.builder()
            .filters(filters)
            .build();

        // Returned filters map should be immutable
        assertThatThrownBy(() -> query.filters().put("new", "value"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Should set scope correctly")
    void testScopeSelection() {
        MemoryQuery shortTerm = MemoryQuery.builder()
            .scope(MemoryScope.SHORT_TERM)
            .build();
        assertThat(shortTerm.scope()).isEqualTo(MemoryScope.SHORT_TERM);

        MemoryQuery longTerm = MemoryQuery.builder()
            .scope(MemoryScope.LONG_TERM)
            .build();
        assertThat(longTerm.scope()).isEqualTo(MemoryScope.LONG_TERM);
    }
}
