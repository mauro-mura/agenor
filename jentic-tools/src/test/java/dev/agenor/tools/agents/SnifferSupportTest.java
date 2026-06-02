package dev.agenor.tools.agents;

import dev.agenor.core.filter.MessageFilter;
import dev.agenor.runtime.JenticRuntime;
import dev.agenor.runtime.filter.TopicFilter;
import dev.agenor.tools.history.MessageHistoryService;
import dev.agenor.tools.history.MessageHistoryService.StoredMessage;
import dev.agenor.core.Message;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

@DisplayName("SnifferSupport")
class SnifferSupportTest {

    private JenticRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = JenticRuntime.builder().build();
    }

    // =========================================================================
    // register(runtime)
    // =========================================================================

    @Test
    @DisplayName("register(runtime) should register sniffer with default settings")
    void registerDefaultShouldRegisterSniffer() {
        MessageSnifferAgent sniffer = SnifferSupport.register(runtime);

        assertThat(sniffer).isNotNull();
        assertThat(sniffer.getFilter()).isNotNull();
        assertThat(sniffer.getHistory().getMaxSize())
            .isEqualTo(MessageSnifferAgent.DEFAULT_HISTORY_SIZE);
        assertThat(runtime.getAgent(MessageSnifferAgent.AGENT_ID)).isPresent();
    }

    @Test
    @DisplayName("register(runtime, filter, size) should register sniffer with custom config")
    void registerWithFilterAndSizeShouldApplyConfig() {
        MessageFilter filter = TopicFilter.startsWith("order.");
        MessageSnifferAgent sniffer = SnifferSupport.register(runtime, filter, 200);

        assertThat(sniffer.getFilter()).isSameAs(filter);
        assertThat(sniffer.getHistory().getMaxSize()).isEqualTo(200);
    }

    @Test
    @DisplayName("register(runtime, history) should register sniffer with shared history")
    void registerWithHistoryShouldUseSharedHistory() {
        MessageHistoryService shared = new MessageHistoryService(300);
        MessageSnifferAgent sniffer = SnifferSupport.register(runtime, shared);

        assertThat(sniffer.getHistory()).isSameAs(shared);
    }

    @Test
    @DisplayName("register(runtime, filter, history) should register sniffer with filter and shared history")
    void registerWithFilterAndHistoryShouldApplyBoth() {
        MessageFilter filter = TopicFilter.startsWith("event.");
        MessageHistoryService shared = new MessageHistoryService(400);
        MessageSnifferAgent sniffer = SnifferSupport.register(runtime, filter, shared);

        assertThat(sniffer.getFilter()).isSameAs(filter);
        assertThat(sniffer.getHistory()).isSameAs(shared);
    }

    // =========================================================================
    // get(runtime)
    // =========================================================================

    @Test
    @DisplayName("get should return empty when no sniffer registered")
    void getShouldReturnEmptyWhenNoSniffer() {
        Optional<MessageSnifferAgent> result = SnifferSupport.get(runtime);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("get should return sniffer after registration")
    void getShouldReturnSnifferAfterRegistration() {
        MessageSnifferAgent registered = SnifferSupport.register(runtime);
        Optional<MessageSnifferAgent> result = SnifferSupport.get(runtime);

        assertThat(result).isPresent();
        assertThat(result.get()).isSameAs(registered);
    }

    // =========================================================================
    // getRecent(runtime, count)
    // =========================================================================

    @Test
    @DisplayName("getRecent should return empty list when no sniffer")
    void getRecentShouldReturnEmptyWhenNoSniffer() {
        List<StoredMessage> result = SnifferSupport.getRecent(runtime, 10);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getRecent should return messages from sniffer history")
    void getRecentShouldReturnMessagesFromHistory() {
        MessageSnifferAgent sniffer = SnifferSupport.register(runtime);
        storeMessages(sniffer, "topic.a", "topic.b", "topic.c");

        List<StoredMessage> result = SnifferSupport.getRecent(runtime, 2);

        assertThat(result).hasSize(2);
        assertThat(result.getFirst().topic()).isEqualTo("topic.c");
    }

    // =========================================================================
    // findByTopic(runtime, pattern)
    // =========================================================================

    @Test
    @DisplayName("findByTopic should return empty list when no sniffer")
    void findByTopicShouldReturnEmptyWhenNoSniffer() {
        List<StoredMessage> result = SnifferSupport.findByTopic(runtime, "order.*");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findByTopic should filter messages by topic pattern")
    void findByTopicShouldFilterMessages() {
        MessageSnifferAgent sniffer = SnifferSupport.register(runtime);
        storeMessages(sniffer, "order.created", "payment.done", "order.shipped");

        List<StoredMessage> result = SnifferSupport.findByTopic(runtime, "order.*");

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(m -> m.topic().startsWith("order."));
    }

    // =========================================================================
    // findBySender(runtime, senderId)
    // =========================================================================

    @Test
    @DisplayName("findBySender should return empty list when no sniffer")
    void findBySenderShouldReturnEmptyWhenNoSniffer() {
        List<StoredMessage> result = SnifferSupport.findBySender(runtime, "agent-1");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findBySender should filter messages by sender")
    void findBySenderShouldFilterBySender() {
        MessageSnifferAgent sniffer = SnifferSupport.register(runtime);
        sniffer.getHistory().store(msg("topic.x", "agent-1"));
        sniffer.getHistory().store(msg("topic.y", "agent-2"));
        sniffer.getHistory().store(msg("topic.z", "agent-1"));

        List<StoredMessage> result = SnifferSupport.findBySender(runtime, "agent-1");

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(m -> "agent-1".equals(m.senderId()));
    }

    // =========================================================================
    // getMessageCount(runtime)
    // =========================================================================

    @Test
    @DisplayName("getMessageCount should return 0 when no sniffer")
    void getMessageCountShouldReturnZeroWhenNoSniffer() {
        assertThat(SnifferSupport.getMessageCount(runtime)).isZero();
    }

    @Test
    @DisplayName("getMessageCount should return correct count")
    void getMessageCountShouldReturnCorrectCount() {
        MessageSnifferAgent sniffer = SnifferSupport.register(runtime);
        storeMessages(sniffer, "a", "b", "c");

        assertThat(SnifferSupport.getMessageCount(runtime)).isEqualTo(3);
    }

    // =========================================================================
    // clear(runtime)
    // =========================================================================

    @Test
    @DisplayName("clear should do nothing when no sniffer")
    void clearShouldDoNothingWhenNoSniffer() {
        assertThatCode(() -> SnifferSupport.clear(runtime)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("clear should remove all messages from sniffer")
    void clearShouldRemoveAllMessages() {
        MessageSnifferAgent sniffer = SnifferSupport.register(runtime);
        storeMessages(sniffer, "a", "b");

        SnifferSupport.clear(runtime);

        assertThat(sniffer.getMessageCount()).isZero();
    }

    // =========================================================================
    // isActive(runtime)
    // =========================================================================

    @Test
    @DisplayName("isActive should return false when no sniffer")
    void isActiveShouldReturnFalseWhenNoSniffer() {
        assertThat(SnifferSupport.isActive(runtime)).isFalse();
    }

    @Test
    @DisplayName("isActive should return false when sniffer is not running")
    void isActiveShouldReturnFalseWhenSnifferNotRunning() {
        SnifferSupport.register(runtime);
        // Not started, so not capturing
        assertThat(SnifferSupport.isActive(runtime)).isFalse();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void storeMessages(MessageSnifferAgent sniffer, String... topics) {
        for (String topic : topics) {
            sniffer.getHistory().store(msg(topic, "test-sender"));
        }
    }

    private Message msg(String topic, String senderId) {
        return Message.builder()
            .topic(topic)
            .senderId(senderId)
            .content("test")
            .build();
    }
}
