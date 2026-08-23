package dev.agenor.runtime.mailbox;

import dev.agenor.core.Message;
import dev.agenor.core.MessageHandler;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.core.exceptions.MailboxOverflowException;
import dev.agenor.core.mailbox.MailboxConfig;
import dev.agenor.core.mailbox.OverflowPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Bounds, overflow policy and routing of {@link DefaultAgentMailbox} (ADR-032 D-1, D-2).
 *
 * <p>Note on ordering: these tests assert the queue's FIFO behaviour through which messages
 * survive an overflow, not the order in which handlers run. ADR-032 D-4 guarantees <em>claim
 * order</em> only — the drain hands each handler call to its own virtual thread, so completion
 * order is explicitly not promised, and a test asserting it would be asserting something the
 * design does not offer.
 */
class DefaultAgentMailboxTest {

    private DefaultAgentMailbox mailbox;

    @AfterEach
    void tearDown() {
        if (mailbox != null) {
            mailbox.stop();
        }
    }

    private static Message plain(String content) {
        return Message.builder().senderId("peer").receiverId("agent-a").content(content).build();
    }

    private static Message dialogueMessage(String content) {
        return DialogueMessage.builder()
                .conversationId("conv-1")
                .senderId("peer")
                .receiverId("agent-a")
                .performative(Performative.REQUEST)
                .content(content)
                .build()
                .toMessage();
    }

    /** Bounded wait: no Awaitility on the classpath, and no fixed sleep in tests. */
    private static void awaitUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 2_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
    }

    private static List<Object> contentsOf(List<Message> messages) {
        return messages.stream().map(Message::content).toList();
    }

    @Test
    @DisplayName("rejects a null message and a blank agent id")
    void rejectsInvalidInput() {
        mailbox = new DefaultAgentMailbox("agent-a", MailboxConfig.defaults(), m -> null);

        assertThatNullPointerException().isThrownBy(() -> mailbox.offer(null));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new DefaultAgentMailbox("  ", MailboxConfig.defaults(), m -> null));
    }

    @Test
    @DisplayName("reports its configured capacity and its current size")
    void reportsCapacityAndSize() {
        // Given a mailbox that is not draining, so offers accumulate
        mailbox = new DefaultAgentMailbox("agent-a",
                new MailboxConfig(2, OverflowPolicy.DROP_OLDEST), m -> null);

        // When
        mailbox.offer(plain("one"));

        // Then
        assertThat(mailbox.capacity()).isEqualTo(2);
        assertThat(mailbox.size()).isEqualTo(1);
    }

    @Test
    @Timeout(10)
    @DisplayName("DROP_OLDEST evicts the head, never growing past capacity")
    void dropOldestEvictsTheHead() {
        // Given a full mailbox with no drain running
        var received = new CopyOnWriteArrayList<Message>();
        mailbox = new DefaultAgentMailbox("agent-a",
                new MailboxConfig(2, OverflowPolicy.DROP_OLDEST),
                MessageHandler.sync(received::add));
        mailbox.offer(plain("first"));
        mailbox.offer(plain("second"));

        // When a third arrives
        var accepted = mailbox.offer(plain("third"));

        // Then it was taken, the oldest was dropped, and the bound held
        assertThat(accepted).isTrue();
        assertThat(mailbox.size()).isEqualTo(2);

        mailbox.start();
        awaitUntil(() -> received.size() == 2);
        assertThat(contentsOf(received)).containsExactlyInAnyOrder("second", "third");
    }

    @Test
    @Timeout(10)
    @DisplayName("DROP_NEWEST refuses the arrival and keeps what is queued")
    void dropNewestRefusesTheArrival() {
        // Given a full mailbox with no drain running
        var received = new CopyOnWriteArrayList<Message>();
        mailbox = new DefaultAgentMailbox("agent-a",
                new MailboxConfig(2, OverflowPolicy.DROP_NEWEST),
                MessageHandler.sync(received::add));
        mailbox.offer(plain("first"));
        mailbox.offer(plain("second"));

        // When
        var accepted = mailbox.offer(plain("third"));

        // Then
        assertThat(accepted).isFalse();
        assertThat(mailbox.size()).isEqualTo(2);

        mailbox.start();
        awaitUntil(() -> received.size() == 2);
        assertThat(contentsOf(received)).containsExactlyInAnyOrder("first", "second");
    }

    @Test
    @DisplayName("REJECT fails the caller with MailboxOverflowException")
    void rejectFailsTheCaller() {
        // Given
        mailbox = new DefaultAgentMailbox("agent-a",
                new MailboxConfig(1, OverflowPolicy.REJECT), m -> null);
        mailbox.offer(plain("first"));

        // When / Then
        assertThatExceptionOfType(MailboxOverflowException.class)
                .isThrownBy(() -> mailbox.offer(plain("second")))
                .satisfies(e -> assertThat(e.getAgentId()).isEqualTo("agent-a"))
                .withMessageContaining("capacity 1");
        assertThat(mailbox.size()).isEqualTo(1);
    }

    @Test
    @Timeout(10)
    @DisplayName("routes a dialogue message to the dialogue consumer and everything else to push")
    void routesByPerformative() {
        // Given both consumers registered
        var push = new CopyOnWriteArrayList<Message>();
        var dialogue = new CopyOnWriteArrayList<Message>();
        mailbox = new DefaultAgentMailbox("agent-a", MailboxConfig.defaults(),
                MessageHandler.sync(push::add));
        mailbox.registerDialogueConsumer(MessageHandler.sync(dialogue::add));
        mailbox.start();

        // When one of each arrives
        mailbox.offer(plain("plain payload"));
        mailbox.offer(dialogueMessage("do the thing"));

        // Then each reached exactly one consumer
        awaitUntil(() -> push.size() == 1 && dialogue.size() == 1);
        assertThat(contentsOf(push)).containsExactly("plain payload");
        assertThat(contentsOf(dialogue)).containsExactly("do the thing");
    }

    @Test
    @Timeout(10)
    @DisplayName("with no dialogue consumer registered, dialogue traffic goes to push")
    void withoutDialogueConsumerEverythingGoesToPush() {
        // Given a dialogue consumer that is registered and then withdrawn
        var push = new CopyOnWriteArrayList<Message>();
        var dialogue = new CopyOnWriteArrayList<Message>();
        mailbox = new DefaultAgentMailbox("agent-a", MailboxConfig.defaults(),
                MessageHandler.sync(push::add));
        var subscription = mailbox.registerDialogueConsumer(MessageHandler.sync(dialogue::add));
        subscription.unsubscribe();
        mailbox.start();

        // When
        mailbox.offer(dialogueMessage("do the thing"));

        // Then the push consumer sees it, as it did before dialogue was wired in
        awaitUntil(() -> push.size() == 1);
        assertThat(contentsOf(push)).containsExactly("do the thing");
        assertThat(dialogue).isEmpty();
    }

    @Test
    @Timeout(10)
    @DisplayName("stop() ends the drain: nothing offered afterwards is delivered")
    void stopEndsTheDrain() {
        // Given a running mailbox that has delivered a message
        var received = new CopyOnWriteArrayList<Message>();
        mailbox = new DefaultAgentMailbox("agent-a", MailboxConfig.defaults(),
                MessageHandler.sync(received::add));
        mailbox.start();
        mailbox.offer(plain("before"));
        awaitUntil(() -> received.size() == 1);

        // When it is stopped — stop() joins the drain, so a drain that ignored the interrupt
        // shows up here as a call that does not return promptly
        long startedAt = System.currentTimeMillis();
        mailbox.stop();
        long elapsed = System.currentTimeMillis() - startedAt;

        // Then the drain is gone and later offers are never routed
        assertThat(elapsed).isLessThan(2_000);
        mailbox.offer(plain("after"));
        awaitUntil(() -> received.size() > 1);
        assertThat(contentsOf(received)).containsExactly("before");
    }

    @Test
    @Timeout(10)
    @DisplayName("start() twice runs one drain, so a message is routed once")
    void startIsIdempotent() {
        // Given
        var received = new CopyOnWriteArrayList<Message>();
        mailbox = new DefaultAgentMailbox("agent-a", MailboxConfig.defaults(),
                MessageHandler.sync(received::add));

        // When
        mailbox.start();
        mailbox.start();
        mailbox.offer(plain("only once"));

        // Then
        awaitUntil(() -> received.size() == 1);
        assertThat(contentsOf(received)).containsExactly("only once");
    }
}
