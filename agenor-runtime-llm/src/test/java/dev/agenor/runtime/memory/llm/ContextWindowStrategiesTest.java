package dev.agenor.runtime.memory.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;

import org.junit.jupiter.api.Test;

import dev.agenor.core.memory.llm.ContextWindowStrategy;

class ContextWindowStrategiesTest {

    // ========== CONSTANTS ==========

    @Test
    void FIXED_shouldBeNonNull() {
        assertThat(ContextWindowStrategies.FIXED).isNotNull();
    }

    @Test
    void SLIDING_shouldBeNonNull() {
        assertThat(ContextWindowStrategies.SLIDING).isNotNull();
    }

    @Test
    void SUMMARIZED_shouldBeNonNull() {
        assertThat(ContextWindowStrategies.SUMMARIZED).isNotNull();
    }

    @Test
    void FIXED_shouldBeFixedWindowStrategy() {
        assertThat(ContextWindowStrategies.FIXED).isInstanceOf(FixedWindowStrategy.class);
    }

    @Test
    void SLIDING_shouldBeSlidingWindowStrategy() {
        assertThat(ContextWindowStrategies.SLIDING).isInstanceOf(SlidingWindowStrategy.class);
    }

    @Test
    void SUMMARIZED_shouldBeSummarizationStrategy() {
        assertThat(ContextWindowStrategies.SUMMARIZED).isInstanceOf(SummarizationStrategy.class);
    }

    // ========== forName ==========

    @Test
    void forName_shouldReturnFixedForFixed() {
        ContextWindowStrategy strategy = ContextWindowStrategies.forName("fixed");
        assertThat(strategy).isSameAs(ContextWindowStrategies.FIXED);
    }

    @Test
    void forName_shouldReturnSlidingForSliding() {
        ContextWindowStrategy strategy = ContextWindowStrategies.forName("sliding");
        assertThat(strategy).isSameAs(ContextWindowStrategies.SLIDING);
    }

    @Test
    void forName_shouldReturnSummarizedForSummarized() {
        ContextWindowStrategy strategy = ContextWindowStrategies.forName("summarized");
        assertThat(strategy).isSameAs(ContextWindowStrategies.SUMMARIZED);
    }

    @Test
    void forName_shouldReturnSummarizedForSummary() {
        ContextWindowStrategy strategy = ContextWindowStrategies.forName("summary");
        assertThat(strategy).isSameAs(ContextWindowStrategies.SUMMARIZED);
    }

    @Test
    void forName_shouldBeCaseInsensitive() {
        assertThat(ContextWindowStrategies.forName("FIXED"))
            .isSameAs(ContextWindowStrategies.FIXED);
        assertThat(ContextWindowStrategies.forName("Fixed"))
            .isSameAs(ContextWindowStrategies.FIXED);
        assertThat(ContextWindowStrategies.forName("SLIDING"))
            .isSameAs(ContextWindowStrategies.SLIDING);
    }

    @Test
    void forName_shouldTrimWhitespace() {
        assertThat(ContextWindowStrategies.forName("  fixed  "))
            .isSameAs(ContextWindowStrategies.FIXED);
    }

    @Test
    void forName_shouldThrowForNull() {
        assertThatThrownBy(() -> ContextWindowStrategies.forName(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be null or empty");
    }

    @Test
    void forName_shouldThrowForEmpty() {
        assertThatThrownBy(() -> ContextWindowStrategies.forName(""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be null or empty");
    }

    @Test
    void forName_shouldThrowForBlank() {
        assertThatThrownBy(() -> ContextWindowStrategies.forName("   "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be null or empty");
    }

    @Test
    void forName_shouldThrowForUnknownName() {
        assertThatThrownBy(() -> ContextWindowStrategies.forName("unknown"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown strategy");
    }

    // ========== values / names ==========

    @Test
    void values_shouldReturnAllThreeStrategies() {
        ContextWindowStrategy[] values = ContextWindowStrategies.values();
        assertThat(values).hasSize(3);
        assertThat(values).containsExactlyInAnyOrder(
            ContextWindowStrategies.FIXED,
            ContextWindowStrategies.SLIDING,
            ContextWindowStrategies.SUMMARIZED
        );
    }

    @Test
    void names_shouldReturnThreeNames() {
        String[] names = ContextWindowStrategies.names();
        assertThat(names).hasSize(3);
        assertThat(names).containsExactlyInAnyOrder("fixed", "sliding", "summarized");
    }

    // ========== Cannot instantiate ==========

    @Test
    void constructor_shouldThrowAssertionError() throws Exception {
        Constructor<ContextWindowStrategies> constructor =
            ContextWindowStrategies.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThatThrownBy(constructor::newInstance)
            .hasCauseInstanceOf(AssertionError.class);
    }
}
