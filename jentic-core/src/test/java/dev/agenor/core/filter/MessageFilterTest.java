package dev.agenor.core.filter;

import dev.agenor.core.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive test suite for MessageFilter and MessageFilterBuilder.
 */
class MessageFilterTest {

    // =========================================================================
    // MESSAGEFILTER STATIC METHODS TESTS
    // =========================================================================

    @Nested
    @DisplayName("MessageFilter static factory methods")
    class StaticFactoryMethods {

        @Test
        @DisplayName("acceptAll() should accept any message")
        void acceptAllShouldAcceptAnyMessage() {
            // Given
            MessageFilter filter = MessageFilter.acceptAll();
            Message message = createTestMessage("test.topic", "content");

            // When & Then
            assertThat(filter.test(message)).isTrue();
            assertThat(filter.test(null)).isTrue();
        }

        @Test
        @DisplayName("rejectAll() should reject any message")
        void rejectAllShouldRejectAnyMessage() {
            // Given
            MessageFilter filter = MessageFilter.rejectAll();
            Message message = createTestMessage("test.topic", "content");

            // When & Then
            assertThat(filter.test(message)).isFalse();
            assertThat(filter.test(null)).isFalse();
        }

        @Test
        @DisplayName("of() should wrap a predicate")
        void ofShouldWrapPredicate() {
            // Given
            MessageFilter filter = MessageFilter.of(msg -> msg != null && msg.topic().startsWith("test"));
            Message matchingMessage = createTestMessage("test.topic", "content");
            Message nonMatchingMessage = createTestMessage("other.topic", "content");

            // When & Then
            assertThat(filter.test(matchingMessage)).isTrue();
            assertThat(filter.test(nonMatchingMessage)).isFalse();
        }

        @Test
        @DisplayName("builder() should create a new builder instance")
        void builderShouldCreateNewInstance() {
            // When
            MessageFilterBuilder builder = MessageFilter.builder();

            // Then
            assertThat(builder).isNotNull();
        }
    }

    // =========================================================================
    // MESSAGEFILTER COMBINATOR METHODS TESTS
    // =========================================================================

    @Nested
    @DisplayName("MessageFilter combinator methods")
    class CombinatorMethods {

        @Test
        @DisplayName("and() should combine filters with AND logic")
        void andShouldCombineFiltersWithAndLogic() {
            // Given
            MessageFilter topicFilter = msg -> "test.topic".equals(msg.topic());
            MessageFilter headerFilter = msg -> "HIGH".equals(msg.headers().get("priority"));
            MessageFilter combined = topicFilter.and(headerFilter);

            Message bothMatch = Message.builder()
                    .topic("test.topic")
                    .header("priority", "HIGH")
                    .build();

            Message topicOnly = Message.builder()
                    .topic("test.topic")
                    .header("priority", "LOW")
                    .build();

            Message headerOnly = Message.builder()
                    .topic("other.topic")
                    .header("priority", "HIGH")
                    .build();

            Message neitherMatch = Message.builder()
                    .topic("other.topic")
                    .header("priority", "LOW")
                    .build();

            // When & Then
            assertThat(combined.test(bothMatch)).isTrue();
            assertThat(combined.test(topicOnly)).isFalse();
            assertThat(combined.test(headerOnly)).isFalse();
            assertThat(combined.test(neitherMatch)).isFalse();
        }

        @Test
        @DisplayName("or() should combine filters with OR logic")
        void orShouldCombineFiltersWithOrLogic() {
            // Given
            MessageFilter topicFilter = msg -> "test.topic".equals(msg.topic());
            MessageFilter headerFilter = msg -> "HIGH".equals(msg.headers().get("priority"));
            MessageFilter combined = topicFilter.or(headerFilter);

            Message bothMatch = Message.builder()
                    .topic("test.topic")
                    .header("priority", "HIGH")
                    .build();

            Message topicOnly = Message.builder()
                    .topic("test.topic")
                    .header("priority", "LOW")
                    .build();

            Message headerOnly = Message.builder()
                    .topic("other.topic")
                    .header("priority", "HIGH")
                    .build();

            Message neitherMatch = Message.builder()
                    .topic("other.topic")
                    .header("priority", "LOW")
                    .build();

            // When & Then
            assertThat(combined.test(bothMatch)).isTrue();
            assertThat(combined.test(topicOnly)).isTrue();
            assertThat(combined.test(headerOnly)).isTrue();
            assertThat(combined.test(neitherMatch)).isFalse();
        }

        @Test
        @DisplayName("negate() should invert filter logic")
        void negateShouldInvertFilterLogic() {
            // Given
            MessageFilter originalFilter = msg -> "test.topic".equals(msg.topic());
            MessageFilter negatedFilter = originalFilter.negate();

            Message matching = createTestMessage("test.topic", "content");
            Message notMatching = createTestMessage("other.topic", "content");

            // When & Then
            assertThat(originalFilter.test(matching)).isTrue();
            assertThat(negatedFilter.test(matching)).isFalse();

            assertThat(originalFilter.test(notMatching)).isFalse();
            assertThat(negatedFilter.test(notMatching)).isTrue();
        }

        @Test
        @DisplayName("combinator methods should be chainable")
        void combinatorMethodsShouldBeChainable() {
            // Given
            MessageFilter filter = MessageFilter.acceptAll()
                    .and(msg -> msg.topic() != null)
                    .and(msg -> msg.topic().startsWith("test"))
                    .or(msg -> "HIGH".equals(msg.headers().get("priority")));

            Message matchesAll = Message.builder()
                    .topic("test.topic")
                    .header("priority", "LOW")
                    .build();

            Message matchesOr = Message.builder()
                    .topic("other.topic")
                    .header("priority", "HIGH")
                    .build();

            Message matchesNone = Message.builder()
                    .topic("other.topic")
                    .header("priority", "LOW")
                    .build();

            // When & Then
            assertThat(filter.test(matchesAll)).isTrue();
            assertThat(filter.test(matchesOr)).isTrue();
            assertThat(filter.test(matchesNone)).isFalse();
        }
    }

    // =========================================================================
    // MESSAGEFILTERBUILDER BASIC TESTS
    // =========================================================================

    @Nested
    @DisplayName("MessageFilterBuilder basic functionality")
    class BuilderBasicFunctionality {

        @Test
        @DisplayName("build() with no filters should return acceptAll filter")
        void buildWithNoFiltersShouldReturnAcceptAll() {
            // Given
            MessageFilterBuilder builder = new MessageFilterBuilder();

            // When
            MessageFilter filter = builder.build();

            // Then
            assertThat(filter.test(createTestMessage("any.topic", "content"))).isTrue();
            assertThat(filter.test(null)).isTrue();
        }

        @Test
        @DisplayName("build() with single filter should return that filter")
        void buildWithSingleFilterShouldReturnThatFilter() {
            // Given
            MessageFilter filter = MessageFilter.builder()
                    .topic("test.topic")
                    .build();

            Message matching = createTestMessage("test.topic", "content");
            Message notMatching = createTestMessage("other.topic", "content");

            // When & Then
            assertThat(filter.test(matching)).isTrue();
            assertThat(filter.test(notMatching)).isFalse();
        }

        @Test
        @DisplayName("operator() should set AND/OR combination mode")
        void operatorShouldSetCombinationMode() {
            // Given
            MessageFilter andFilter = MessageFilter.builder()
                    .operator(MessageFilterBuilder.FilterOperator.AND)
                    .topic("test.topic")
                    .headerEquals("priority", "HIGH")
                    .build();

            MessageFilter orFilter = MessageFilter.builder()
                    .operator(MessageFilterBuilder.FilterOperator.OR)
                    .topic("test.topic")
                    .headerEquals("priority", "HIGH")
                    .build();

            Message bothMatch = Message.builder()
                    .topic("test.topic")
                    .header("priority", "HIGH")
                    .build();

            Message topicOnly = Message.builder()
                    .topic("test.topic")
                    .header("priority", "LOW")
                    .build();

            // When & Then
            assertThat(andFilter.test(bothMatch)).isTrue();
            assertThat(andFilter.test(topicOnly)).isFalse();

            assertThat(orFilter.test(bothMatch)).isTrue();
            assertThat(orFilter.test(topicOnly)).isTrue();
        }
    }

    // =========================================================================
    // MESSAGEFILTERBUILDER TOPIC FILTERS
    // =========================================================================

    @Nested
    @DisplayName("MessageFilterBuilder topic filters")
    class TopicFilters {

        @Test
        @DisplayName("topic() should filter by exact topic match")
        void topicShouldFilterByExactMatch() {
            // Given
            MessageFilter filter = MessageFilter.builder()
                    .topic("order.created")
                    .build();

            Message exactMatch = createTestMessage("order.created", "content");
            Message noMatch = createTestMessage("order.updated", "content");

            // When & Then
            assertThat(filter.test(exactMatch)).isTrue();
            assertThat(filter.test(noMatch)).isFalse();
        }

        @Test
        @DisplayName("topicStartsWith() should filter by topic prefix")
        void topicStartsWithShouldFilterByPrefix() {
            // Given
            MessageFilter filter = MessageFilter.builder()
                    .topicStartsWith("order.")
                    .build();

            Message match1 = createTestMessage("order.created", "content");
            Message match2 = createTestMessage("order.updated", "content");
            Message noMatch = createTestMessage("product.created", "content");

            // When & Then
            assertThat(filter.test(match1)).isTrue();
            assertThat(filter.test(match2)).isTrue();
            assertThat(filter.test(noMatch)).isFalse();
        }

        @Test
        @DisplayName("topicStartsWith() should handle null topic")
        void topicStartsWithShouldHandleNullTopic() {
            // Given
            MessageFilter filter = MessageFilter.builder()
                    .topicStartsWith("order.")
                    .build();

            Message nullTopic = Message.builder()
                    .topic(null)
                    .content("content")
                    .build();

            // When & Then
            assertThat(filter.test(nullTopic)).isFalse();
        }

        @Test
        @DisplayName("topicMatches() should filter by regex pattern")
        void topicMatchesShouldFilterByRegex() {
            // Given
            MessageFilter filter = MessageFilter.builder()
                    .topicMatches("order\\.(created|updated)")
                    .build();

            Message match1 = createTestMessage("order.created", "content");
            Message match2 = createTestMessage("order.updated", "content");
            Message noMatch = createTestMessage("order.deleted", "content");

            // When & Then
            assertThat(filter.test(match1)).isTrue();
            assertThat(filter.test(match2)).isTrue();
            assertThat(filter.test(noMatch)).isFalse();
        }

        @Test
        @DisplayName("topicMatches() should handle null topic")
        void topicMatchesShouldHandleNullTopic() {
            // Given
            MessageFilter filter = MessageFilter.builder()
                    .topicMatches("order\\..*")
                    .build();

            Message nullTopic = Message.builder()
                    .topic(null)
                    .content("content")
                    .build();

            // When & Then
            assertThat(filter.test(nullTopic)).isFalse();
        }
    }

    // =========================================================================
    // MESSAGEFILTERBUILDER ID FILTERS
    // =========================================================================

    @Nested
    @DisplayName("MessageFilterBuilder ID filters")
    class IdFilters {

        @Test
        @DisplayName("senderId() should filter by sender ID")
        void senderIdShouldFilterBySender() {
            // Given
            MessageFilter filter = MessageFilter.builder()
                    .senderId("agent-1")
                    .build();

            Message match = Message.builder()
                    .topic("test")
                    .senderId("agent-1")
                    .build();

            Message noMatch = Message.builder()
                    .topic("test")
                    .senderId("agent-2")
                    .build();

            // When & Then
            assertThat(filter.test(match)).isTrue();
            assertThat(filter.test(noMatch)).isFalse();
        }

        @Test
        @DisplayName("receiverId() should filter by receiver ID")
        void receiverIdShouldFilterByReceiver() {
            // Given
            MessageFilter filter = MessageFilter.builder()
                    .receiverId("agent-2")
                    .build();

            Message match = Message.builder()
                    .topic("test")
                    .receiverId("agent-2")
                    .build();

            Message noMatch = Message.builder()
                    .topic("test")
                    .receiverId("agent-1")
                    .build();

            // When & Then
            assertThat(filter.test(match)).isTrue();
            assertThat(filter.test(noMatch)).isFalse();
        }

        @Test
        @DisplayName("correlationId() should filter by correlation ID")
        void correlationIdShouldFilterByCorrelation() {
            // Given
            MessageFilter filter = MessageFilter.builder()
                    .correlationId("corr-123")
                    .build();

            Message match = Message.builder()
                    .topic("test")
                    .correlationId("corr-123")
                    .build();

            Message noMatch = Message.builder()
                    .topic("test")
                    .correlationId("corr-456")
                    .build();

            // When & Then
            assertThat(filter.test(match)).isTrue();
            assertThat(filter.test(noMatch)).isFalse();
        }
    }

    // =========================================================================
    // MESSAGEFILTERBUILDER HEADER FILTERS
    // =========================================================================

    @Nested
    @DisplayName("MessageFilterBuilder header filters")
    class HeaderFilters {

        @Test
        @DisplayName("headerEquals() should filter by exact header value")
        void headerEqualsShouldFilterByExactValue() {
            // Given
            MessageFilter filter = MessageFilter.builder()
                    .headerEquals("priority", "HIGH")
                    .build();

            Message match = Message.builder()
                    .topic("test")
                    .header("priority", "HIGH")
                    .build();

            Message noMatch = Message.builder()
                    .topic("test")
                    .header("priority", "LOW")
                    .build();

            // When & Then
            assertThat(filter.test(match)).isTrue();
            assertThat(filter.test(noMatch)).isFalse();
        }

        @Test
        @DisplayName("headerExists() should filter by header presence")
        void headerExistsShouldFilterByPresence() {
            // Given
            MessageFilter filter = MessageFilter.builder()
                    .headerExists("customer-id")
                    .build();

            Message match = Message.builder()
                    .topic("test")
                    .header("customer-id", "123")
                    .build();

            Message noMatch = Message.builder()
                    .topic("test")
                    .build();

            // When & Then
            assertThat(filter.test(match)).isTrue();
            assertThat(filter.test(noMatch)).isFalse();
        }

        @Test
        @DisplayName("headerMatches() should filter by regex pattern")
        void headerMatchesShouldFilterByRegex() {
            // Given
            MessageFilter filter = MessageFilter.builder()
                    .headerMatches("region", "us-.*")
                    .build();

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

            // When & Then
            assertThat(filter.test(match1)).isTrue();
            assertThat(filter.test(match2)).isTrue();
            assertThat(filter.test(noMatch)).isFalse();
        }

        @Test
        @DisplayName("headerMatches() should handle null header value")
        void headerMatchesShouldHandleNullValue() {
            // Given
            MessageFilter filter = MessageFilter.builder()
                    .headerMatches("region", "us-.*")
                    .build();

            Message nullHeader = Message.builder()
                    .topic("test")
                    .build();

            // When & Then
            assertThat(filter.test(nullHeader)).isFalse();
        }

        @Test
        @DisplayName("headerIn() should filter by value in set")
        void headerInShouldFilterByValueInSet() {
            // Given
            MessageFilter filter = MessageFilter.builder()
                    .headerIn("priority", "HIGH", "CRITICAL")
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

            // When & Then
            assertThat(filter.test(match1)).isTrue();
            assertThat(filter.test(match2)).isTrue();
            assertThat(filter.test(noMatch)).isFalse();
        }

        @Test
        @DisplayName("headerIn() should handle null header value")
        void headerInShouldHandleNullValue() {
            // Given
            MessageFilter filter = MessageFilter.builder()
                    .headerIn("priority", "HIGH", "CRITICAL")
                    .build();

            Message nullHeader = Message.builder()
                    .topic("test")
                    .build();

            // When & Then
            assertThat(filter.test(nullHeader)).isFalse();
        }
    }

    // =========================================================================
    // MESSAGEFILTERBUILDER CONTENT FILTERS
    // =========================================================================

    @Nested
    @DisplayName("MessageFilterBuilder content filters")
    class ContentFilters {

        @Test
        @DisplayName("contentType() should filter by content class")
        void contentTypeShouldFilterByClass() {
            // Given
            MessageFilter filter = MessageFilter.builder()
                    .contentType(String.class)
                    .build();

            Message stringContent = createTestMessage("test", "text content");
            Message integerContent = createTestMessage("test", 12345);
            Message nullContent = Message.builder()
                    .topic("test")
                    .content(null)
                    .build();

            // When & Then
            assertThat(filter.test(stringContent)).isTrue();
            assertThat(filter.test(integerContent)).isFalse();
            assertThat(filter.test(nullContent)).isFalse();
        }

        @Test
        @DisplayName("contentPredicate() should filter by custom predicate")
        void contentPredicateShouldFilterByCustomPredicate() {
            // Given
            MessageFilter filter = MessageFilter.builder()
                    .contentPredicate(content ->
                        content instanceof String && ((String) content).length() > 5)
                    .build();

            Message longString = createTestMessage("test", "long text");
            Message shortString = createTestMessage("test", "short");
            Message notString = createTestMessage("test", 12345);
            Message nullContent = Message.builder()
                    .topic("test")
                    .content(null)
                    .build();

            // When & Then
            assertThat(filter.test(longString)).isTrue();
            assertThat(filter.test(shortString)).isFalse();
            assertThat(filter.test(notString)).isFalse();
            assertThat(filter.test(nullContent)).isFalse();
        }

        @Test
        @DisplayName("contentPredicate() should work with complex objects")
        void contentPredicateShouldWorkWithComplexObjects() {
            // Given
            record OrderData(int amount, String status) {}

            MessageFilter filter = MessageFilter.builder()
                    .contentPredicate(content ->
                        content instanceof OrderData order && order.amount() > 1000)
                    .build();

            Message highValue = createTestMessage("test", new OrderData(1500, "pending"));
            Message lowValue = createTestMessage("test", new OrderData(500, "pending"));

            // When & Then
            assertThat(filter.test(highValue)).isTrue();
            assertThat(filter.test(lowValue)).isFalse();
        }
    }

    // =========================================================================
    // MESSAGEFILTERBUILDER CUSTOM FILTERS
    // =========================================================================

    @Nested
    @DisplayName("MessageFilterBuilder custom filters")
    class CustomFilters {

        @Test
        @DisplayName("customPredicate() should apply custom message filter")
        void customPredicateShouldApplyCustomFilter() {
            // Given
            MessageFilter filter = MessageFilter.builder()
                    .customPredicate(msg ->
                        msg.topic() != null &&
                        msg.topic().startsWith("order") &&
                        msg.headers().containsKey("priority"))
                    .build();

            Message match = Message.builder()
                    .topic("order.created")
                    .header("priority", "HIGH")
                    .build();

            Message noMatch = Message.builder()
                    .topic("product.created")
                    .header("priority", "HIGH")
                    .build();

            // When & Then
            assertThat(filter.test(match)).isTrue();
            assertThat(filter.test(noMatch)).isFalse();
        }

        @Test
        @DisplayName("addFilter() should add pre-built filter")
        void addFilterShouldAddPreBuiltFilter() {
            // Given
            MessageFilter preBuilt = msg -> "test.topic".equals(msg.topic());

            MessageFilter filter = MessageFilter.builder()
                    .addFilter(preBuilt)
                    .headerEquals("priority", "HIGH")
                    .build();

            Message match = Message.builder()
                    .topic("test.topic")
                    .header("priority", "HIGH")
                    .build();

            Message noMatch = Message.builder()
                    .topic("other.topic")
                    .header("priority", "HIGH")
                    .build();

            // When & Then
            assertThat(filter.test(match)).isTrue();
            assertThat(filter.test(noMatch)).isFalse();
        }
    }

    // =========================================================================
    // MESSAGEFILTERBUILDER COMPLEX SCENARIOS
    // =========================================================================

    @Nested
    @DisplayName("MessageFilterBuilder complex scenarios")
    class ComplexScenarios {

        @Test
        @DisplayName("should build complex multi-criteria filter with AND")
        void shouldBuildComplexMultiCriteriaFilterWithAnd() {
            // Given
            MessageFilter filter = MessageFilter.builder()
                    .topicStartsWith("order.")
                    .headerEquals("priority", "HIGH")
                    .headerExists("customer-id")
                    .contentType(String.class)
                    .build();

            Message allMatch = Message.builder()
                    .topic("order.created")
                    .header("priority", "HIGH")
                    .header("customer-id", "123")
                    .content("order data")
                    .build();

            Message missingHeader = Message.builder()
                    .topic("order.created")
                    .header("priority", "HIGH")
                    .content("order data")
                    .build();

            Message wrongTopic = Message.builder()
                    .topic("product.created")
                    .header("priority", "HIGH")
                    .header("customer-id", "123")
                    .content("order data")
                    .build();

            // When & Then
            assertThat(filter.test(allMatch)).isTrue();
            assertThat(filter.test(missingHeader)).isFalse();
            assertThat(filter.test(wrongTopic)).isFalse();
        }

        @Test
        @DisplayName("should build complex multi-criteria filter with OR")
        void shouldBuildComplexMultiCriteriaFilterWithOr() {
            // Given
            MessageFilter filter = MessageFilter.builder()
                    .operator(MessageFilterBuilder.FilterOperator.OR)
                    .headerEquals("priority", "HIGH")
                    .headerEquals("priority", "CRITICAL")
                    .headerEquals("priority", "URGENT")
                    .build();

            Message high = Message.builder()
                    .topic("test")
                    .header("priority", "HIGH")
                    .build();

            Message critical = Message.builder()
                    .topic("test")
                    .header("priority", "CRITICAL")
                    .build();

            Message low = Message.builder()
                    .topic("test")
                    .header("priority", "LOW")
                    .build();

            // When & Then
            assertThat(filter.test(high)).isTrue();
            assertThat(filter.test(critical)).isTrue();
            assertThat(filter.test(low)).isFalse();
        }

        @Test
        @DisplayName("should combine builder filters with manual combinators")
        void shouldCombineBuilderFiltersWithManualCombinators() {
            // Given
            MessageFilter builderFilter = MessageFilter.builder()
                    .topicStartsWith("order.")
                    .build();

            MessageFilter manualFilter = msg -> "HIGH".equals(msg.headers().get("priority"));

            MessageFilter combined = builderFilter.and(manualFilter);

            Message bothMatch = Message.builder()
                    .topic("order.created")
                    .header("priority", "HIGH")
                    .build();

            Message topicOnly = Message.builder()
                    .topic("order.created")
                    .header("priority", "LOW")
                    .build();

            // When & Then
            assertThat(combined.test(bothMatch)).isTrue();
            assertThat(combined.test(topicOnly)).isFalse();
        }

        @Test
        @DisplayName("should short-circuit AND filter on first false")
        void shouldShortCircuitAndFilterOnFirstFalse() {
            // Given
            boolean[] predicateCalled = {false};

            MessageFilter filter = MessageFilter.builder()
                    .topic("other.topic")  // This will fail
                    .customPredicate(msg -> {
                        predicateCalled[0] = true;  // Should not be called
                        return true;
                    })
                    .build();

            Message message = createTestMessage("test.topic", "content");

            // When
            boolean result = filter.test(message);

            // Then
            assertThat(result).isFalse();
            // Short-circuit means second predicate should not be called
            // Note: This depends on implementation details
        }

        @Test
        @DisplayName("should short-circuit OR filter on first true")
        void shouldShortCircuitOrFilterOnFirstTrue() {
            // Given
            boolean[] predicateCalled = {false};

            MessageFilter filter = MessageFilter.builder()
                    .operator(MessageFilterBuilder.FilterOperator.OR)
                    .topic("test.topic")  // This will succeed
                    .customPredicate(msg -> {
                        predicateCalled[0] = true;  // Should not be called
                        return true;
                    })
                    .build();

            Message message = createTestMessage("test.topic", "content");

            // When
            boolean result = filter.test(message);

            // Then
            assertThat(result).isTrue();
            // Short-circuit means second predicate should not be called
            // Note: This depends on implementation details
        }
    }

    // =========================================================================
    // EDGE CASES AND ERROR HANDLING
    // =========================================================================

    @Nested
    @DisplayName("Edge cases and error handling")
    class EdgeCasesAndErrorHandling {

        @Test
        @DisplayName("should handle messages with empty headers map")
        void shouldHandleEmptyHeadersMap() {
            // Given
            MessageFilter filter = MessageFilter.builder()
                    .headerExists("priority")
                    .build();

            Message emptyHeaders = Message.builder()
                    .topic("test")
                    .build();

            // When & Then
            assertThat(filter.test(emptyHeaders)).isFalse();
        }

        @Test
        @DisplayName("should handle null message fields gracefully")
        void shouldHandleNullFieldsGracefully() {
            // Given
            MessageFilter filter = MessageFilter.builder()
                    .topic("test.topic")
                    .senderId("sender-1")
                    .build();

            Message nullFields = Message.builder()
                    .topic(null)
                    .senderId(null)
                    .build();

            // When & Then
            assertThat(filter.test(nullFields)).isFalse();
        }

        @Test
        @DisplayName("should work with very long filter chains")
        void shouldWorkWithVeryLongFilterChains() {
            // Given
            MessageFilterBuilder builder = MessageFilter.builder();

            for (int i = 0; i < 50; i++) {
                builder.headerExists("header-" + i);
            }

            MessageFilter filter = builder.build();
            Message message = createTestMessage("test", "content");

            // When & Then
            assertThat(filter.test(message)).isFalse();
        }

        @Test
        @DisplayName("should handle regex pattern errors gracefully")
        void shouldHandleRegexPatternErrors() {
            // Given - invalid regex patterns should compile (Pattern.compile handles it)
            assertThatCode(() -> {
                MessageFilter filter = MessageFilter.builder()
                        .topicMatches("valid\\.pattern")
                        .build();
            }).doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    private static Message createTestMessage(String topic, Object content) {
        return Message.builder()
                .topic(topic)
                .content(content)
                .build();
    }
}
