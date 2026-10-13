package dev.agenor.runtime.dialogue;

import dev.agenor.core.deadletter.DeadLetter;
import dev.agenor.core.dialogue.DialogueHandler;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.runtime.AgenorRuntime;
import dev.agenor.runtime.agent.BaseAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code @DialogueHandler} that throws, on the in-memory transport, through an assembled
 * runtime and the agent's mailbox.
 *
 * <p>Until 0.37.0 the exception was caught and logged twice on its way out — by the reflective
 * invoker in {@link DialogueHandlerRegistry} and again by {@link DialogueCapability} — so the
 * mailbox saw a success and nothing was recorded. The Redis half, where the same failure must
 * also be redelivered, is {@code MailboxDeliverySemanticsIT} in {@code agenor-examples}.
 */
@DisplayName("DialogueCapability: a failing handler reaches the dead-letter queue")
class DialogueHandlerFailureTest {

    private AgenorRuntime runtime;

    @AfterEach
    void tearDown() {
        if (runtime != null && runtime.isRunning()) {
            runtime.stop().join();
        }
    }

    @Test
    @Timeout(10)
    @DisplayName("a @DialogueHandler that throws is dead-lettered with its own exception as the reason")
    void throwingDialogueHandlerIsDeadLettered() throws Exception {
        // Given a running agent whose dialogue handler always throws
        var agent = new DialogueFailingAgent();
        runtime = AgenorRuntime.builder().build();
        runtime.registerAgent(agent);
        runtime.start().join();

        // When a REQUEST reaches it
        var request = DialogueMessage.builder()
                .conversationId("conv-fails")
                .senderId("coordinator")
                .receiverId("dialogue-fails")
                .performative(Performative.REQUEST)
                .content("this will throw")
                .build()
                .toMessage();
        runtime.getMessageDispatcher().sendTo(request).join();

        // Then it is recorded once — this transport does not redeliver — under the handler's
        // own exception, not the InvocationTargetException reflection wrapped it in
        var recorded = awaitDeadLetters();
        assertThat(recorded).hasSize(1);
        var deadLetter = recorded.getFirst();
        assertThat(deadLetter.message().id()).isEqualTo(request.id());
        assertThat(deadLetter.recipientId()).isEqualTo("dialogue-fails");
        assertThat(deadLetter.attempts()).isEqualTo(1);
        assertThat(deadLetter.reason())
                .isEqualTo("IllegalStateException: dialogue handler failed on purpose");
        assertThat(agent.attempts.get()).isEqualTo(1);
    }

    /** Delivery runs on a detached virtual thread, so nothing upstream can be joined on. */
    private List<DeadLetter> awaitDeadLetters() throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var recorded = runtime.getDeadLetterQueue().recent(10);
            if (!recorded.isEmpty()) return recorded;
            Thread.sleep(10);
        }
        return runtime.getDeadLetterQueue().recent(10);
    }

    static class DialogueFailingAgent extends BaseAgent {

        final DialogueCapability dialogue = new DialogueCapability(this);
        final AtomicInteger attempts = new AtomicInteger();

        DialogueFailingAgent() {
            super("dialogue-fails", "Dialogue Fails");
        }

        @DialogueHandler(performatives = Performative.REQUEST)
        public void handleRequest(DialogueMessage message) {
            attempts.incrementAndGet();
            throw new IllegalStateException("dialogue handler failed on purpose");
        }
    }
}
