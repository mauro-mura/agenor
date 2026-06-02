package dev.agenor.core.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MemoryScope Tests")
class MemoryScopeTest {

    @Test
    @DisplayName("Should identify short-term scope")
    void testShortTermIdentification() {
        assertThat(MemoryScope.SHORT_TERM.isShortTerm()).isTrue();
        assertThat(MemoryScope.SHORT_TERM.isLongTerm()).isFalse();
    }

    @Test
    @DisplayName("Should identify long-term scope")
    void testLongTermIdentification() {
        assertThat(MemoryScope.LONG_TERM.isLongTerm()).isTrue();
        assertThat(MemoryScope.LONG_TERM.isShortTerm()).isFalse();
    }

    @Test
    @DisplayName("Should have exactly two values")
    void testEnumValues() {
        MemoryScope[] values = MemoryScope.values();
        assertThat(values).hasSize(2);
        assertThat(values).containsExactlyInAnyOrder(
            MemoryScope.SHORT_TERM,
            MemoryScope.LONG_TERM
        );
    }

    @Test
    @DisplayName("Should support valueOf")
    void testValueOf() {
        assertThat(MemoryScope.valueOf("SHORT_TERM")).isEqualTo(MemoryScope.SHORT_TERM);
        assertThat(MemoryScope.valueOf("LONG_TERM")).isEqualTo(MemoryScope.LONG_TERM);
    }
}
