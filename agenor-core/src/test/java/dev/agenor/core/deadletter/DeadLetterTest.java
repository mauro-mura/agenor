package dev.agenor.core.deadletter;

import dev.agenor.core.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DeadLetter")
class DeadLetterTest {

    private static Message message() {
        return Message.builder().topic("orders").content("payload").build();
    }

    @Test
    @DisplayName("of() defaults to one attempt, which is what a transport without redelivery produces")
    void of_defaultsToOneAttempt() {
        var dl = DeadLetter.of(message(), "agent-1", new IllegalStateException("boom"));

        assertThat(dl.attempts()).isEqualTo(1);
        assertThat(dl.recipientId()).isEqualTo("agent-1");
        assertThat(dl.deadLetteredAt()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    @DisplayName("the reason names the failure type and its detail")
    void of_reasonCarriesTypeAndDetail() {
        var dl = DeadLetter.of(message(), "agent-1", new IllegalStateException("boom"));

        assertThat(dl.reason()).isEqualTo("IllegalStateException: boom");
    }

    @Test
    @DisplayName("a failure with no detail still names its type rather than reading 'null'")
    void of_reasonWithoutDetail() {
        var dl = DeadLetter.of(message(), "agent-1", new IllegalStateException());

        assertThat(dl.reason()).isEqualTo("IllegalStateException");
    }

    @Test
    @DisplayName("a topic delivery has no single recipient, and null says so")
    void of_topicDeliveryHasNoRecipient() {
        var dl = DeadLetter.of(message(), null, new RuntimeException("x"));

        assertThat(dl.recipientId()).isNull();
    }

    @Test
    @DisplayName("attempts below one is rejected: a dead letter means a handler ran")
    void attemptsMustBeAtLeastOne() {
        assertThatThrownBy(() -> new DeadLetter(message(), "r", "a", 0, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1");
    }

    @Test
    @DisplayName("the message and the reason are required")
    void requiredFields() {
        assertThatThrownBy(() -> new DeadLetter(null, "r", "a", 1, Instant.now()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DeadLetter(message(), null, "a", 1, Instant.now()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("noop() keeps nothing and reads back empty, but still rejects null")
    void noopKeepsNothing() {
        var q = DeadLetterQueue.noop();
        q.record(DeadLetter.of(message(), "a", new RuntimeException("x")));

        assertThat(q.recent(10)).isEmpty();
        assertThatThrownBy(() -> q.record(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> q.recent(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
