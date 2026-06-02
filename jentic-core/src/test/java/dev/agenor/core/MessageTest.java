package dev.agenor.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.Map;

/**
 * Unit tests for Message record and builder
 */
class MessageTest {

    @Test
    void shouldCreateMessageWithBuilder() {
        // Given
        String content = "test content";
        String topic = "test.topic";
        String senderId = "sender-1";

        // When
        Message message = Message.builder()
            .topic(topic)
            .senderId(senderId)
            .content(content)
            .build();

        // Then
        assertThat(message.topic()).isEqualTo(topic);
        assertThat(message.senderId()).isEqualTo(senderId);
        assertThat(message.content()).isEqualTo(content);
        assertThat(message.id()).isNotNull();
        assertThat(message.timestamp()).isNotNull();
        assertThat(message.headers()).isEmpty();
    }

    @Test
    void shouldCreateMessageWithAllFields() {
        // Given
        String id = "msg-123";
        String topic = "test.topic";
        String senderId = "sender-1";
        String receiverId = "receiver-1";
        String correlationId = "corr-456";
        String content = "test content";
        Map<String, String> headers = Map.of("key1", "value1", "key2", "value2");
        Instant timestamp = Instant.now();

        // When
        Message message = Message.builder()
            .id(id)
            .topic(topic)
            .senderId(senderId)
            .receiverId(receiverId)
            .correlationId(correlationId)
            .content(content)
            .headers(headers)
            .timestamp(timestamp)
            .build();

        // Then
        assertThat(message.id()).isEqualTo(id);
        assertThat(message.topic()).isEqualTo(topic);
        assertThat(message.senderId()).isEqualTo(senderId);
        assertThat(message.receiverId()).isEqualTo(receiverId);
        assertThat(message.correlationId()).isEqualTo(correlationId);
        assertThat(message.content()).isEqualTo(content);
        assertThat(message.headers()).containsExactlyInAnyOrderEntriesOf(headers);
        assertThat(message.timestamp()).isEqualTo(timestamp);
    }

    @Test
    void shouldAddHeadersIndividually() {
        // When
        Message message = Message.builder()
            .topic("test")
            .content("test")
            .header("key1", "value1")
            .header("key2", "value2")
            .build();

        // Then
        assertThat(message.headers())
            .containsEntry("key1", "value1")
            .containsEntry("key2", "value2");
    }

    @Test
    void shouldGetContentWithType() {
        // Given
        TestData content = new TestData("test", 42);
        Message message = Message.builder()
            .topic("test")
            .content(content)
            .build();

        // When
        TestData retrieved = message.getContent(TestData.class);

        // Then
        assertThat(retrieved).isEqualTo(content);
    }

    @Test
    void shouldCreateReplyMessage() {
        // Given
        Message original = Message.builder()
            .id("original-123")
            .topic("request")
            .senderId("sender-1")
            .content("request")
            .build();

        String replyContent = "response";

        // When
        Message reply = original.reply(replyContent)
            .topic("response")
            .build();

        // Then
        assertThat(reply.correlationId()).isEqualTo(original.id());
        assertThat(reply.receiverId()).isEqualTo(original.senderId());
        assertThat(reply.content()).isEqualTo(replyContent);
        assertThat(reply.topic()).isEqualTo("response");
    }

    @Test
    void shouldHaveImmutableHeaders() {
        // Given
        Message message = Message.builder()
            .topic("test")
            .header("key1", "value1")
            .build();

        // When/Then
        assertThatThrownBy(() -> message.headers().put("key2", "value2"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldGenerateUniqueIds() {
        // When
        Message msg1 = Message.builder().topic("test").build();
        Message msg2 = Message.builder().topic("test").build();

        // Then
        assertThat(msg1.id()).isNotEqualTo(msg2.id());
    }

    // Test record for type testing
    record TestData(String name, int value) {}
}
