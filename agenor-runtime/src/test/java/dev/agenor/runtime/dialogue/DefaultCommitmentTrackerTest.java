package dev.agenor.runtime.dialogue;

import dev.agenor.core.dialogue.CommitmentState;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultCommitmentTrackerTest {

    private DefaultCommitmentTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new DefaultCommitmentTracker(Duration.ofMinutes(5));
    }

    @Test
    void shouldCreateCommitmentFromRequest() {
        var message = DialogueMessage.builder()
            .id("msg-1")
            .conversationId("conv-1")
            .senderId("requester")
            .receiverId("performer")
            .performative(Performative.REQUEST)
            .content("do task")
            .build();

        var commitment = tracker.createFromMessage(message);

        assertThat(commitment).isNotNull();
        assertThat(commitment.getRequester()).isEqualTo("requester");
        assertThat(commitment.getPerformer()).isEqualTo("performer");
        assertThat(commitment.getState()).isEqualTo(CommitmentState.PENDING);
    }

    @Test
    void shouldCreateActiveCommitmentFromAgree() {
        var message = DialogueMessage.builder()
            .id("msg-1")
            .conversationId("conv-1")
            .senderId("performer")
            .receiverId("requester")
            .performative(Performative.AGREE)
            .content("ok")
            .build();

        var commitment = tracker.createFromMessage(message);

        assertThat(commitment).isNotNull();
        assertThat(commitment.getPerformer()).isEqualTo("performer");
        assertThat(commitment.getRequester()).isEqualTo("requester");
        assertThat(commitment.getState()).isEqualTo(CommitmentState.ACTIVE);
    }

    @Test
    void shouldNotCreateCommitmentFromInform() {
        var message = DialogueMessage.builder()
            .senderId("agent")
            .performative(Performative.INFORM)
            .content("data")
            .build();

        var commitment = tracker.createFromMessage(message);

        assertThat(commitment).isNull();
    }

    @Test
    void shouldUpdateCommitmentOnAgree() {
        var request = DialogueMessage.builder()
            .id("msg-1")
            .conversationId("conv-1")
            .senderId("requester")
            .receiverId("performer")
            .performative(Performative.REQUEST)
            .build();

        var commitment = tracker.createFromMessage(request);
        var commitmentId = commitment.getId();

        var agree = DialogueMessage.builder()
            .senderId("performer")
            .performative(Performative.AGREE)
            .inReplyTo("msg-1")
            .build();

        tracker.updateFromResponse(commitmentId, agree);

        assertThat(tracker.get(commitmentId).get().getState())
            .isEqualTo(CommitmentState.ACTIVE);
    }

    @Test
    void shouldFulfillCommitmentOnInform() {
        var commitment = createActiveCommitment();

        var inform = DialogueMessage.builder()
            .senderId("performer")
            .performative(Performative.INFORM)
            .content("result")
            .build();

        tracker.updateFromResponse(commitment.getId(), inform);

        assertThat(tracker.get(commitment.getId()).get().getState())
            .isEqualTo(CommitmentState.FULFILLED);
    }

    @Test
    void shouldViolateCommitmentOnFailure() {
        var commitment = createActiveCommitment();

        var failure = DialogueMessage.builder()
            .senderId("performer")
            .performative(Performative.FAILURE)
            .content("error")
            .build();

        tracker.updateFromResponse(commitment.getId(), failure);

        assertThat(tracker.get(commitment.getId()).get().getState())
            .isEqualTo(CommitmentState.VIOLATED);
    }

    @Test
    void shouldGetActiveAsPerformer() {
        var msg1 = DialogueMessage.builder()
            .senderId("requester").receiverId("performer-1")
            .performative(Performative.AGREE).build();
        var msg2 = DialogueMessage.builder()
            .senderId("requester").receiverId("performer-2")
            .performative(Performative.AGREE).build();

        tracker.createFromMessage(msg1);
        tracker.createFromMessage(msg2);

        var active = tracker.getActiveAsPerformer("requester");
        assertThat(active).hasSize(2);
    }

    @Test
    void shouldGetActiveAsRequester() {
        var msg = DialogueMessage.builder()
            .senderId("performer").receiverId("requester")
            .performative(Performative.AGREE).build();

        tracker.createFromMessage(msg);

        var active = tracker.getActiveAsRequester("requester");
        assertThat(active).hasSize(1);
    }

    @Test
    void shouldCheckViolations() {
        var shortDeadline = new DefaultCommitmentTracker(Duration.ofMillis(1));

        var msg = DialogueMessage.builder()
            .senderId("performer").receiverId("requester")
            .performative(Performative.AGREE).build();

        shortDeadline.createFromMessage(msg);

        try { Thread.sleep(10); } catch (InterruptedException e) { }

        var violations = shortDeadline.checkViolations();
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).getState()).isEqualTo(CommitmentState.VIOLATED);
    }

    @Test
    void shouldCancelCommitment() {
        var commitment = createActiveCommitment();

        tracker.cancel(commitment.getId(), "No longer needed");

        assertThat(tracker.get(commitment.getId()).get().getState())
            .isEqualTo(CommitmentState.CANCELLED);
    }

    @Test
    void shouldReleaseCommitment() {
        var commitment = createActiveCommitment();

        tracker.release(commitment.getId());

        assertThat(tracker.get(commitment.getId()).get().getState())
            .isEqualTo(CommitmentState.RELEASED);
    }

    private dev.agenor.core.dialogue.Commitment createActiveCommitment() {
        var msg = DialogueMessage.builder()
            .id("msg-1")
            .conversationId("conv-1")
            .senderId("performer")
            .receiverId("requester")
            .performative(Performative.AGREE)
            .build();

        return tracker.createFromMessage(msg);
    }
}
