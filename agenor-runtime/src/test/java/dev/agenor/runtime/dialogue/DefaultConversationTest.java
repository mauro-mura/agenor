package dev.agenor.runtime.dialogue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.core.dialogue.protocol.Protocol;
import dev.agenor.core.dialogue.protocol.ProtocolState;
import dev.agenor.runtime.dialogue.protocol.RequestProtocol;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultConversationTest {

    @Test
    void shouldCreateConversation() {
        var conversation = new DefaultConversation(
            "conv-1",
            new RequestProtocol(),
            "agent-a",
            "agent-b",
            true
        );

        assertThat(conversation.getId()).isEqualTo("conv-1");
        assertThat(conversation.getInitiatorId()).isEqualTo("agent-a");
        assertThat(conversation.getParticipantId()).isEqualTo("agent-b");
        assertThat(conversation.isInitiator()).isTrue();
        assertThat(conversation.getState()).isEqualTo(ProtocolState.INITIATED);
        assertThat(conversation.isComplete()).isFalse();
    }

    @Test
    void shouldTrackMessageHistory() {
        var conversation = new DefaultConversation(
            "conv-1", null, "agent-a", "agent-b", true
        );

        var msg1 = DialogueMessage.builder()
            .senderId("agent-a")
            .performative(Performative.REQUEST)
            .content("do task")
            .build();

        var msg2 = DialogueMessage.builder()
            .senderId("agent-b")
            .performative(Performative.AGREE)
            .content("ok")
            .build();

        conversation.addMessage(msg1);
        conversation.addMessage(msg2);

        assertThat(conversation.getHistory()).hasSize(2);
        assertThat(conversation.getMessageCount()).isEqualTo(2);
        assertThat(conversation.getLastMessage()).isPresent().contains(msg2);
    }

    @Test
    void shouldTransitionStateWithProtocol() {
        var conversation = new DefaultConversation(
            "conv-1",
            new RequestProtocol(),
            "agent-a",
            "agent-b",
            true
        );

        var request = DialogueMessage.builder()
            .senderId("agent-a")
            .performative(Performative.REQUEST)
            .build();
        conversation.addMessage(request);
        assertThat(conversation.getState()).isEqualTo(ProtocolState.AWAITING_RESPONSE);

        var agree = DialogueMessage.builder()
            .senderId("agent-b")
            .performative(Performative.AGREE)
            .build();
        conversation.addMessage(agree);
        assertThat(conversation.getState()).isEqualTo(ProtocolState.AGREED);

        var inform = DialogueMessage.builder()
            .senderId("agent-b")
            .performative(Performative.INFORM)
            .build();
        conversation.addMessage(inform);
        assertThat(conversation.getState()).isEqualTo(ProtocolState.COMPLETED);
        assertThat(conversation.isComplete()).isTrue();
    }

    @Test
    void shouldUpdateLastActivity() throws InterruptedException {
        var conversation = new DefaultConversation(
            "conv-1", null, "agent-a", "agent-b", true
        );

        var initialActivity = conversation.getLastActivity();
        Thread.sleep(10);

        conversation.addMessage(DialogueMessage.builder()
            .senderId("agent-a")
            .performative(Performative.INFORM)
            .build());

        assertThat(conversation.getLastActivity()).isAfter(initialActivity);
    }

    @Test
    void shouldReportAnOutOfOrderPerformativeWithoutChangingState() {
        // Given a REQUEST conversation that has not started yet
        var conversation = new DefaultConversation(
            "conv-1", new RequestProtocol(), "agent-a", "agent-b", true
        );

        // When the participant informs before anything was requested
        var premature = DialogueMessage.builder()
            .senderId("agent-b")
            .performative(Performative.INFORM)
            .build();
        var violations = captureViolationsOf(() -> conversation.addMessage(premature));

        // Then the violation is reported...
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0))
            .contains("conv-1")
            .contains("agent-b")
            .contains("INFORM")
            .contains("INITIATED");

        // ...and nothing else changes: state untouched, message still in the history
        assertThat(conversation.getState()).isEqualTo(ProtocolState.INITIATED);
        assertThat(conversation.getHistory()).containsExactly(premature);
    }

    @Test
    void shouldNotReportALegitimateExchangeFromEitherSide() {
        // Given the responder's view of a conversation: it did not initiate
        var responderView = new DefaultConversation(
            "conv-2", new RequestProtocol(), "agent-a", "agent-b", false
        );

        // When the full REQUEST -> AGREE -> INFORM exchange plays out. The perspective matters:
        // AGREE and INFORM come from the responder, REQUEST from the initiator.
        var violations = captureViolationsOf(() -> {
            responderView.addMessage(DialogueMessage.builder()
                .senderId("agent-a").performative(Performative.REQUEST).build());
            responderView.addMessage(DialogueMessage.builder()
                .senderId("agent-b").performative(Performative.AGREE).build());
            responderView.addMessage(DialogueMessage.builder()
                .senderId("agent-b").performative(Performative.INFORM).build());
        });

        assertThat(violations).isEmpty();
        assertThat(responderView.getState()).isEqualTo(ProtocolState.COMPLETED);
    }

    @Test
    void shouldNotReportAnImmediateFailure() {
        // dialogue.failure(...) on a REQUEST nobody agreed to — the D6 case
        var conversation = new DefaultConversation(
            "conv-3", new RequestProtocol(), "agent-a", "agent-b", true
        );

        var violations = captureViolationsOf(() -> {
            conversation.addMessage(DialogueMessage.builder()
                .senderId("agent-a").performative(Performative.REQUEST).build());
            conversation.addMessage(DialogueMessage.builder()
                .senderId("agent-b").performative(Performative.FAILURE).build());
        });

        assertThat(violations).isEmpty();
        assertThat(conversation.getState()).isEqualTo(ProtocolState.FAILED);
    }

    @Test
    void shouldNotValidateWhenThereIsNoProtocol() {
        // An unregistered custom protocol leaves getProtocol() empty: there is no FSM to check
        var conversation = new DefaultConversation(
            "conv-4", null, "agent-a", "agent-b", true
        );

        var violations = captureViolationsOf(() -> conversation.addMessage(
            DialogueMessage.builder()
                .senderId("agent-b").performative(Performative.INFORM).build()));

        assertThat(violations).isEmpty();
    }

    /** Collects the WARN lines {@link DefaultConversation} emits while the action runs. */
    private static List<String> captureViolationsOf(Runnable action) {
        var logger = (Logger) LoggerFactory.getLogger(DefaultConversation.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        return appender.list.stream()
            .filter(event -> event.getLevel() == Level.WARN)
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    }

    @Test
    void shouldAllowManualStateChange() {
        var conversation = new DefaultConversation(
            "conv-1", null, "agent-a", "agent-b", true
        );

        conversation.setState(ProtocolState.CANCELLED);

        assertThat(conversation.getState()).isEqualTo(ProtocolState.CANCELLED);
        assertThat(conversation.isComplete()).isTrue();
    }

    /**
     * A conversation is mutated from arbitrary virtual threads: the dispatcher delivers every
     * message on its own thread, so two messages for the same conversation are handled
     * concurrently, and the timeout path calls {@code setState} from a third.
     *
     * <p>The invariant asserted here is the one a lost update breaks: every transition must
     * observe the state the previous transition produced. Recording the {@code (in, out)} pairs
     * the protocol sees is what makes that observable — asserting on the final state alone
     * cannot distinguish a lost transition from a legitimate one.
     */
    @Test
    void shouldNeverTransitionFromAStaleState() throws Exception {
        // Given a protocol that advances one step per message and records what it observed
        for (int round = 0; round < 200; round++) {
            var protocol = new RecordingProtocol();
            var conversation = new DefaultConversation(
                "conv-race", protocol, "agent-a", "agent-b", true
            );
            var start = new CountDownLatch(1);
            var done = new CountDownLatch(2);

            // When two messages land on it at the same instant
            for (int i = 0; i < 2; i++) {
                Thread.ofVirtual().start(() -> {
                    try {
                        start.await();
                        conversation.addMessage(message(Performative.INFORM));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

            // Then the two transitions chain: the second saw what the first produced
            assertThat(conversation.getHistory()).hasSize(2);
            assertThat(protocol.observed())
                .as("round %d — a repeated input state means one transition was computed from "
                    + "a state another thread had already replaced", round)
                .doesNotHaveDuplicates();
        }
    }

    @Test
    void shouldNotLoseATransitionRacingWithAManualStateChange() throws Exception {
        // Given the timeout path and a late reply hitting the same conversation
        for (int round = 0; round < 200; round++) {
            var protocol = new RecordingProtocol();
            var conversation = new DefaultConversation(
                "conv-race", protocol, "agent-a", "agent-b", true
            );
            var start = new CountDownLatch(1);
            var done = new CountDownLatch(2);

            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    conversation.addMessage(message(Performative.INFORM));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    conversation.setState(ProtocolState.TIMEOUT);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            start.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

            // Then the TIMEOUT either survives as the current state, or the transition that
            // replaced it saw it first. The forbidden third outcome — TIMEOUT overwritten by a
            // transition computed from a state read before it was set — is a conversation that
            // silently stays live after its own timeout fired.
            var timeoutSurvived = conversation.getState() == ProtocolState.TIMEOUT;
            var timeoutWasObserved = protocol.observed().contains(ProtocolState.TIMEOUT);
            assertThat(timeoutSurvived || timeoutWasObserved)
                .as("round %d — setState(TIMEOUT) was lost: final state %s, transitions saw %s",
                    round, conversation.getState(), protocol.observed())
                .isTrue();
        }
    }

    private static DialogueMessage message(Performative performative) {
        return DialogueMessage.builder()
            .conversationId("conv-race")
            .senderId("agent-a")
            .receiverId("agent-b")
            .performative(performative)
            .build();
    }

    /**
     * Advances through a fixed chain of states, one step per message, recording the state each
     * transition was computed from. Two entries with the same input mean a lost update.
     */
    private static final class RecordingProtocol implements Protocol {

        private static final List<ProtocolState> CHAIN = List.of(
            ProtocolState.INITIATED,
            ProtocolState.AWAITING_RESPONSE,
            ProtocolState.AGREED,
            ProtocolState.COMPLETED
        );

        private final List<ProtocolState> observed = Collections.synchronizedList(new ArrayList<>());

        @Override public String getId() { return "recording"; }

        @Override public String getDisplayName() { return "Recording"; }

        @Override public ProtocolState getInitialState() { return ProtocolState.INITIATED; }

        @Override
        public ProtocolState nextState(ProtocolState current, Performative received, boolean isInitiator) {
            observed.add(current);
            // Widens the read-modify-write window so the race is reproducible rather than
            // one-in-a-thousand. Legitimate: nextState is user-supplied code and a real
            // Protocol may be slower than this.
            Thread.yield();
            int next = Math.min(CHAIN.indexOf(current) + 1, CHAIN.size() - 1);
            return CHAIN.get(next);
        }

        @Override
        public Set<Performative> allowedPerformatives(ProtocolState state, boolean isInitiator) {
            return Set.of(Performative.INFORM);
        }

        List<ProtocolState> observed() {
            synchronized (observed) {
                return List.copyOf(observed);
            }
        }

    }
}
