package dev.agenor.core.composite;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.*;

@DisplayName("CompletionStrategy Tests")
class CompletionStrategyTest {

    @Test
    @DisplayName("Should have correct number of strategies")
    void shouldHaveCorrectNumberOfStrategies() {
        assertThat(CompletionStrategy.values()).hasSize(4);
    }

    @ParameterizedTest
    @EnumSource(CompletionStrategy.class)
    @DisplayName("Should convert each strategy to string and back")
    void shouldConvertToStringAndBack(CompletionStrategy strategy) {
        String name = strategy.name();
        CompletionStrategy converted = CompletionStrategy.valueOf(name);
        assertThat(converted).isEqualTo(strategy);
    }

    @Test
    @DisplayName("Should maintain correct enum order")
    void shouldMaintainCorrectEnumOrder() {
        CompletionStrategy[] values = CompletionStrategy.values();
        assertThat(values[0]).isEqualTo(CompletionStrategy.ALL);
        assertThat(values[1]).isEqualTo(CompletionStrategy.ANY);
        assertThat(values[2]).isEqualTo(CompletionStrategy.FIRST);
        assertThat(values[3]).isEqualTo(CompletionStrategy.N_OF_M);
    }

    @Test
    @DisplayName("Should throw exception for invalid strategy name")
    void shouldThrowExceptionForInvalidName() {
        assertThatThrownBy(() -> CompletionStrategy.valueOf("INVALID"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should be case sensitive")
    void shouldBeCaseSensitive() {
        assertThatThrownBy(() -> CompletionStrategy.valueOf("all"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should throw exception for null value")
    void shouldThrowExceptionForNull() {
        assertThatThrownBy(() -> CompletionStrategy.valueOf(null))
            .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @EnumSource(CompletionStrategy.class)
    @DisplayName("Should support equality comparison")
    void shouldSupportEqualityComparison(CompletionStrategy strategy) {
        assertThat(strategy).isEqualTo(strategy);
        assertThat(strategy.equals(strategy)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(CompletionStrategy.class)
    @DisplayName("Should have consistent hashCode")
    void shouldHaveConsistentHashCode(CompletionStrategy strategy) {
        int hash1 = strategy.hashCode();
        int hash2 = strategy.hashCode();
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    @DisplayName("Should compare strategies using identity")
    void shouldCompareUsingIdentity() {
        CompletionStrategy all1 = CompletionStrategy.ALL;
        CompletionStrategy all2 = CompletionStrategy.ALL;
        assertThat(all1).isSameAs(all2);
    }
}
