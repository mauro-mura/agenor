package dev.jentic.tools.agents;

import dev.jentic.core.Message;
import dev.jentic.core.console.ConsoleEventListener;
import dev.jentic.core.filter.MessageFilter;
import dev.jentic.runtime.filter.TopicFilter;
import dev.jentic.core.telemetry.JenticTelemetry;
import dev.jentic.runtime.directory.LocalAgentDirectory;
import dev.jentic.runtime.messaging.InMemoryMessageDispatcher;
import dev.jentic.runtime.scheduler.SimpleBehaviorScheduler;
import dev.jentic.tools.history.MessageHistoryService;
import org.junit.jupiter.api.*;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

@DisplayName("MessageSnifferAgent")
class MessageSnifferAgentTest {

    private MessageSnifferAgent sniffer;

    // =========================================================================
    // Lifecycle infrastructure (shared by Lifecycle nested class)
    // =========================================================================

    private InMemoryMessageDispatcher messageDispatcher;
    private SimpleBehaviorScheduler scheduler;

    @BeforeEach
    void setUpInfrastructure() {
        messageDispatcher = new InMemoryMessageDispatcher(new LocalAgentDirectory(), JenticTelemetry.noop());
        scheduler = new SimpleBehaviorScheduler();
        scheduler.start().join();
    }

    @AfterEach
    void tearDownInfrastructure() {
        if (sniffer != null && sniffer.isRunning()) {
            sniffer.stop().join();
        }
        scheduler.stop().join();
    }

    private void startSniffer(MessageSnifferAgent agent) {
        agent.setMessageDispatcher(messageDispatcher);
        agent.setBehaviorScheduler(scheduler);
        agent.start().join();
    }

    // =========================================================================

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("should create with defaults")
        void shouldCreateWithDefaults() {
            sniffer = new MessageSnifferAgent();

            assertThat(sniffer.getAgentId()).isEqualTo("message-sniffer");
            assertThat(sniffer.getAgentName()).isEqualTo("Message Sniffer");
            assertThat(sniffer.getHistory()).isNotNull();
            assertThat(sniffer.getFilter()).isNotNull();
        }

        @Test
        @DisplayName("should create with custom history size")
        void shouldCreateWithCustomHistorySize() {
            sniffer = new MessageSnifferAgent(500);

            assertThat(sniffer.getHistory().getMaxSize()).isEqualTo(500);
        }

        @Test
        @DisplayName("should create with custom filter")
        void shouldCreateWithCustomFilter() {
            MessageFilter filter = TopicFilter.startsWith("order.");
            sniffer = new MessageSnifferAgent(filter);

            assertThat(sniffer.getFilter()).isSameAs(filter);
        }

        @Test
        @DisplayName("should create with external history")
        void shouldCreateWithExternalHistory() {
            MessageHistoryService history = new MessageHistoryService(200);
            MessageFilter filter = MessageFilter.acceptAll();

            sniffer = new MessageSnifferAgent(filter, history);

            assertThat(sniffer.getHistory()).isSameAs(history);
        }

        @Test
        @DisplayName("should reject null filter")
        void shouldRejectNullFilter() {
            assertThatThrownBy(() -> new MessageSnifferAgent(null, 100))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("filter");
        }

        @Test
        @DisplayName("should reject null history")
        void shouldRejectNullHistory() {
            assertThatThrownBy(() -> new MessageSnifferAgent(MessageFilter.acceptAll(), null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("history");
        }
    }

    @Nested
    @DisplayName("Agent Properties")
    class AgentProperties {

        @Test
        @DisplayName("should have correct agent ID")
        void shouldHaveCorrectAgentId() {
            sniffer = new MessageSnifferAgent();
            assertThat(sniffer.getAgentId()).isEqualTo(MessageSnifferAgent.AGENT_ID);
        }

        @Test
        @DisplayName("should have correct agent name")
        void shouldHaveCorrectAgentName() {
            sniffer = new MessageSnifferAgent();
            assertThat(sniffer.getAgentName()).isEqualTo(MessageSnifferAgent.AGENT_NAME);
        }

        @Test
        @DisplayName("should use default history size constant")
        void shouldUseDefaultHistorySizeConstant() {
            sniffer = new MessageSnifferAgent();
            assertThat(sniffer.getHistory().getMaxSize())
                    .isEqualTo(MessageSnifferAgent.DEFAULT_HISTORY_SIZE);
        }
    }

    @Nested
    @DisplayName("Message Storage")
    class MessageStorage {

        @BeforeEach
        void setUp() {
            sniffer = new MessageSnifferAgent(100);
        }

        @Test
        @DisplayName("should store messages via history")
        void shouldStoreMessages() {
            Message msg = Message.builder()
                    .topic("test.topic")
                    .senderId("agent-1")
                    .content("test content")
                    .build();

            sniffer.getHistory().store(msg);

            assertThat(sniffer.getMessageCount()).isEqualTo(1);
            assertThat(sniffer.getRecent(1)).hasSize(1);
            assertThat(sniffer.getRecent(1).getFirst().topic()).isEqualTo("test.topic");
        }

        @Test
        @DisplayName("should respect history size limit")
        void shouldRespectHistoryLimit() {
            sniffer = new MessageSnifferAgent(10);

            for (int i = 0; i < 15; i++) {
                sniffer.getHistory().store(
                        Message.builder()
                                .topic("test." + i)
                                .content("msg-" + i)
                                .build()
                );
            }

            assertThat(sniffer.getMessageCount()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("Query API")
    class QueryAPI {

        @BeforeEach
        void setUp() {
            sniffer = new MessageSnifferAgent();

            sniffer.getHistory().store(createMessage("order.created", "agent-1"));
            sniffer.getHistory().store(createMessage("order.updated", "agent-1"));
            sniffer.getHistory().store(createMessage("payment.processed", "agent-2"));
            sniffer.getHistory().store(createMessage("order.shipped", "agent-1"));
        }

        @Test
        @DisplayName("should find by topic pattern")
        void shouldFindByTopicPattern() {
            var results = sniffer.findByTopic("order.*");

            assertThat(results).hasSize(3);
            assertThat(results).allMatch(m -> m.topic().startsWith("order."));
        }

        @Test
        @DisplayName("should find by sender")
        void shouldFindBySender() {
            var results = sniffer.findBySender("agent-1");

            assertThat(results).hasSize(3);
            assertThat(results).allMatch(m -> "agent-1".equals(m.senderId()));
        }

        @Test
        @DisplayName("should get recent messages")
        void shouldGetRecentMessages() {
            var results = sniffer.getRecent(2);

            assertThat(results).hasSize(2);
            assertThat(results.getFirst().topic()).isEqualTo("order.shipped");
        }

        @Test
        @DisplayName("should clear history")
        void shouldClearHistory() {
            assertThat(sniffer.getMessageCount()).isEqualTo(4);

            sniffer.clear();

            assertThat(sniffer.getMessageCount()).isZero();
        }

        private Message createMessage(String topic, String senderId) {
            return Message.builder()
                    .topic(topic)
                    .senderId(senderId)
                    .content("test")
                    .build();
        }
    }

    @Nested
    @DisplayName("Filter Behavior")
    class FilterBehavior {

        @Test
        @DisplayName("topic filter should match correctly")
        void topicFilterShouldMatchCorrectly() {
            MessageFilter filter = TopicFilter.startsWith("order.");
            sniffer = new MessageSnifferAgent(filter);

            Message orderMsg = Message.builder().topic("order.created").build();
            Message paymentMsg = Message.builder().topic("payment.done").build();

            assertThat(sniffer.getFilter().test(orderMsg)).isTrue();
            assertThat(sniffer.getFilter().test(paymentMsg)).isFalse();
        }

        @Test
        @DisplayName("acceptAll filter should match everything")
        void acceptAllShouldMatchEverything() {
            sniffer = new MessageSnifferAgent();

            Message msg1 = Message.builder().topic("any.topic").build();
            Message msg2 = Message.builder().topic("other.topic").build();

            assertThat(sniffer.getFilter().test(msg1)).isTrue();
            assertThat(sniffer.getFilter().test(msg2)).isTrue();
        }

        @Test
        @DisplayName("wildcard filter should match patterns")
        void wildcardFilterShouldMatchPatterns() {
            MessageFilter filter = TopicFilter.wildcard("sensor.*.data");
            sniffer = new MessageSnifferAgent(filter);

            Message match1 = Message.builder().topic("sensor.temp.data").build();
            Message match2 = Message.builder().topic("sensor.humidity.data").build();
            Message noMatch = Message.builder().topic("sensor.data").build();

            assertThat(sniffer.getFilter().test(match1)).isTrue();
            assertThat(sniffer.getFilter().test(match2)).isTrue();
            assertThat(sniffer.getFilter().test(noMatch)).isFalse();
        }
    }

    @Nested
    @DisplayName("Event Listener")
    class EventListenerTests {

        @BeforeEach
        void setUp() {
            sniffer = new MessageSnifferAgent();
        }

        @Test
        @DisplayName("should set and get event listener")
        void shouldSetAndGetEventListener() {
            assertThat(sniffer.getEventListener()).isNull();

            var listener = new TestEventListener();
            sniffer.setEventListener(listener);

            assertThat(sniffer.getEventListener()).isSameAs(listener);
        }

        @Test
        @DisplayName("should allow null listener")
        void shouldAllowNullListener() {
            var listener = new TestEventListener();
            sniffer.setEventListener(listener);
            sniffer.setEventListener(null);

            assertThat(sniffer.getEventListener()).isNull();
        }
    }

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("isCapturing should be false before start")
        void isCapturingShouldBeFalseBeforeStart() {
            sniffer = new MessageSnifferAgent();
            assertThat(sniffer.isCapturing()).isFalse();
        }

        @Test
        @DisplayName("isCapturing should be true after start")
        void isCapturingShouldBeTrueAfterStart() {
            sniffer = new MessageSnifferAgent();
            startSniffer(sniffer);

            assertThat(sniffer.isCapturing()).isTrue();
        }

        @Test
        @DisplayName("isCapturing should be false after stop")
        void isCapturingShouldBeFalseAfterStop() {
            sniffer = new MessageSnifferAgent();
            startSniffer(sniffer);
            sniffer.stop().join();

            assertThat(sniffer.isCapturing()).isFalse();
        }

        @Test
        @DisplayName("stop should be idempotent when already stopped")
        void stopShouldBeIdempotentWhenAlreadyStopped() {
            sniffer = new MessageSnifferAgent();
            startSniffer(sniffer);
            sniffer.stop().join();

            assertThatCode(() -> sniffer.stop().join()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should capture published messages after start")
        void shouldCapturePublishedMessages() throws Exception {
            sniffer = new MessageSnifferAgent();
            startSniffer(sniffer);

            messageDispatcher.publish(Message.builder()
                    .topic("test.event")
                    .senderId("publisher")
                    .content("payload")
                    .build()).join();

            Thread.sleep(200);

            assertThat(sniffer.getMessageCount()).isGreaterThanOrEqualTo(1);
            assertThat(sniffer.getRecent(1).getFirst().topic()).isEqualTo("test.event");
        }

        @Test
        @DisplayName("should apply filter during capture - only matching messages stored")
        void shouldApplyFilterDuringCapture() throws Exception {
            MessageFilter orderFilter = TopicFilter.startsWith("order.");
            sniffer = new MessageSnifferAgent(orderFilter);
            startSniffer(sniffer);

            messageDispatcher.publish(Message.builder()
                    .topic("order.created").senderId("s").content("x").build()).join();
            messageDispatcher.publish(Message.builder()
                    .topic("payment.done").senderId("s").content("x").build()).join();

            Thread.sleep(200);

            assertThat(sniffer.getFilter().test(Message.builder().topic("order.created").build())).isTrue();
            assertThat(sniffer.getFilter().test(Message.builder().topic("payment.done").build())).isFalse();
        }

        @Test
        @DisplayName("should stop capturing messages after stop")
        void shouldStopCapturingAfterStop() throws Exception {
            sniffer = new MessageSnifferAgent();
            startSniffer(sniffer);

            messageDispatcher.publish(Message.builder()
                    .topic("before.stop").senderId("s").content("c").build()).join();
            Thread.sleep(200);
            int countBeforeStop = sniffer.getMessageCount();

            sniffer.stop().join();

            messageDispatcher.publish(Message.builder()
                    .topic("after.stop").senderId("s").content("c").build()).join();
            Thread.sleep(200);

            assertThat(sniffer.getMessageCount()).isEqualTo(countBeforeStop);
        }

        @Test
        @DisplayName("should notify event listener when message captured")
        void shouldNotifyEventListenerOnCapture() throws Exception {
            sniffer = new MessageSnifferAgent();
            AtomicInteger notified = new AtomicInteger(0);
            sniffer.setEventListener(countingListener(notified));
            startSniffer(sniffer);

            messageDispatcher.publish(Message.builder()
                    .topic("notify.test").senderId("s").content("c").build()).join();
            Thread.sleep(200);

            assertThat(notified.get()).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("should not fail when event listener throws exception")
        void shouldNotFailWhenEventListenerThrows() {
            sniffer = new MessageSnifferAgent();
            sniffer.setEventListener(throwingListener());
            startSniffer(sniffer);

            assertThatCode(() -> {
                messageDispatcher.publish(Message.builder()
                        .topic("error.test").senderId("s").content("c").build()).join();
                Thread.sleep(200);
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should not notify listener when cleared to null")
        void shouldNotNotifyWhenListenerIsNull() throws Exception {
            sniffer = new MessageSnifferAgent();
            AtomicInteger notified = new AtomicInteger(0);
            sniffer.setEventListener(countingListener(notified));
            sniffer.setEventListener(null);
            startSniffer(sniffer);

            messageDispatcher.publish(Message.builder()
                    .topic("silent.test").senderId("s").content("c").build()).join();
            Thread.sleep(200);

            assertThat(notified.get()).isZero();
        }

        private ConsoleEventListener countingListener(AtomicInteger counter) {
            return new TestEventListener() {
                @Override
                public void onMessageSent(String id, String topic, String sender) {
                    counter.incrementAndGet();
                }
            };
        }

        private ConsoleEventListener throwingListener() {
            return new TestEventListener() {
                @Override
                public void onMessageSent(String id, String topic, String sender) {
                    throw new RuntimeException("listener error");
                }
            };
        }
    }

    // =========================================================================
    // Shared test helper
    // =========================================================================

    static class TestEventListener implements ConsoleEventListener {
        @Override public void onAgentStarted(String agentId, String agentName) {}
        @Override public void onAgentStopped(String agentId, String agentName) {}
        @Override public void onMessageSent(String messageId, String topic, String senderId) {}
        @Override public void onError(String source, String message) {}
        @Override public void onBehaviorExecuted(String agentId, String behaviorId,
                                                 long durationMs, boolean success, String error) {}
    }
}