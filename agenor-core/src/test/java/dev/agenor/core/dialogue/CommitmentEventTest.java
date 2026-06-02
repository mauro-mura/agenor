package dev.agenor.core.dialogue;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommitmentEventTest {

    @Test
    void shouldCreateEvent() {
        var now = Instant.now();
        var event = new CommitmentEvent(
            now,
            CommitmentState.PENDING,
            CommitmentState.ACTIVE,
            "agent-1",
            "Activated by request"
        );

        assertThat(event.timestamp()).isEqualTo(now);
        assertThat(event.fromState()).isEqualTo(CommitmentState.PENDING);
        assertThat(event.toState()).isEqualTo(CommitmentState.ACTIVE);
        assertThat(event.triggeredBy()).isEqualTo("agent-1");
        assertThat(event.description()).isEqualTo("Activated by request");
    }

    @Test
    void shouldCreateCreatedEvent() {
        var event = CommitmentEvent.created("agent-1", "Initial commitment");

        assertThat(event.fromState()).isEqualTo(CommitmentState.PENDING);
        assertThat(event.toState()).isEqualTo(CommitmentState.PENDING);
        assertThat(event.triggeredBy()).isEqualTo("agent-1");
        assertThat(event.timestamp()).isNotNull();
    }

    @Test
    void shouldCreateActivatedEvent() {
        var event = CommitmentEvent.activated("agent-2");

        assertThat(event.fromState()).isEqualTo(CommitmentState.PENDING);
        assertThat(event.toState()).isEqualTo(CommitmentState.ACTIVE);
        assertThat(event.triggeredBy()).isEqualTo("agent-2");
    }

    @Test
    void shouldRejectNullTimestamp() {
        assertThatThrownBy(() -> new CommitmentEvent(
            null, CommitmentState.PENDING, CommitmentState.ACTIVE, "agent", "desc"
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullFromState() {
        assertThatThrownBy(() -> new CommitmentEvent(
            Instant.now(), null, CommitmentState.ACTIVE, "agent", "desc"
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullToState() {
        assertThatThrownBy(() -> new CommitmentEvent(
            Instant.now(), CommitmentState.PENDING, null, "agent", "desc"
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldAllowNullTriggeredBy() {
        var event = new CommitmentEvent(
            Instant.now(),
            CommitmentState.ACTIVE,
            CommitmentState.FULFILLED,
            null,
            "System fulfilled"
        );

        assertThat(event.triggeredBy()).isNull();
    }
}
