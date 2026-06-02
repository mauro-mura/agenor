package dev.agenor.runtime.behavior;

import dev.agenor.core.BehaviorType;
import dev.agenor.core.Message;
import dev.agenor.core.MessageHandler;
import dev.agenor.runtime.agent.BaseAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive tests for EventDrivenBehavior.
 * Coverage: message handling, topic subscription, factory methods, lifecycle.
 */
class EventDrivenBehaviorTest {

    private TestAgent agent;

    @BeforeEach
    void setUp() {
        agent = new TestAgent();
    }

    // =========================================================================
    // CONSTRUCTOR TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create event-driven behavior with topic")
    void testConstructorWithTopic() {
        // Given
        String topic = "test.topic";

        // When
        EventDrivenBehavior behavior = new EventDrivenBehavior(topic) {
            @Override
            protected void handleMessage(Message message) {
                // empty
            }
        };

        // Then
        assertThat(behavior.getType()).isEqualTo(BehaviorType.EVENT_DRIVEN);
        assertThat(behavior.getTopic()).isEqualTo(topic);
        assertThat(behavior.getInterval()).isNull();
        assertThat(behavior.isActive()).isTrue();
    }

    @Test
    @DisplayName("Should create event-driven behavior with custom ID and topic")
    void testConstructorWithIdAndTopic() {
        // Given
        String behaviorId = "custom-event-handler";
        String topic = "events.important";

        // When
        EventDrivenBehavior behavior = new EventDrivenBehavior(behaviorId, topic) {
            @Override
            protected void handleMessage(Message message) {
                // empty
            }
        };

        // Then
        assertThat(behavior.getBehaviorId()).isEqualTo(behaviorId);
        assertThat(behavior.getTopic()).isEqualTo(topic);
        assertThat(behavior.getType()).isEqualTo(BehaviorType.EVENT_DRIVEN);
    }

    // =========================================================================
    // FACTORY METHOD TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create from topic and MessageHandler")
    void testFromFactory() {
        // Given
        String topic = "test.events";
        List<Message> receivedMessages = new ArrayList<>();

        MessageHandler handler = message -> {
            receivedMessages.add(message);
            return CompletableFuture.completedFuture(null);
        };

        // When
        EventDrivenBehavior behavior = EventDrivenBehavior.from(topic, handler);

        // Then
        assertThat(behavior).isNotNull();
        assertThat(behavior.getTopic()).isEqualTo(topic);
        assertThat(behavior.getType()).isEqualTo(BehaviorType.EVENT_DRIVEN);
    }

    // =========================================================================
    // MESSAGE HANDLING TESTS
    // =========================================================================

    @Test
    @DisplayName("Should handle incoming messages")
    void testMessageHandling() throws InterruptedException {
        // Given
        CountDownLatch latch = new CountDownLatch(1);
        List<Message> receivedMessages = new ArrayList<>();

        EventDrivenBehavior behavior = new EventDrivenBehavior("test.topic") {
            @Override
            protected void handleMessage(Message message) {
                receivedMessages.add(message);
                latch.countDown();
            }
        };
        behavior.setAgent(agent);

        Message testMessage = Message.builder()
            .topic("test.topic")
            .content("test content")
            .build();

        // When
        behavior.handle(testMessage).join();

        // Then
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(receivedMessages).hasSize(1);
        assertThat(receivedMessages.get(0).content()).isEqualTo("test content");
    }

    @Test
    @DisplayName("Should handle multiple messages sequentially")
    void testMultipleMessages() throws InterruptedException {
        // Given
        CountDownLatch latch = new CountDownLatch(3);
        List<String> contents = new ArrayList<>();

        EventDrivenBehavior behavior = new EventDrivenBehavior("test.topic") {
            @Override
            protected void handleMessage(Message message) {
                contents.add((String) message.content());
                latch.countDown();
            }
        };
        behavior.setAgent(agent);

        // When
        behavior.handle(createMessage("msg1")).join();
        behavior.handle(createMessage("msg2")).join();
        behavior.handle(createMessage("msg3")).join();

        // Then
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(contents).containsExactly("msg1", "msg2", "msg3");
    }

    @Test
    @DisplayName("Should extract message content correctly")
    void testMessageContentExtraction() {
        // Given
        List<Object> receivedContents = new ArrayList<>();

        EventDrivenBehavior behavior = new EventDrivenBehavior("test.topic") {
            @Override
            protected void handleMessage(Message message) {
                receivedContents.add(message.content());
            }
        };
        behavior.setAgent(agent);

        // When - send different content types
        behavior.handle(createMessage("text")).join();
        behavior.handle(createMessage(42)).join();
        behavior.handle(createMessage(List.of("a", "b"))).join();

        // Then
        assertThat(receivedContents).hasSize(3);
        assertThat(receivedContents.get(0)).isEqualTo("text");
        assertThat(receivedContents.get(1)).isEqualTo(42);
        assertThat(receivedContents.get(2)).isInstanceOf(List.class);
    }

    // =========================================================================
    // TOPIC TESTS
    // =========================================================================

    @Test
    @DisplayName("Should maintain topic association")
    void testTopicAssociation() {
        // Given
        String topic = "specific.events";

        // When
        EventDrivenBehavior behavior = new EventDrivenBehavior(topic) {
            @Override
            protected void handleMessage(Message message) {
                // empty
            }
        };

        // Then
        assertThat(behavior.getTopic()).isEqualTo(topic);
    }

    @Test
    @DisplayName("Should support hierarchical topics")
    void testHierarchicalTopics() {
        // Given
        EventDrivenBehavior behavior = new EventDrivenBehavior("system.events.critical") {
            @Override
            protected void handleMessage(Message message) {
                // empty
            }
        };

        // Then
        assertThat(behavior.getTopic()).isEqualTo("system.events.critical");
    }

    // =========================================================================
    // LIFECYCLE TESTS
    // =========================================================================

    @Test
    @DisplayName("Should not handle messages when stopped")
    void testNoHandlingWhenStopped() {
        // Given
        AtomicInteger handleCount = new AtomicInteger(0);

        EventDrivenBehavior behavior = new EventDrivenBehavior("test.topic") {
            @Override
            protected void handleMessage(Message message) {
                handleCount.incrementAndGet();
            }
        };
        behavior.setAgent(agent);

        // When
        behavior.stop();
        behavior.handle(createMessage("test")).join();

        // Then
        assertThat(handleCount.get()).isZero();
        assertThat(behavior.isActive()).isFalse();
    }

    @Test
    @DisplayName("Should support reactivation")
    void testReactivation() {
        // Given
        AtomicInteger handleCount = new AtomicInteger(0);

        EventDrivenBehavior behavior = new EventDrivenBehavior("test.topic") {
            @Override
            protected void handleMessage(Message message) {
                handleCount.incrementAndGet();
            }
        };
        behavior.setAgent(agent);

        // When - stop, reactivate, handle
        behavior.stop();
        boolean activated = behavior.activate();

        assertThat(activated).isTrue();
        assertThat(behavior.isActive()).isTrue();

        behavior.handle(createMessage("test")).join();

        // Then
        assertThat(handleCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should not execute on schedule")
    void testNoScheduledExecution() {
        // Given
        AtomicInteger actionCount = new AtomicInteger(0);

        EventDrivenBehavior behavior = new EventDrivenBehavior("test.topic") {
            @Override
            protected void action() {
                actionCount.incrementAndGet();
                super.action();
            }

            @Override
            protected void handleMessage(Message message) {
                // empty
            }
        };
        behavior.setAgent(agent);

        // When - execute() should do nothing for event-driven
        behavior.execute().join();

        // Then - action() is called but does nothing
        assertThat(actionCount.get()).isEqualTo(1);
    }

    // =========================================================================
    // ERROR HANDLING TESTS
    // =========================================================================

    @Test
    @DisplayName("Should handle exceptions in message handler")
    void testExceptionInHandler() {
        // Given
        AtomicInteger attemptCount = new AtomicInteger(0);

        EventDrivenBehavior behavior = new EventDrivenBehavior("test.topic") {
            @Override
            protected void handleMessage(Message message) {
                attemptCount.incrementAndGet();
                throw new RuntimeException("Handler failed");
            }
        };
        behavior.setAgent(agent);

        // When/Then - should not propagate exception
        assertThatCode(() -> behavior.handle(createMessage("test")).join())
            .doesNotThrowAnyException();

        // Handler was attempted
        assertThat(attemptCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should continue handling messages after exception")
    void testContinueAfterException() {
        // Given
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger attemptCount = new AtomicInteger(0);

        EventDrivenBehavior behavior = new EventDrivenBehavior("test.topic") {
            @Override
            protected void handleMessage(Message message) {
                int count = attemptCount.incrementAndGet();
                if (count == 2) {
                    throw new RuntimeException("Second message fails");
                }
                successCount.incrementAndGet();
            }
        };
        behavior.setAgent(agent);

        // When - handle 3 messages (2nd fails)
        behavior.handle(createMessage("msg1")).join();
        behavior.handle(createMessage("msg2")).join(); // exception
        behavior.handle(createMessage("msg3")).join();

        // Then - should handle 1st and 3rd successfully
        assertThat(attemptCount.get()).isEqualTo(3);
        assertThat(successCount.get()).isEqualTo(2);
    }

    // =========================================================================
    // CONCURRENCY TESTS
    // =========================================================================

    @Test
    @DisplayName("Should handle concurrent messages safely")
    void testConcurrentMessageHandling() throws InterruptedException {
        // Given
        CountDownLatch latch = new CountDownLatch(5);
        AtomicInteger handleCount = new AtomicInteger(0);

        EventDrivenBehavior behavior = new EventDrivenBehavior("test.topic") {
            @Override
            protected void handleMessage(Message message) {
                handleCount.incrementAndGet();
                latch.countDown();
            }
        };
        behavior.setAgent(agent);

        // When - send messages concurrently
        CompletableFuture.allOf(
            behavior.handle(createMessage("msg1")),
            behavior.handle(createMessage("msg2")),
            behavior.handle(createMessage("msg3")),
            behavior.handle(createMessage("msg4")),
            behavior.handle(createMessage("msg5"))
        ).join();

        // Then
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(handleCount.get()).isEqualTo(5);
    }

    // =========================================================================
    // INTEGRATION TESTS
    // =========================================================================

    @Test
    @DisplayName("Should integrate with MessageHandler interface")
    void testMessageHandlerIntegration() {
        // Given
        List<Message> received = new ArrayList<>();

        MessageHandler handler = message -> {
            received.add(message);
            return CompletableFuture.completedFuture(null);
        };

        EventDrivenBehavior behavior = EventDrivenBehavior.from("test.topic", handler);
        behavior.setAgent(agent);

        // When
        Message msg = createMessage("content");
        behavior.handle(msg).join();

        // Then
        assertThat(received).hasSize(1);
        assertThat(received.get(0)).isEqualTo(msg);
    }

    @Test
    @DisplayName("Should integrate with agent")
    void testAgentIntegration() {
        // Given
        EventDrivenBehavior behavior = new EventDrivenBehavior("test.topic") {
            @Override
            protected void handleMessage(Message message) {
                // empty
            }
        };

        // When
        behavior.setAgent(agent);

        // Then
        assertThat(behavior.getAgent()).isEqualTo(agent);
        assertThat(behavior.getAgent().getAgentId()).isEqualTo(agent.getAgentId());
    }

    @Test
    @DisplayName("Should support multiple event behaviors")
    void testMultipleEventBehaviors() {
        // Given
        List<String> topic1Messages = new ArrayList<>();
        List<String> topic2Messages = new ArrayList<>();

        EventDrivenBehavior behavior1 = new EventDrivenBehavior("topic1") {
            @Override
            protected void handleMessage(Message message) {
                topic1Messages.add((String) message.content());
            }
        };

        EventDrivenBehavior behavior2 = new EventDrivenBehavior("topic2") {
            @Override
            protected void handleMessage(Message message) {
                topic2Messages.add((String) message.content());
            }
        };

        behavior1.setAgent(agent);
        behavior2.setAgent(agent);

        // When
        behavior1.handle(createMessage("topic1", "msg1")).join();
        behavior2.handle(createMessage("topic2", "msg2")).join();
        behavior1.handle(createMessage("topic1", "msg3")).join();

        // Then
        assertThat(topic1Messages).containsExactly("msg1", "msg3");
        assertThat(topic2Messages).containsExactly("msg2");
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    private Message createMessage(Object content) {
        return createMessage("test.topic", content);
    }

    private Message createMessage(String topic, Object content) {
        return Message.builder()
            .topic(topic)
            .content(content)
            .build();
    }

    // =========================================================================
    // HELPER CLASSES
    // =========================================================================

    static class TestAgent extends BaseAgent {
        TestAgent() {
            super("test-agent", "Test Agent");
        }
    }
}
