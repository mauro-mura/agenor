package dev.agenor.core.dialogue;

import dev.agenor.core.MessageHandler;
import dev.agenor.core.dialogue.protocol.ProtocolState;
import dev.agenor.core.exceptions.AgentNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reusable contract tests for what a {@link ConversationManager} does when its own message
 * cannot be sent.
 *
 * <p>The dispatcher reports a send it cannot make — a recipient no resolver knows, or an
 * endpoint the transport cannot reach — through the future {@code sendTo} returns. Until 0.38.0
 * the dialogue discarded that future, so a request waited its whole timeout and a call for
 * proposals its whole deadline, for a message that never left. Found by re-running agenor-jobs
 * against the snapshot after {@code f2a3527} had made the Redis case fail at the dispatcher.
 *
 * <p>It lives in {@code agenor-core}'s test-jar so that both transports are held to it from one
 * source: a behaviour verified only against the in-memory dispatcher is not verified.
 *
 * @since 0.38.0
 */
public interface ConversationManagerSendFailureContractTests {

    /** Long enough that a test finishing well inside it cannot have waited for it. */
    Duration WAIT = Duration.ofSeconds(30);

    /** How long a failure that is reported, rather than waited out, may take. */
    long PROMPTLY_SECONDS = 5;

    /** The manager under test, wired to receive replies over the transport under test. */
    ConversationManager initiator();

    /** The agent id {@link #initiator()} sends as. */
    String initiatorId();

    /**
     * An agent reachable over the transport, which answers a CFP with {@code PROPOSE} and a
     * REQUEST with {@code INFORM}. Wire it with {@link #responderInbox(ConversationManager)}.
     */
    String responder();

    /** An agent the resolver does not know. */
    String missingAgent();

    /**
     * An agent the transport cannot deliver to. On a transport that has no such case of its own,
     * {@link #missingAgent()} is the honest answer.
     */
    String unreachableAgent();

    /** The inbox of the initiator: every reply goes back to its manager. */
    static MessageHandler initiatorInbox(ConversationManager manager) {
        return MessageHandler.sync(message ->
            manager.handleIncoming(DialogueMessage.fromMessage(message)));
    }

    /** The inbox of {@link #responder()}: proposes on a CFP, informs on a REQUEST. */
    static MessageHandler responderInbox(ConversationManager manager) {
        return message -> {
            var incoming = DialogueMessage.fromMessage(message);
            manager.handleIncoming(incoming);
            return switch (incoming.performative()) {
                case CFP -> manager.reply(incoming, Performative.PROPOSE, "bid");
                case REQUEST -> manager.reply(incoming, Performative.INFORM, "done");
                default -> CompletableFuture.completedFuture(null);
            };
        };
    }

    @Test
    @DisplayName("request: a recipient nobody knows fails the future with the cause, not a timeout")
    default void request_missingAgent_failsPromptly() {
        assertRequestFailsPromptly(missingAgent());
    }

    @Test
    @DisplayName("request: a recipient the transport cannot reach fails the future with the cause")
    default void request_unreachableAgent_failsPromptly() {
        assertRequestFailsPromptly(unreachableAgent());
    }

    @Test
    @DisplayName("request: a reachable recipient still resolves with its reply")
    default void request_responder_resolves() {
        var reply = initiator().request(responder(), "work", WAIT);

        assertThat(reply).succeedsWithin(PROMPTLY_SECONDS, TimeUnit.SECONDS)
            .extracting(DialogueMessage::performative)
            .isEqualTo(Performative.INFORM);
    }

    @Test
    @DisplayName("callForProposals: an unreachable participant does not hold the call to its deadline")
    default void callForProposals_unreachableParticipant_closesOnTheOthers() {
        var proposals = initiator().callForProposals(
            List.of(responder(), unreachableAgent()), "task", WAIT);

        assertThat(proposals).succeedsWithin(PROMPTLY_SECONDS, TimeUnit.SECONDS)
            .satisfies(list -> assertThat(list).singleElement()
                .satisfies(p -> {
                    assertThat(p.senderId()).isEqualTo(responder());
                    assertThat(p.performative()).isEqualTo(Performative.PROPOSE);
                }));
        assertThat(initiator().getConversationsWith(unreachableAgent()))
            .singleElement()
            .extracting(Conversation::getState)
            .isEqualTo(ProtocolState.FAILED);
    }

    @Test
    @DisplayName("callForProposals: no participant reachable fails the call with the cause")
    default void callForProposals_noParticipantReachable_fails() {
        var proposals = initiator().callForProposals(
            List.of(unreachableAgent()), "task", WAIT);

        assertThat(proposals).failsWithin(PROMPTLY_SECONDS, TimeUnit.SECONDS)
            .withThrowableOfType(Exception.class)
            .havingRootCause()
            .isInstanceOf(AgentNotFoundException.class);
    }

    private void assertRequestFailsPromptly(String target) {
        var reply = initiator().request(target, "work", WAIT);

        assertThat(reply).failsWithin(PROMPTLY_SECONDS, TimeUnit.SECONDS)
            .withThrowableOfType(Exception.class)
            .havingRootCause()
            .isInstanceOf(AgentNotFoundException.class);

        // A message that never left is not a timeout, and binds nobody: the commitment the
        // REQUEST opened must not stay live to be declared VIOLATED at its deadline.
        var conversation = initiator().getConversationsWith(target).getFirst();
        assertThat(conversation.getState()).isEqualTo(ProtocolState.FAILED);
        var tracker = initiator().getCommitmentTracker();
        var commitment = tracker.getByMessageId(conversation.getHistory().getFirst().id())
            .flatMap(tracker::get)
            .orElseThrow();
        assertThat(commitment.getState()).isEqualTo(CommitmentState.CANCELLED);
        assertThat(tracker.getActiveAsRequester(initiatorId())).isEmpty();
    }
}
