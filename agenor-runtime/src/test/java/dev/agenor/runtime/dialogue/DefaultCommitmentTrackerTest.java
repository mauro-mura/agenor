package dev.agenor.runtime.dialogue;

import dev.agenor.core.dialogue.CommitmentState;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.runtime.dialogue.protocol.ProtocolRegistry;
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
    void shouldBindTheReceiverWhenContractNetAgreeAccceptsAProposal() {
        // In Contract Net the initiator sends AGREE to accept a proposal, so the party taking
        // on the work is the receiver. Reading the performative alone put the manager down as
        // the performer of the task it had just delegated.
        var tracker = new DefaultCommitmentTracker(new ProtocolRegistry());
        var agree = DialogueMessage.builder()
            .id("msg-cn")
            .conversationId("conv-cn")
            .senderId("manager")
            .receiverId("worker-2")
            .performative(Performative.AGREE)
            .protocol("contract-net")
            .content("You win!")
            .build();

        var commitment = tracker.createFromMessage(agree);

        assertThat(commitment.getPerformer()).isEqualTo("worker-2");
        assertThat(commitment.getRequester()).isEqualTo("manager");
        assertThat(tracker.getActiveAsRequester("manager")).hasSize(1);
        assertThat(tracker.getActiveAsPerformer("manager")).isEmpty();
    }

    @Test
    void shouldBindTheSenderWhenAProposalOffersToPerform() {
        var tracker = new DefaultCommitmentTracker(new ProtocolRegistry());
        var propose = DialogueMessage.builder()
            .id("msg-p")
            .conversationId("conv-cn")
            .senderId("worker-2")
            .receiverId("manager")
            .performative(Performative.PROPOSE)
            .protocol("contract-net")
            .content("my bid")
            .build();

        var commitment = tracker.createFromMessage(propose);

        assertThat(commitment.getPerformer()).isEqualTo("worker-2");
        assertThat(commitment.getRequester()).isEqualTo("manager");
    }

    @Test
    void shouldKeepTheRequestShapedReadingForARequestProtocolAgree() {
        var tracker = new DefaultCommitmentTracker(new ProtocolRegistry());
        var agree = DialogueMessage.builder()
            .id("msg-r")
            .conversationId("conv-r")
            .senderId("server")
            .receiverId("client")
            .performative(Performative.AGREE)
            .protocol("request")
            .content("Accepted")
            .build();

        var commitment = tracker.createFromMessage(agree);

        assertThat(commitment.getPerformer()).isEqualTo("server");
        assertThat(commitment.getRequester()).isEqualTo("client");
    }

    @Test
    void shouldFallBackToTheRequestShapedReadingWhenNoProtocolIsNamed() {
        // A message with no protocol, or a tracker with no registry, behaves exactly as this
        // class did before 0.31.0.
        var tracker = new DefaultCommitmentTracker(new ProtocolRegistry());
        var agree = DialogueMessage.builder()
            .id("msg-n")
            .conversationId("conv-n")
            .senderId("a")
            .receiverId("b")
            .performative(Performative.AGREE)
            .build();

        assertThat(tracker.createFromMessage(agree).getPerformer()).isEqualTo("a");
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
    void shouldNotViolateCommitmentsWhoseDeadlineHasNotPassed() {
        // Given a commitment created with the default (5 minute) deadline
        var msg = DialogueMessage.builder()
            .senderId("performer").receiverId("requester")
            .performative(Performative.AGREE).build();
        var commitment = tracker.createFromMessage(msg);

        // When violations are checked before the deadline — the sweep does this every minute
        var violations = tracker.checkViolations();

        // Then nothing is flagged and the commitment is untouched
        assertThat(violations).isEmpty();
        assertThat(tracker.get(commitment.getId()).get().getState())
            .isEqualTo(CommitmentState.ACTIVE);
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

    @Test
    void shouldRemoveTerminalCommitmentsAndTheirMessageIndexOnCleanup() {
        // Given one terminal and one still-active commitment
        var terminal = createActiveCommitment();
        tracker.release(terminal.getId());

        var active = DialogueMessage.builder()
            .id("msg-2")
            .conversationId("conv-2")
            .senderId("performer")
            .receiverId("requester")
            .performative(Performative.AGREE)
            .build();
        tracker.createFromMessage(active);

        // When sweeping with a zero retention window
        int removed = tracker.cleanup(Duration.ZERO);

        // Then only the terminal one is gone, index included
        assertThat(removed).isEqualTo(1);
        assertThat(tracker.get(terminal.getId())).isEmpty();
        assertThat(tracker.getByMessageId("msg-1")).isEmpty();
        assertThat(tracker.getByMessageId("msg-2")).isPresent();
    }

    @Test
    void shouldKeepActiveCommitmentsOnCleanupHoweverOld() {
        var commitment = createActiveCommitment();

        assertThat(tracker.cleanup(Duration.ZERO)).isZero();
        assertThat(tracker.get(commitment.getId())).isPresent();
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
