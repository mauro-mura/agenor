package dev.agenor.runtime.filter;

import dev.agenor.core.Message;
import dev.agenor.core.filter.MessageFilter;
import dev.agenor.core.filter.MessageFilterBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.*;

class MessageFilterTest {

    // =========================================================================
    // BASIC FILTER TESTS
    // =========================================================================

    @Test
    @DisplayName("Should accept all messages")
    void shouldAcceptAll() {
        MessageFilter filter = MessageFilter.acceptAll();

        Message msg = Message.builder()
            .topic("test")
            .content("data")
            .build();

        assertThat(filter.test(msg)).isTrue();
    }

    @Test
    @DisplayName("Should reject all messages")
    void shouldRejectAll() {
        MessageFilter filter = MessageFilter.rejectAll();

        Message msg = Message.builder()
            .topic("test")
            .content("data")
            .build();

        assertThat(filter.test(msg)).isFalse();
    }

    // =========================================================================
    // TOPIC FILTER TESTS
    // =========================================================================

    @Test
    @DisplayName("Should filter by exact topic")
    void shouldFilterByExactTopic() {
        MessageFilter filter = TopicFilter.exact("order.created");

        Message match = Message.builder().topic("order.created").build();
        Message noMatch = Message.builder().topic("order.updated").build();

        assertThat(filter.test(match)).isTrue();
        assertThat(filter.test(noMatch)).isFalse();
    }

    @Test
    @DisplayName("Should filter by topic prefix")
    void shouldFilterByTopicPrefix() {
        MessageFilter filter = TopicFilter.startsWith("order.");

        Message match1 = Message.builder().topic("order.created").build();
        Message match2 = Message.builder().topic("order.updated").build();
        Message noMatch = Message.builder().topic("product.created").build();

        assertThat(filter.test(match1)).isTrue();
        assertThat(filter.test(match2)).isTrue();
        assertThat(filter.test(noMatch)).isFalse();
    }

    @Test
    @DisplayName("Should filter by topic wildcard")
    void shouldFilterByTopicWildcard() {
        MessageFilter filter = TopicFilter.wildcard("order.*");

        Message match = Message.builder().topic("order.created").build();
        Message noMatch = Message.builder().topic("product.created").build();

        assertThat(filter.test(match)).isTrue();
        assertThat(filter.test(noMatch)).isFalse();
    }

    @Test
    @DisplayName("Should filter by topic regex")
    void shouldFilterByTopicRegex() {
        MessageFilter filter = TopicFilter.regex("order\\.(created|updated)");

        Message match1 = Message.builder().topic("order.created").build();
        Message match2 = Message.builder().topic("order.updated").build();
        Message noMatch = Message.builder().topic("order.deleted").build();

        assertThat(filter.test(match1)).isTrue();
        assertThat(filter.test(match2)).isTrue();
        assertThat(filter.test(noMatch)).isFalse();
    }

    // =========================================================================
    // HEADER FILTER TESTS
    // =========================================================================

    @Test
    @DisplayName("Should filter by header existence")
    void shouldFilterByHeaderExistence() {
        MessageFilter filter = HeaderFilter.exists("priority");

        Message match = Message.builder()
            .topic("test")
            .header("priority", "HIGH")
            .build();

        Message noMatch = Message.builder()
            .topic("test")
            .build();

        assertThat(filter.test(match)).isTrue();
        assertThat(filter.test(noMatch)).isFalse();
    }

    @Test
    @DisplayName("Should filter by header value")
    void shouldFilterByHeaderValue() {
        MessageFilter filter = HeaderFilter.equals("priority", "HIGH");

        Message match = Message.builder()
            .topic("test")
            .header("priority", "HIGH")
            .build();

        Message noMatch = Message.builder()
            .topic("test")
            .header("priority", "LOW")
            .build();

        assertThat(filter.test(match)).isTrue();
        assertThat(filter.test(noMatch)).isFalse();
    }

    @Test
    @DisplayName("Should filter by header regex")
    void shouldFilterByHeaderRegex() {
        MessageFilter filter = HeaderFilter.matches("region", "us-.*");

        Message match1 = Message.builder()
            .topic("test")
            .header("region", "us-east-1")
            .build();

        Message match2 = Message.builder()
            .topic("test")
            .header("region", "us-west-2")
            .build();

        Message noMatch = Message.builder()
            .topic("test")
            .header("region", "eu-west-1")
            .build();

        assertThat(filter.test(match1)).isTrue();
        assertThat(filter.test(match2)).isTrue();
        assertThat(filter.test(noMatch)).isFalse();
    }

    @Test
    @DisplayName("Should filter by header in set")
    void shouldFilterByHeaderIn() {
        MessageFilter filter = HeaderFilter.in("priority", "HIGH", "CRITICAL");

        Message match1 = Message.builder()
            .topic("test")
            .header("priority", "HIGH")
            .build();

        Message match2 = Message.builder()
            .topic("test")
            .header("priority", "CRITICAL")
            .build();

        Message noMatch = Message.builder()
            .topic("test")
            .header("priority", "LOW")
            .build();

        assertThat(filter.test(match1)).isTrue();
        assertThat(filter.test(match2)).isTrue();
        assertThat(filter.test(noMatch)).isFalse();
    }

    // =========================================================================
    // CONTENT FILTER TESTS
    // =========================================================================

    @Test
    @DisplayName("Should filter by content type")
    void shouldFilterByContentType() {
        MessageFilter filter = ContentFilter.ofType(String.class);

        Message match = Message.builder()
            .topic("test")
            .content("text data")
            .build();

        Message noMatch = Message.builder()
            .topic("test")
            .content(12345)
            .build();

        assertThat(filter.test(match)).isTrue();
        assertThat(filter.test(noMatch)).isFalse();
    }

    @Test
    @DisplayName("Should filter by content predicate")
    void shouldFilterByContentPredicate() {
        MessageFilter filter = ContentFilter.matching(
            content -> content instanceof String && ((String) content).length() > 5
        );

        Message match = Message.builder()
            .topic("test")
            .content("long text")
            .build();

        Message noMatch = Message.builder()
            .topic("test")
            .content("short")
            .build();

        assertThat(filter.test(match)).isTrue();
        assertThat(filter.test(noMatch)).isFalse();
    }

    // =========================================================================
    // COMPOSITE FILTER TESTS
    // =========================================================================

    @Test
    @DisplayName("Should combine filters with AND")
    void shouldCombineWithAnd() {
        MessageFilter filter = CompositeFilter.and(
            TopicFilter.startsWith("order."),
            HeaderFilter.equals("priority", "HIGH")
        );

        Message match = Message.builder()
            .topic("order.created")
            .header("priority", "HIGH")
            .build();

        Message noMatch1 = Message.builder()
            .topic("product.created")
            .header("priority", "HIGH")
            .build();

        Message noMatch2 = Message.builder()
            .topic("order.created")
            .header("priority", "LOW")
            .build();

        assertThat(filter.test(match)).isTrue();
        assertThat(filter.test(noMatch1)).isFalse();
        assertThat(filter.test(noMatch2)).isFalse();
    }

    @Test
    @DisplayName("Should combine filters with OR")
    void shouldCombineWithOr() {
        MessageFilter filter = CompositeFilter.or(
            TopicFilter.exact("order.created"),
            TopicFilter.exact("order.updated")
        );

        Message match1 = Message.builder().topic("order.created").build();
        Message match2 = Message.builder().topic("order.updated").build();
        Message noMatch = Message.builder().topic("order.deleted").build();

        assertThat(filter.test(match1)).isTrue();
        assertThat(filter.test(match2)).isTrue();
        assertThat(filter.test(noMatch)).isFalse();
    }

    @Test
    @DisplayName("Should negate filter with NOT")
    void shouldNegateFilter() {
        MessageFilter filter = CompositeFilter.not(
            TopicFilter.exact("order.created")
        );

        Message match = Message.builder().topic("order.updated").build();
        Message noMatch = Message.builder().topic("order.created").build();

        assertThat(filter.test(match)).isTrue();
        assertThat(filter.test(noMatch)).isFalse();
    }

    // =========================================================================
    // BUILDER TESTS
    // =========================================================================

    @Test
    @DisplayName("Should build complex filter with builder")
    void shouldBuildComplexFilter() {
        MessageFilter filter = MessageFilter.builder()
            .topicStartsWith("order.")
            .headerEquals("priority", "HIGH")
            .contentType(String.class)
            .build();

        Message match = Message.builder()
            .topic("order.created")
            .header("priority", "HIGH")
            .content("order data")
            .build();

        Message noMatch = Message.builder()
            .topic("order.created")
            .header("priority", "LOW")
            .content("order data")
            .build();

        assertThat(filter.test(match)).isTrue();
        assertThat(filter.test(noMatch)).isFalse();
    }

    @Test
    @DisplayName("Should build filter with OR operator")
    void shouldBuildFilterWithOr() {
        MessageFilter filter = MessageFilter.builder()
            .operator(MessageFilterBuilder.FilterOperator.OR)
            .headerEquals("priority", "HIGH")
            .headerEquals("priority", "CRITICAL")
            .build();

        Message match1 = Message.builder()
            .topic("test")
            .header("priority", "HIGH")
            .build();

        Message match2 = Message.builder()
            .topic("test")
            .header("priority", "CRITICAL")
            .build();

        Message noMatch = Message.builder()
            .topic("test")
            .header("priority", "LOW")
            .build();

        assertThat(filter.test(match1)).isTrue();
        assertThat(filter.test(match2)).isTrue();
        assertThat(filter.test(noMatch)).isFalse();
    }

    @Test
    @DisplayName("Should chain filter methods")
    void shouldChainFilterMethods() {
        MessageFilter filter = MessageFilter.builder()
            .topicStartsWith("order.")
            .build()
            .and(HeaderFilter.equals("priority", "HIGH"));

        Message match = Message.builder()
            .topic("order.created")
            .header("priority", "HIGH")
            .build();

        Message noMatch = Message.builder()
            .topic("order.created")
            .header("priority", "LOW")
            .build();

        assertThat(filter.test(match)).isTrue();
        assertThat(filter.test(noMatch)).isFalse();
    }

    // =========================================================================
    // PREDICATE FILTER TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create filter from predicate")
    void shouldCreateFromPredicate() {
        MessageFilter filter = MessageFilter.of(
            msg -> msg.topic().startsWith("order.") &&
                   msg.headers().containsKey("customer-id")
        );

        Message match = Message.builder()
            .topic("order.created")
            .header("customer-id", "12345")
            .build();

        Message noMatch = Message.builder()
            .topic("order.created")
            .build();

        assertThat(filter.test(match)).isTrue();
        assertThat(filter.test(noMatch)).isFalse();
    }

    // =========================================================================
    // EDGE CASES
    // =========================================================================

    @Test
    @DisplayName("Should handle null topic gracefully")
    void shouldHandleNullTopic() {
        MessageFilter filter = TopicFilter.exact("test");

        Message msg = Message.builder()
            .topic(null)
            .build();

        assertThat(filter.test(msg)).isFalse();
    }

    @Test
    @DisplayName("Should handle null content gracefully")
    void shouldHandleNullContent() {
        MessageFilter filter = ContentFilter.ofType(String.class);

        Message msg = Message.builder()
            .topic("test")
            .content(null)
            .build();

        assertThat(filter.test(msg)).isFalse();
    }

    @Test
    @DisplayName("Should handle missing headers gracefully")
    void shouldHandleMissingHeaders() {
        MessageFilter filter = HeaderFilter.equals("priority", "HIGH");

        Message msg = Message.builder()
            .topic("test")
            .build();

        assertThat(filter.test(msg)).isFalse();
    }

    @Test
    @DisplayName("Should build empty filter as acceptAll")
    void shouldBuildEmptyFilterAsAcceptAll() {
        MessageFilter filter = MessageFilter.builder().build();

        Message msg = Message.builder()
            .topic("test")
            .build();

        assertThat(filter.test(msg)).isTrue();
    }
}
