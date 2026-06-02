package dev.agenor.runtime.dialogue;

import dev.agenor.core.dialogue.CommitmentState;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultCommitmentTest {

    @Test
    void shouldCreateCommitment() {
        var deadline = Instant.now().plusSeconds(60);
        var commitment = new DefaultCommitment(
            "commit-1",
            "performer-agent",
            "requester-agent",
            "task content",
            "conv-1",
            deadline
        );

        assertThat(commitment.getId()).isEqualTo("commit-1");
        assertThat(commitment.getPerformer()).isEqualTo("performer-agent");
        assertThat(commitment.getRequester()).isEqualTo("requester-agent");
        assertThat(commitment.getContent()).isEqualTo("task content");
        assertThat(commitment.getConversationId()).isEqualTo("conv-1");
        assertThat(commitment.getState()).isEqualTo(CommitmentState.PENDING);
        assertThat(commitment.getDeadline()).isPresent().contains(deadline);
        assertThat(commitment.isActive()).isTrue();
    }

    @Test
    void shouldActivate() {
        var commitment = createCommitment();

        commitment.activate("performer-agent");

        assertThat(commitment.getState()).isEqualTo(CommitmentState.ACTIVE);
        assertThat(commitment.getHistory()).hasSize(2);
    }

    @Test
    void shouldFulfill() {
        var commitment = createCommitment();
        commitment.activate("performer-agent");

        commitment.fulfill("performer-agent");

        assertThat(commitment.getState()).isEqualTo(CommitmentState.FULFILLED);
        assertThat(commitment.isActive()).isFalse();
        assertThat(commitment.getState().isSuccessful()).isTrue();
    }

    @Test
    void shouldViolate() {
        var commitment = createCommitment();
        commitment.activate("performer-agent");

        commitment.violate("Deadline exceeded");

        assertThat(commitment.getState()).isEqualTo(CommitmentState.VIOLATED);
        assertThat(commitment.isActive()).isFalse();
    }

    @Test
    void shouldCancel() {
        var commitment = createCommitment();
        commitment.activate("performer-agent");

        commitment.cancel("performer-agent", "Cannot complete");

        assertThat(commitment.getState()).isEqualTo(CommitmentState.CANCELLED);
    }

    @Test
    void shouldRelease() {
        var commitment = createCommitment();
        commitment.activate("performer-agent");

        commitment.release("requester-agent");

        assertThat(commitment.getState()).isEqualTo(CommitmentState.RELEASED);
        assertThat(commitment.getState().isSuccessful()).isTrue();
    }

    @Test
    void shouldNotTransitionFromTerminalState() {
        var commitment = createCommitment();
        commitment.activate("performer-agent");
        commitment.fulfill("performer-agent");

        var result = commitment.transitionTo(CommitmentState.VIOLATED, "system", "test");

        assertThat(result).isFalse();
        assertThat(commitment.getState()).isEqualTo(CommitmentState.FULFILLED);
    }

    @Test
    void shouldTrackHistory() {
        var commitment = createCommitment();
        commitment.activate("performer-agent");
        commitment.fulfill("performer-agent");

        var history = commitment.getHistory();

        assertThat(history).hasSize(3);
        assertThat(history.get(0).toState()).isEqualTo(CommitmentState.PENDING);
        assertThat(history.get(1).toState()).isEqualTo(CommitmentState.ACTIVE);
        assertThat(history.get(2).toState()).isEqualTo(CommitmentState.FULFILLED);
    }

    @Test
    void shouldDetectOverdue() {
        var pastDeadline = Instant.now().minusSeconds(60);
        var commitment = new DefaultCommitment(
            "commit-1", "performer", "requester", "task", "conv-1", pastDeadline
        );

        assertThat(commitment.isOverdue()).isTrue();
    }

    @Test
    void shouldNotBeOverdueWithoutDeadline() {
        var commitment = new DefaultCommitment(
            "commit-1", "performer", "requester", "task", "conv-1", null
        );

        assertThat(commitment.isOverdue()).isFalse();
    }

    private DefaultCommitment createCommitment() {
        return new DefaultCommitment(
            "commit-1",
            "performer-agent",
            "requester-agent",
            "task content",
            "conv-1",
            Instant.now().plusSeconds(60)
        );
    }
}
