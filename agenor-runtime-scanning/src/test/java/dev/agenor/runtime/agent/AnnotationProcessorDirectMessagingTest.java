package dev.agenor.runtime.agent;

import dev.agenor.core.Message;
import dev.agenor.core.annotations.AgenorMessageHandler;
import dev.agenor.core.telemetry.AgenorTelemetry;
import dev.agenor.runtime.directory.InMemoryAgentDirectory;
import dev.agenor.runtime.discovery.AnnotationProcessor;
import dev.agenor.runtime.messaging.InMemoryMessageDispatcher;
import dev.agenor.runtime.scheduler.SimpleBehaviorScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Verifies that {@link AnnotationProcessor}-wired {@code @AgenorMessageHandler}
 * methods on a {@link BaseAgent} receive direct (point-to-point) messages sent via
 * {@code sendTo()}, not just {@code publish()}, falling back to
 * {@code onDirectMessage()} only when no handler matches.
 *
 * <p>Split out of {@code BaseAgentDirectMessagingTest} (ADR-027, task 4): the rest of
 * that suite tests {@link BaseAgent}'s direct-messaging dispatch without annotation
 * processing and stays in {@code agenor-runtime}, which cannot depend on
 * {@link AnnotationProcessor} (moved to {@code agenor-runtime-scanning}).
 */
class AnnotationProcessorDirectMessagingTest {

    private InMemoryMessageDispatcher messageDispatcher;
    private InMemoryAgentDirectory agentDirectory;
    private SimpleBehaviorScheduler behaviorScheduler;

    @BeforeEach
    void setUp() {
        agentDirectory = new InMemoryAgentDirectory();
        messageDispatcher = new InMemoryMessageDispatcher(agentDirectory, AgenorTelemetry.noop());
        behaviorScheduler = new SimpleBehaviorScheduler();
        behaviorScheduler.start().join();
    }

    @Test
    @Timeout(5)
    @org.junit.jupiter.api.DisplayName("@AgenorMessageHandler should fire for messages sent via sendTo()/receiverId, not just publish()")
    void testAgenorMessageHandlerReceivesDirectMessage() throws Exception {
        // Given: an agent with an @AgenorMessageHandler bound to a topic, processed by the runtime
        AnnotatedTestAgent agent = new AnnotatedTestAgent("annotated-agent");
        setupAgent(agent);
        new AnnotationProcessor(messageDispatcher).processAnnotations(agent);
        agent.start().join();

        // When: a direct message with the matching topic is sent (point-to-point, not pub/sub)
        Message task = Message.builder()
            .topic("task.process")
            .senderId("coordinator")
            .receiverId("annotated-agent")
            .content("do work")
            .build();
        messageDispatcher.sendTo(task).join();

        // Then: the annotated handler receives it
        Message received = agent.handledFuture.get(2, TimeUnit.SECONDS);
        assertEquals("do work", received.content());

        // And: the onDirectMessage() fallback is NOT invoked, since a handler matched
        Thread.sleep(200);
        assertFalse(agent.fallbackCalled.get(),
            "onDirectMessage fallback should not run when an @AgenorMessageHandler matches");

        agent.stop().join();
    }

    @Test
    @Timeout(5)
    @org.junit.jupiter.api.DisplayName("Direct messages with no matching @AgenorMessageHandler still fall back to onDirectMessage()")
    void testDirectMessageWithoutMatchingHandlerFallsBackToOnDirectMessage() throws Exception {
        // Given: an agent with an @AgenorMessageHandler bound to a different topic
        AnnotatedTestAgent agent = new AnnotatedTestAgent("annotated-agent");
        setupAgent(agent);
        new AnnotationProcessor(messageDispatcher).processAnnotations(agent);
        agent.start().join();

        // When: a direct message with a non-matching topic is sent
        Message unrelated = Message.builder()
            .topic("some.other.topic")
            .senderId("coordinator")
            .receiverId("annotated-agent")
            .content("unrelated")
            .build();
        messageDispatcher.sendTo(unrelated).join();

        // Then: the fallback hook is invoked instead
        Message fallbackMessage = agent.fallbackFuture.get(2, TimeUnit.SECONDS);
        assertEquals("unrelated", fallbackMessage.content());
        assertFalse(agent.handledFuture.isDone(), "The @AgenorMessageHandler for a different topic must not fire");

        agent.stop().join();
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private void setupAgent(AnnotatedTestAgent agent) {
        agent.setMessageDispatcher(messageDispatcher);
        agent.setAgentDirectory(agentDirectory);
        agent.setBehaviorScheduler(behaviorScheduler);
    }

    // =========================================================================
    // Test Agent Class
    // =========================================================================

    /**
     * Test agent with an @AgenorMessageHandler, used to verify direct (point-to-point)
     * messages are dispatched to matching annotated handlers, falling back to
     * onDirectMessage() only when no handler matches.
     */
    static class AnnotatedTestAgent extends BaseAgent {

        final CompletableFuture<Message> handledFuture = new CompletableFuture<>();
        final CompletableFuture<Message> fallbackFuture = new CompletableFuture<>();
        final AtomicBoolean fallbackCalled = new AtomicBoolean(false);

        AnnotatedTestAgent(String agentId) {
            super(agentId, agentId);
        }

        @AgenorMessageHandler("task.process")
        public void handleTask(Message message) {
            handledFuture.complete(message);
        }

        @Override
        protected void onDirectMessage(Message message) {
            fallbackCalled.set(true);
            fallbackFuture.complete(message);
        }
    }
}
