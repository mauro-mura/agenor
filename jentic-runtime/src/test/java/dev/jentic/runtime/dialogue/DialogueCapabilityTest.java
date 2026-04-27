package dev.jentic.runtime.dialogue;

import dev.jentic.core.Agent;
import dev.jentic.core.Message;
import dev.jentic.core.MessageHandler;
import dev.jentic.core.messaging.MessageDispatcher;
import dev.jentic.core.messaging.Subscription;
import dev.jentic.core.dialogue.DialogueHandler;
import dev.jentic.core.dialogue.DialogueMessage;
import dev.jentic.core.dialogue.Performative;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DialogueCapabilityTest {
    
    private TestDialogueAgent agent;
    private MessageDispatcher messageService;
    private DialogueCapability capability;
    private MessageHandler messageHandler;

    @BeforeEach
    void setUp() {
        agent = new TestDialogueAgent("test-agent");
        messageService = mock(MessageDispatcher.class);

        var stubSub = Subscription.of("sub-1", () -> {});
        when(messageService.subscribeRecipient(eq("test-agent"), any(MessageHandler.class)))
            .thenAnswer(invocation -> {
                messageHandler = invocation.getArgument(1);
                return stubSub;
            });

        when(messageService.sendTo(any(), any(Message.class)))
            .thenReturn(CompletableFuture.completedFuture(null));

        capability = new DialogueCapability(agent);
    }

    @Test
    void shouldInitializeAndScanHandlers() {
        capability.initialize(messageService);

        verify(messageService).subscribeRecipient(eq("test-agent"), any());
    }
    
    @Test
    void shouldDispatchIncomingMessages() {
        capability.initialize(messageService);
        
        // Simulate incoming message
        var incomingMsg = Message.builder()
            .id("msg-1")
            .senderId("remote-agent")
            .receiverId("test-agent")
            .content("do task")
            .header("conversationId", "conv-1")
            .header("performative", "REQUEST")
            .build();
        
        // Handler should have been captured during initialize
        assertThat(messageHandler).isNotNull();
        messageHandler.handle(incomingMsg);
        
        assertThat(agent.lastRequest.get()).isNotNull();
        assertThat(agent.lastRequest.get().performative()).isEqualTo(Performative.REQUEST);
    }
    
    @Test
    void shouldSendRequest() {
        capability.initialize(messageService);
        
        capability.request("remote-agent", "do something");
        
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageService).sendTo(any(), captor.capture());
        
        Message sent = captor.getValue();
        assertThat(sent.receiverId()).isEqualTo("remote-agent");
        assertThat(sent.headers().get("performative")).isEqualTo("REQUEST");
    }
    
    @Test
    void shouldSendQuery() {
        capability.initialize(messageService);
        
        capability.query("remote-agent", "what time?");
        
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageService).sendTo(any(), captor.capture());
        
        Message sent = captor.getValue();
        assertThat(sent.headers().get("performative")).isEqualTo("QUERY");
    }
    
    @Test
    void shouldReplyToMessage() {
        capability.initialize(messageService);
        
        var original = DialogueMessage.builder()
            .id("msg-1")
            .conversationId("conv-1")
            .senderId("remote-agent")
            .receiverId("test-agent")
            .performative(Performative.REQUEST)
            .content("do task")
            .build();
        
        capability.agree(original);
        
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageService).sendTo(any(), captor.capture());
        
        Message reply = captor.getValue();
        assertThat(reply.receiverId()).isEqualTo("remote-agent");
        assertThat(reply.headers().get("performative")).isEqualTo("AGREE");
    }
    
    @Test
    void shouldProvideConvenienceReplyMethods() {
        capability.initialize(messageService);
        
        var original = DialogueMessage.builder()
            .senderId("remote").receiverId("test-agent")
            .performative(Performative.REQUEST).build();
        
        // Test various reply methods
        capability.refuse(original, "too busy");
        capability.inform(original, "result");
        capability.failure(original, "error");
        
        verify(messageService, times(3)).sendTo(any(), any(Message.class));
    }

    @Test
    void shouldShutdownCleanly() {
        capability.initialize(messageService);
        capability.shutdown();
        // subscription.unsubscribe() was called on the Subscription returned by subscribeRecipient
        verify(messageService).subscribeRecipient(eq("test-agent"), any());
    }
    
    @Test
    void shouldTrackActiveConversations() {
        capability.initialize(messageService);
        
        capability.request("agent-1", "task1");
        capability.request("agent-2", "task2");
        
        assertThat(capability.getActiveConversations()).hasSize(2);
    }
    
    // Test agent with dialogue handlers
    static class TestDialogueAgent implements Agent {
        private final String id;
        final AtomicReference<DialogueMessage> lastRequest = new AtomicReference<>();
        
        TestDialogueAgent(String id) {
            this.id = id;
        }
        
        @Override public String getAgentId() { return id; }
        @Override public String getAgentName() { return id; }
        @Override public boolean isRunning() { return true; }
        @Override public CompletableFuture<Void> start() { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Void> stop() { return CompletableFuture.completedFuture(null); }
        @Override public void addBehavior(dev.jentic.core.Behavior behavior) { }
        @Override public void removeBehavior(String behaviorId) { }
        @Override public dev.jentic.core.MessageService getMessageService() { return null; }
        
        @DialogueHandler(performatives = Performative.REQUEST)
        public void handleRequest(DialogueMessage msg) {
            lastRequest.set(msg);
        }
    }
}