package dev.agenor.tools.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import dev.agenor.core.Message;


@DisplayName("MessageHistoryService")
class MessageHistoryServiceTest {

    private MessageHistoryService service;

    @BeforeEach
    void setUp() {
        service = new MessageHistoryService(100);
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("should create with default max size")
        void shouldCreateWithDefaultMaxSize() {
            var defaultService = new MessageHistoryService();
            assertEquals(MessageHistoryService.DEFAULT_MAX_SIZE, defaultService.getMaxSize());
            assertTrue(defaultService.isEmpty());
        }

        @Test
        @DisplayName("should create with custom max size")
        void shouldCreateWithCustomMaxSize() {
            var customService = new MessageHistoryService(500);
            assertEquals(500, customService.getMaxSize());
        }

        @Test
        @DisplayName("should reject maxSize below minimum")
        void shouldRejectMaxSizeBelowMinimum() {
            assertThrows(IllegalArgumentException.class,
                    () -> new MessageHistoryService(5));
        }

        @Test
        @DisplayName("should reject maxSize above maximum")
        void shouldRejectMaxSizeAboveMaximum() {
            assertThrows(IllegalArgumentException.class,
                    () -> new MessageHistoryService(200_000));
        }
    }

    @Nested
    @DisplayName("Store")
    class Store {

        @Test
        @DisplayName("should store single message")
        void shouldStoreSingleMessage() {
            var message = createMessage("test-topic", "content-1");

            service.store(message);

            assertEquals(1, service.size());
            assertFalse(service.isEmpty());
        }

        @Test
        @DisplayName("should reject null message")
        void shouldRejectNullMessage() {
            assertThrows(NullPointerException.class,
                    () -> service.store(null));
        }

        @Test
        @DisplayName("should preserve message fields")
        void shouldPreserveMessageFields() {
            var message = Message.builder()
                    .topic("orders.created")
                    .senderId("agent-1")
                    .receiverId("agent-2")
                    .correlationId("corr-123")
                    .content(Map.of("orderId", "ORD-001"))
                    .header("priority", "high")
                    .build();

            service.store(message);

            var stored = service.getRecent(1).get(0);
            assertEquals(message.id(), stored.id());
            assertEquals("orders.created", stored.topic());
            assertEquals("agent-1", stored.senderId());
            assertEquals("agent-2", stored.receiverId());
            assertEquals("corr-123", stored.correlationId());
            assertEquals(Map.of("orderId", "ORD-001"), stored.payload());
            assertEquals("high", stored.headers().get("priority"));
            assertNotNull(stored.timestamp());
            assertNotNull(stored.storedAt());
        }

        @Test
        @DisplayName("should evict oldest when full")
        void shouldEvictOldestWhenFull() {
            var smallService = new MessageHistoryService(10);

            for (int i = 0; i < 15; i++) {
                smallService.store(createMessage("topic", "content-" + i));
            }

            assertEquals(10, smallService.size());

            var recent = smallService.getRecent(10);
            assertEquals("content-14", recent.get(0).payload());
            assertEquals("content-5", recent.get(9).payload());
        }
    }

    @Nested
    @DisplayName("GetRecent")
    class GetRecent {

        @Test
        @DisplayName("should return empty list when empty")
        void shouldReturnEmptyWhenEmpty() {
            var result = service.getRecent(10);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return newest first")
        void shouldReturnNewestFirst() {
            service.store(createMessage("topic", "first"));
            service.store(createMessage("topic", "second"));
            service.store(createMessage("topic", "third"));

            var result = service.getRecent(3);

            assertEquals(3, result.size());
            assertEquals("third", result.get(0).payload());
            assertEquals("second", result.get(1).payload());
            assertEquals("first", result.get(2).payload());
        }

        @Test
        @DisplayName("should respect limit")
        void shouldRespectLimit() {
            for (int i = 0; i < 10; i++) {
                service.store(createMessage("topic", "content-" + i));
            }

            var result = service.getRecent(3);
            assertEquals(3, result.size());
        }
    }

    @Nested
    @DisplayName("GetByTopic")
    class GetByTopic {

        @Test
        @DisplayName("should filter by exact topic")
        void shouldFilterByExactTopic() {
            service.store(createMessage("orders.created", "order-1"));
            service.store(createMessage("orders.updated", "order-2"));
            service.store(createMessage("orders.created", "order-3"));
            service.store(createMessage("payments.completed", "payment-1"));

            var result = service.getByTopic("orders.created");

            assertEquals(2, result.size());
            assertEquals("order-3", result.get(0).payload());
            assertEquals("order-1", result.get(1).payload());
        }
    }

    @Nested
    @DisplayName("GetByTopicPattern")
    class GetByTopicPattern {

        @Test
        @DisplayName("should match wildcard pattern")
        void shouldMatchWildcardPattern() {
            service.store(createMessage("orders.created", "1"));
            service.store(createMessage("orders.updated", "2"));
            service.store(createMessage("orders.deleted", "3"));
            service.store(createMessage("payments.created", "4"));

            var result = service.findByTopicPattern("orders.*");

            assertEquals(3, result.size());
        }

        @Test
        @DisplayName("should match single character wildcard")
        void shouldMatchSingleCharacterWildcard() {
            service.store(createMessage("events.a.created", "1"));
            service.store(createMessage("events.b.created", "2"));
            service.store(createMessage("events.ab.created", "3"));

            var result = service.findByTopicPattern("events.?.created");

            assertEquals(2, result.size());
        }
    }

    @Nested
    @DisplayName("Thread Safety")
    class ThreadSafety {

        @Test
        @DisplayName("should handle concurrent stores")
        void shouldHandleConcurrentStores() throws InterruptedException {
            int threadCount = 10;
            int messagesPerThread = 100;
            var latch = new CountDownLatch(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < messagesPerThread; i++) {
                            service.store(createMessage(
                                    "thread-" + threadId,
                                    "msg-" + threadId + "-" + i));
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS));
            executor.shutdown();

            // Max size is 100, so we should have exactly 100 messages
            assertEquals(100, service.size());
        }
    }

    // Helper methods

    private Message createMessage(String topic, Object content) {
        return Message.builder()
                .topic(topic)
                .content(content)
                .build();
    }
}
