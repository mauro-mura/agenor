package dev.agenor.runtime.filter;

import dev.agenor.core.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class PredicateFilterTest {

    // =========================================================================
    // BEHAVIOR
    // =========================================================================

    @Test
    @DisplayName("Should accept message matching predicate")
    void shouldAcceptMatchingMessage() {
        PredicateFilter filter = new PredicateFilter(msg -> "order.created".equals(msg.topic()));

        Message match = Message.builder().topic("order.created").build();
        Message noMatch = Message.builder().topic("order.updated").build();

        assertThat(filter.test(match)).isTrue();
        assertThat(filter.test(noMatch)).isFalse();
    }

    @Test
    @DisplayName("Single-arg constructor should set default description")
    void singleArgConstructorShouldSetDefaultDescription() {
        PredicateFilter filter = new PredicateFilter(msg -> true);

        assertThat(filter.getDescription()).isEqualTo("custom-predicate");
    }

    @Test
    @DisplayName("Two-arg constructor should preserve custom description")
    void twoArgConstructorShouldPreserveDescription() {
        PredicateFilter filter = new PredicateFilter(msg -> true, "high-priority-filter");

        assertThat(filter.getDescription()).isEqualTo("high-priority-filter");
    }

    @Test
    @DisplayName("toString() should include description")
    void toStringShouldIncludeDescription() {
        PredicateFilter filter = new PredicateFilter(msg -> true, "my-filter");

        assertThat(filter.toString()).contains("my-filter");
        assertThat(filter.toString()).startsWith("PredicateFilter[");
    }

    @Test
    @DisplayName("Should handle complex predicate with header checks")
    void shouldHandleComplexPredicate() {
        PredicateFilter filter = new PredicateFilter(
                msg -> msg.topic() != null && msg.headers().containsKey("x-trace-id"),
                "traced-messages"
        );

        Message traced = Message.builder()
                .topic("event.published")
                .header("x-trace-id", "abc-123")
                .build();

        Message notTraced = Message.builder()
                .topic("event.published")
                .build();

        assertThat(filter.test(traced)).isTrue();
        assertThat(filter.test(notTraced)).isFalse();
    }
}
