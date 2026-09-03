package dev.agenor.runtime.messaging;

import dev.agenor.core.Message;
import dev.agenor.core.deadletter.DeadLetter;
import dev.agenor.core.deadletter.DeadLetterQueue;
import dev.agenor.runtime.deadletter.InMemoryDeadLetterQueue;
import dev.agenor.runtime.directory.InMemoryAgentDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What becomes of a message whose handler fails on the in-memory transport.
 *
 * <p>Before 0.32.0 the answer was a log line: the dispatcher joins the handler's future on a
 * detached virtual thread, so the failure reached neither the sender's future nor the span,
 * and the message was gone. These tests pin the part that changed - it is now recorded - and
 * the part that deliberately did not: this transport still does not redeliver.
 */
@DisplayName("InMemoryMessageDispatcher: dead-lettering")
class InMemoryDispatcherDeadLetterTest {

    private InMemoryAgentDirectory directory;
    private InMemoryMessageDispatcher dispatcher;
    private InMemoryDeadLetterQueue deadLetters;

    @BeforeEach
    void setUp() {
        directory = new InMemoryAgentDirectory("test-node");
        dispatcher = new InMemoryMessageDispatcher(directory);
        deadLetters = new InMemoryDeadLetterQueue(16);
        dispatcher.setDeadLetterQueue(deadLetters);
    }

    /** Delivery runs on a detached virtual thread, so nothing upstream can be joined on. */
    private List<DeadLetter> awaitOne() throws InterruptedException {
        var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            var recorded = deadLetters.recent(10);
            if (!recorded.isEmpty()) return recorded;
            Thread.sleep(10);
        }
        return deadLetters.recent(10);
    }

    @Test
    @DisplayName("a topic handler that throws dead-letters its message, with no recipient")
    void topicHandlerThrows() throws Exception {
        dispatcher.subscribeTopic("orders", msg -> {
            throw new IllegalStateException("handler exploded");
        });

        dispatcher.publish(Message.builder().topic("orders").content("payload").build()).join();

        var recorded = awaitOne();
        assertThat(recorded).hasSize(1);
        assertThat(recorded.getFirst().reason()).isEqualTo("IllegalStateException: handler exploded");
        assertThat(recorded.getFirst().recipientId()).isNull();
        assertThat(recorded.getFirst().attempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("a handler returning a failed future is dead-lettered on its own exception, not the wrapper")
    void failedFutureIsUnwrapped() throws Exception {
        dispatcher.subscribeTopic("orders", msg ->
                CompletableFuture.failedFuture(new IllegalArgumentException("bad payload")));

        dispatcher.publish(Message.builder().topic("orders").content("payload").build()).join();

        // join() wraps in CompletionException; recording that instead of the cause would make
        // every entry in the queue read the same.
        assertThat(awaitOne().getFirst().reason())
                .isEqualTo("IllegalArgumentException: bad payload");
    }

    @Test
    @DisplayName("a direct-message handler that throws dead-letters against the addressed agent")
    void receiverHandlerThrows() throws Exception {
        directory.register(dev.agenor.core.AgentDescriptor.builder("agent-1")
                .agentName("Agent One")
                .build()).join();
        dispatcher.subscribeRecipient("agent-1", msg -> {
            throw new IllegalStateException("receiver exploded");
        });

        dispatcher.sendTo(Message.builder()
                .topic("t").receiverId("agent-1").content("payload").build()).join();

        var recorded = awaitOne();
        assertThat(recorded).hasSize(1);
        assertThat(recorded.getFirst().recipientId()).isEqualTo("agent-1");
    }

    @Test
    @DisplayName("a successful handler records nothing")
    void successRecordsNothing() throws Exception {
        var handled = new AtomicBoolean();
        dispatcher.subscribeTopic("orders", msg -> {
            handled.set(true);
            return CompletableFuture.completedFuture(null);
        });

        dispatcher.publish(Message.builder().topic("orders").content("payload").build()).join();

        var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (!handled.get() && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(handled.get()).isTrue();
        Thread.sleep(100);
        assertThat(deadLetters.recent(10)).isEmpty();
    }

    @Test
    @DisplayName("without a queue the dispatcher behaves exactly as it did before")
    void noQueueIsTheOldBehaviour() throws Exception {
        var plain = new InMemoryMessageDispatcher(directory);
        assertThat(plain.getDeadLetterQueue()).isSameAs(DeadLetterQueue.noop());

        plain.subscribeTopic("orders", msg -> {
            throw new IllegalStateException("boom");
        });

        // The failure must not reach the publisher's future, which is the pre-existing
        // contract: publish completes once delivery threads are spawned.
        plain.publish(Message.builder().topic("orders").content("payload").build()).join();
        Thread.sleep(100);
        assertThat(plain.getDeadLetterQueue().recent(10)).isEmpty();
    }
}
