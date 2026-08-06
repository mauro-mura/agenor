package dev.agenor.runtime.agent;

import dev.agenor.core.*;
import dev.agenor.core.telemetry.AgenorTelemetry;
import dev.agenor.runtime.messaging.InMemoryMessageDispatcher;
import dev.agenor.runtime.scheduler.SimpleBehaviorScheduler;
import dev.agenor.runtime.directory.InMemoryAgentDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for BaseAgent direct messaging functionality.
 */
class BaseAgentDirectMessagingTest {

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
    void testAutoSubscribeDirectMessages() throws Exception {
        // Given: A test agent
        TestAgent agent = new TestAgent("test-agent");
        setupAgent(agent);

        // When: Agent starts
        agent.start().join();

        // Then: Agent should be subscribed to direct messages
        // Verify by sending a message and checking if received
        CompletableFuture<Message> receivedMessage = new CompletableFuture<>();
        agent.setDirectMessageHandler(receivedMessage::complete);

        Message testMsg = Message.builder()
            .senderId("external")
            .receiverId("test-agent")
            .content("Test message")
            .build();

        messageDispatcher.sendTo(testMsg).join();

        // Verify message was received
        Message received = receivedMessage.get(2, TimeUnit.SECONDS);
        assertNotNull(received);
        assertEquals("external", received.senderId());
        assertEquals("test-agent", received.receiverId());
        assertEquals("Test message", received.content());

        // Cleanup
        agent.stop().join();
    }

    @Test
    @Timeout(5)
    void testUnsubscribeDirectMessages() throws Exception {
        // Given: A started agent
        TestAgent agent = new TestAgent("test-agent");
        setupAgent(agent);
        agent.start().join();

        AtomicBoolean receivedMessage = new AtomicBoolean(false);
        agent.setDirectMessageHandler((Consumer<Message>) msg -> receivedMessage.set(true));

        // When: Agent stops
        agent.stop().join();

        // Then: Agent should no longer receive messages
        Message testMsg = Message.builder()
            .senderId("external")
            .receiverId("test-agent")
            .content("Should not receive")
            .build();

        // sendTo throws AgentNotFoundException for unregistered agent — that's expected
        messageDispatcher.sendTo(testMsg).exceptionally(e -> null).join();

        // Wait a bit to ensure message would have been delivered if subscribed
        Thread.sleep(200);

        // Verify message was NOT received
        assertFalse(receivedMessage.get(), "Agent should not receive messages after stop");
    }

    @Test
    @Timeout(5)
    void testSendTo() throws Exception {
        // Given: Two agents
        TestAgent alice = new TestAgent("alice");
        TestAgent bob = new TestAgent("bob");
        setupAgent(alice);
        setupAgent(bob);

        alice.start().join();
        bob.start().join();

        CompletableFuture<Message> bobReceived = new CompletableFuture<>();
        bob.setDirectMessageHandler(bobReceived::complete);

        // When: Alice sends to Bob
        String testContent = "Hello Bob!";
        alice.sendTo("bob", testContent).join();

        // Then: Bob should receive the message
        Message received = bobReceived.get(2, TimeUnit.SECONDS);
        assertNotNull(received);
        assertEquals("alice", received.senderId());
        assertEquals("bob", received.receiverId());
        assertEquals(testContent, received.content());

        // Cleanup
        alice.stop().join();
        bob.stop().join();
    }

    @Test
    @Timeout(5)
    void testRequestFrom() throws Exception {
        // Given: Two agents with Bob configured to reply
        TestAgent alice = new TestAgent("alice");
        TestAgent bob = new TestAgent("bob") {
            @Override
            protected void onDirectMessage(Message message) {
                // Bob automatically replies
                replyTo(message, "Response from Bob: " + message.content());
            }
        };
        setupAgent(alice);
        setupAgent(bob);

        alice.start().join();
        bob.start().join();

        // When: Alice requests from Bob
        String question = "What's your status?";
        Message response = alice.requestFrom("bob", question).get(2, TimeUnit.SECONDS);

        // Then: Alice should receive Bob's response
        assertNotNull(response);
        assertEquals("bob", response.senderId());
        assertEquals("alice", response.receiverId());
        assertEquals("Response from Bob: " + question, response.content());
        assertNotNull(response.correlationId(), "Response should have correlation ID");

        // Cleanup
        alice.stop().join();
        bob.stop().join();
    }

    @Test
    @Timeout(5)
    void testRequestFromTimeout() throws Exception {
        // Given: Alice and Bob (Bob doesn't reply)
        TestAgent alice = new TestAgent("alice");
        TestAgent bob = new TestAgent("bob"); // No reply handler
        setupAgent(alice);
        setupAgent(bob);

        alice.start().join();
        bob.start().join();

        // When: Alice requests with short timeout
        CompletableFuture<Message> responseFuture = alice.requestFrom("bob", "Question", 500);

        // Then: Should timeout
        ExecutionException exception = assertThrows(ExecutionException.class, () -> {
            responseFuture.get(1, TimeUnit.SECONDS);
        });

        assertTrue(exception.getCause() instanceof TimeoutException,
                  "Should throw TimeoutException");

        // Cleanup
        alice.stop().join();
        bob.stop().join();
    }

    @Test
    @Timeout(5)
    void testReplyTo() throws Exception {
        // Given: Two agents
        TestAgent alice = new TestAgent("alice");
        TestAgent bob = new TestAgent("bob");
        setupAgent(alice);
        setupAgent(bob);

        alice.start().join();
        bob.start().join();

        CompletableFuture<Message> aliceReceived = new CompletableFuture<>();
        alice.setDirectMessageHandler(aliceReceived::complete);

        // When: Alice sends message and Bob replies
        CompletableFuture<Message> bobReceived = new CompletableFuture<>();
        bob.setDirectMessageHandler(msg -> {
            bobReceived.complete(msg);
            bob.replyTo(msg, "Reply to: " + msg.content());
        });

        Message originalMessage = Message.builder()
            .senderId("alice")
            .receiverId("bob")
            .content("Original message")
            .build();

        messageDispatcher.sendTo(originalMessage).join();

        // Wait for Bob to receive and reply
        Message bobGot = bobReceived.get(2, TimeUnit.SECONDS);
        assertNotNull(bobGot);

        // Then: Alice should receive the reply with correlation ID
        Message reply = aliceReceived.get(2, TimeUnit.SECONDS);
        assertNotNull(reply);
        assertEquals("bob", reply.senderId());
        assertEquals("alice", reply.receiverId());
        assertEquals("Reply to: Original message", reply.content());
        assertEquals(originalMessage.id(), reply.correlationId(),
                    "Reply should have correlation ID matching original message");

        // Cleanup
        alice.stop().join();
        bob.stop().join();
    }

    @Test
    @Timeout(5)
    void testOnDirectMessageHook() throws Exception {
        // Given: Agent with custom onDirectMessage implementation
        AtomicReference<Message> receivedMessage = new AtomicReference<>();
        AtomicBoolean hookCalled = new AtomicBoolean(false);

        TestAgent agent = new TestAgent("test-agent") {
            @Override
            protected void onDirectMessage(Message message) {
                hookCalled.set(true);
                receivedMessage.set(message);
            }
        };

        setupAgent(agent);
        agent.start().join();

        // When: Message is sent to agent
        Message testMsg = Message.builder()
            .senderId("external")
            .receiverId("test-agent")
            .content("Test content")
            .build();

        messageDispatcher.sendTo(testMsg).join();

        // Wait for message to be processed
        Thread.sleep(200);

        // Then: Hook should be called with the message
        assertTrue(hookCalled.get(), "onDirectMessage hook should be called");
        assertNotNull(receivedMessage.get());
        assertEquals("external", receivedMessage.get().senderId());
        assertEquals("Test content", receivedMessage.get().content());

        // Cleanup
        agent.stop().join();
    }

    @Test
    @Timeout(5)
    void testMultipleDirectMessages() throws Exception {
        // Given: Two agents
        TestAgent alice = new TestAgent("alice");
        TestAgent bob = new TestAgent("bob");
        setupAgent(alice);
        setupAgent(bob);

        alice.start().join();
        bob.start().join();

        CountDownLatch bobReceived = new CountDownLatch(3);
        CopyOnWriteArrayList<String> receivedMessages = new CopyOnWriteArrayList<>();

        bob.setDirectMessageHandler(msg -> {
            receivedMessages.add((String) msg.content());
            bobReceived.countDown();
        });

        // When: Alice sends multiple messages to Bob
        alice.sendTo("bob", "Message 1").join();
        alice.sendTo("bob", "Message 2").join();
        alice.sendTo("bob", "Message 3").join();

        // Then: Bob should receive all messages
        assertTrue(bobReceived.await(2, TimeUnit.SECONDS),
                  "Bob should receive all 3 messages");
        assertEquals(3, receivedMessages.size());
        assertTrue(receivedMessages.contains("Message 1"));
        assertTrue(receivedMessages.contains("Message 2"));
        assertTrue(receivedMessages.contains("Message 3"));

        // Cleanup
        alice.stop().join();
        bob.stop().join();
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private void setupAgent(TestAgent agent) {
        agent.setMessageDispatcher(messageDispatcher);
        agent.setAgentDirectory(agentDirectory);
        agent.setBehaviorScheduler(behaviorScheduler);
    }

    // =========================================================================
    // Test Agent Class
    // =========================================================================

    /**
     * Test agent with configurable direct message handler
     */
    static class TestAgent extends BaseAgent {

        private volatile MessageHandler directMessageHandler;

        public TestAgent(String agentId) {
            super(agentId, agentId);
        }

        public void setDirectMessageHandler(MessageHandler handler) {
            this.directMessageHandler = handler;
        }

        public void setDirectMessageHandler(java.util.function.Consumer<Message> handler) {
            this.directMessageHandler = MessageHandler.sync(handler::accept);
        }

        @Override
        protected void onDirectMessage(Message message) {
            if (directMessageHandler != null) {
                try {
                    directMessageHandler.handle(message).join();
                } catch (Exception e) {
                    log.error("Error in test message handler", e);
                }
            }
        }
    }
}
