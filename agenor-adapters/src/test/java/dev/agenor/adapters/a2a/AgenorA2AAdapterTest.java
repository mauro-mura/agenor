package dev.agenor.adapters.a2a;

import dev.agenor.core.AgentDescriptor;
import dev.agenor.core.AgentEndpoint;
import dev.agenor.core.directory.AgentDirectory;
import dev.agenor.core.Message;
import dev.agenor.core.MessageHandler;
import dev.agenor.core.messaging.Subscription;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.core.messaging.LocalEndpointProvider;
import dev.agenor.core.messaging.MessageDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Tests for AgenorA2AAdapter.
 */
@ExtendWith(MockitoExtension.class)
class AgenorA2AAdapterTest {

    @Mock
    private MessageDispatcher messageDispatcher;

    @Mock
    private AgentDirectory agentDirectory;

    private AgenorA2AAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new AgenorA2AAdapter(
            messageDispatcher,
            agentDirectory,
            "local-agent",
            Duration.ofSeconds(30)
        );
    }

    @Test
    void shouldIdentifyInternalAgent() {
        // Given
        AgentDescriptor internal = AgentDescriptor.builder("internal-agent").build();

        when(agentDirectory.findById("internal-agent"))
            .thenReturn(CompletableFuture.completedFuture(Optional.of(internal)));
        when(agentDirectory.findById("unknown-agent"))
            .thenReturn(CompletableFuture.completedFuture(Optional.empty()));

        // Then
        assertThat(adapter.isInternalAgent("internal-agent")).isTrue();
        assertThat(adapter.isInternalAgent("unknown-agent")).isFalse();
        assertThat(adapter.isInternalAgent(null)).isFalse();
    }

    @Test
    void shouldIdentifyExternalA2AUrl() {
        assertThat(adapter.isExternalA2AUrl("https://agent.example.com")).isTrue();
        assertThat(adapter.isExternalA2AUrl("http://localhost:8080")).isTrue();
        assertThat(adapter.isExternalA2AUrl("internal-agent")).isFalse();
        assertThat(adapter.isExternalA2AUrl(null)).isFalse();
    }

    @Test
    void shouldRouteToInternalAgent() throws Exception {
        // Given
        AgentDescriptor internal = AgentDescriptor.builder("internal-agent").build();
        when(agentDirectory.findById("internal-agent"))
            .thenReturn(CompletableFuture.completedFuture(Optional.of(internal)));

        Message responseMsg = Message.builder()
            .id("resp-1")
            .senderId("internal-agent")
            .receiverId("local-agent")
            .content("Done")
            .header("conversationId", "conv-1")
            .header("performative", "INFORM")
            .build();

        stubDispatcherToReplyImmediately(responseMsg);

        DialogueMessage request = DialogueMessage.builder()
            .conversationId("conv-1")
            .senderId("local-agent")
            .receiverId("internal-agent")
            .performative(Performative.REQUEST)
            .content("Do task")
            .build();

        // When
        DialogueMessage response = adapter.send(request).get(5, TimeUnit.SECONDS);

        // Then
        assertThat(response.performative()).isEqualTo(Performative.INFORM);
        assertThat(response.content()).isEqualTo("Done");
        verify(messageDispatcher).subscribeRecipient(eq("local-agent"), any());
        verify(messageDispatcher).sendTo(argThat(msg -> "internal-agent".equals(msg.receiverId())));
    }

    @Test
    void shouldFailForUnknownAgent() {
        // Given - agent not registered and not a URL
        when(agentDirectory.findById("unknown"))
            .thenReturn(CompletableFuture.completedFuture(Optional.empty()));

        DialogueMessage request = DialogueMessage.builder()
            .receiverId("unknown")
            .senderId("unknown-sender")
            .performative(Performative.REQUEST)
            .content("Test")
            .build();

        // When/Then
        assertThatThrownBy(() -> adapter.send(request).get(5, TimeUnit.SECONDS))
            .hasCauseInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown agent");
    }

    @Test
    void shouldRecognizeHttpUrls() {
        // http and https should be recognized as external URLs
        assertThat(adapter.isExternalA2AUrl("http://agent.local")).isTrue();
        assertThat(adapter.isExternalA2AUrl("https://agent.cloud")).isTrue();
        assertThat(adapter.isExternalA2AUrl("ftp://invalid")).isFalse();
    }

    @Test
    void shouldPrioritizeInternalOverUrl() throws Exception {
        // Given - URL-like agent registered internally
        AgentDescriptor internal = AgentDescriptor.builder("https://local-agent.com").build();
        when(agentDirectory.findById("https://local-agent.com"))
            .thenReturn(CompletableFuture.completedFuture(Optional.of(internal)));

        Message responseMsg = Message.builder()
            .senderId("https://local-agent.com")
            .receiverId("local-agent")
            .header("performative", "INFORM")
            .build();

        stubDispatcherToReplyImmediately(responseMsg);

        DialogueMessage request = DialogueMessage.builder()
            .senderId("local-agent")
            .receiverId("https://local-agent.com")
            .performative(Performative.REQUEST)
            .build();

        // When
        adapter.send(request).get(5, TimeUnit.SECONDS);

        // Then - should route internally, not externally
        verify(messageDispatcher).sendTo(argThat(msg -> "https://local-agent.com".equals(msg.receiverId())));
    }

    @Test
    void shouldReturnLocalAgentId() {
        assertThat(adapter.getLocalAgentId()).isEqualTo("local-agent");
    }

    @Test
    void shouldClearCache() {
        // Just verify no exception
        adapter.clearCache();
    }

    @Test
    void shouldProvideExternalClient() {
        assertThat(adapter.getExternalClient()).isNotNull();
    }

    // -----------------------------------------------------------------------
    // sendWithStreaming - non-external URL routes to send()
    // -----------------------------------------------------------------------

    @Test
    void sendWithStreaming_internalAgent_shouldRouteToSend() throws Exception {
        // Given
        AgentDescriptor internal = AgentDescriptor.builder("internal-agent").build();
        when(agentDirectory.findById("internal-agent"))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(internal)));

        Message responseMsg = Message.builder()
                .id("resp-1")
                .senderId("internal-agent")
                .receiverId("local-agent")
                .content("Done")
                .header("conversationId", "conv-stream")
                .header("performative", "INFORM")
                .build();

        stubDispatcherToReplyImmediately(responseMsg);

        DialogueMessage request = DialogueMessage.builder()
                .conversationId("conv-stream")
                .senderId("local-agent")
                .receiverId("internal-agent")
                .performative(Performative.REQUEST)
                .content("stream task")
                .build();

        // When - internal agent → sendWithStreaming falls through to send()
        DialogueMessage response = adapter.sendWithStreaming(request, (state, msg) -> {})
                .get(5, TimeUnit.SECONDS);

        // Then
        assertThat(response.performative()).isEqualTo(Performative.INFORM);
    }

    @Test
    void sendWithStreaming_unknownAgent_shouldFail() {
        // Given - not internal, not URL → send() will fail
        when(agentDirectory.findById("unknown"))
                .thenReturn(CompletableFuture.completedFuture(Optional.empty()));

        DialogueMessage request = DialogueMessage.builder()
                .receiverId("unknown")
                .senderId("local-agent")
                .performative(Performative.REQUEST)
                .content("task")
                .build();

        // sendWithStreaming routes to send() which fails for unknown
        CompletableFuture<DialogueMessage> future = adapter.sendWithStreaming(request, null);

        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sendWithStreaming_externalUrl_shouldAttemptExternalCall() {
        // External URL → goes to externalClient.sendWithStreaming which fails for invalid URL
        DialogueMessage request = DialogueMessage.builder()
                .receiverId("http://invalid-a2a-agent.test")
                .senderId("local-agent")
                .performative(Performative.REQUEST)
                .content("task")
                .build();

        CompletableFuture<DialogueMessage> future = adapter.sendWithStreaming(request, (state, msg) -> {});

        // Should fail but not throw synchronously
        assertThat(future).isNotNull();
        assertThatThrownBy(() -> future.get(3, TimeUnit.SECONDS))
                .isInstanceOf(Exception.class);
    }

    // -----------------------------------------------------------------------
    // getExternalAgentCard - invalid URL fails
    // -----------------------------------------------------------------------

    @Test
    void getExternalAgentCard_withInvalidUrl_shouldFail() {
        CompletableFuture<?> future = adapter.getExternalAgentCard("http://invalid-agent-url.test");

        assertThat(future).isNotNull();
        assertThatThrownBy(() -> future.get(3, TimeUnit.SECONDS))
                .isInstanceOf(Exception.class);
    }

    // -----------------------------------------------------------------------
    // Cache clear is idempotent
    // -----------------------------------------------------------------------

    @Test
    void clearCache_shouldBeIdempotent() {
        adapter.clearCache();
        adapter.clearCache();
        // No exception → pass
    }

    // -----------------------------------------------------------------------
    // validateExternalAgent - fails for invalid URL
    // -----------------------------------------------------------------------

    @Test
    void validateExternalAgent_withInvalidUrl_shouldReturnFalse() throws Exception {
        CompletableFuture<Boolean> result = adapter.validateExternalAgent("http://not-a-real-agent.test");

        assertThat(result.get(3, TimeUnit.SECONDS)).isFalse();
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    @Test
    void shouldReturnExternalClient() {
        assertThat(adapter.getExternalClient()).isNotNull();
    }

    // -----------------------------------------------------------------------
    // sendInternal directly
    // -----------------------------------------------------------------------

    @Test
    void sendInternal_shouldResolveOnFinalInformAfterAgree() throws Exception {
        // Given - responder sends an intermediate AGREE, then the final INFORM
        Message agreeMsg = Message.builder()
                .id("agree-1")
                .senderId("agent-a")
                .receiverId("local-agent")
                .header("conversationId", "c-1")
                .header("performative", "AGREE")
                .build();
        Message informMsg = Message.builder()
                .id("inform-1")
                .senderId("agent-a")
                .receiverId("local-agent")
                .content("done")
                .header("conversationId", "c-1")
                .header("performative", "INFORM")
                .build();

        stubDispatcherToReplyImmediately(agreeMsg, informMsg);

        DialogueMessage request = DialogueMessage.builder()
                .conversationId("c-1")
                .senderId("local-agent")
                .receiverId("agent-a")
                .performative(Performative.REQUEST)
                .build();

        // When
        DialogueMessage response = adapter.sendInternal(request).get(5, TimeUnit.SECONDS);

        // Then - the future resolves on the final INFORM, not the intermediate AGREE
        assertThat(response.performative()).isEqualTo(Performative.INFORM);
        assertThat(response.content()).isEqualTo("done");
        verify(messageDispatcher).subscribeRecipient(eq("local-agent"), any());
        verify(messageDispatcher).sendTo(argThat(msg -> "agent-a".equals(msg.receiverId())));
    }

    @Test
    void sendInternal_shouldResolveImmediatelyOnRefuse() throws Exception {
        // Given - non-regression: a direct REFUSE (no AGREE) still resolves immediately
        Message refuseMsg = Message.builder()
                .id("refuse-1")
                .senderId("agent-a")
                .receiverId("local-agent")
                .header("conversationId", "c-1")
                .header("performative", "REFUSE")
                .build();

        stubDispatcherToReplyImmediately(refuseMsg);

        DialogueMessage request = DialogueMessage.builder()
                .conversationId("c-1")
                .senderId("local-agent")
                .receiverId("agent-a")
                .performative(Performative.REQUEST)
                .build();

        // When
        DialogueMessage response = adapter.sendInternal(request).get(5, TimeUnit.SECONDS);

        // Then
        assertThat(response.performative()).isEqualTo(Performative.REFUSE);
    }

    // -----------------------------------------------------------------------
    // sendInternal across nodes
    // -----------------------------------------------------------------------

    @Test
    void sendInternal_registersAnUnknownSenderBeforeSendingAndUnregistersItAfter() throws Exception {
        var endpoint = new AgentEndpoint("node-1", "redis", Map.of());
        MessageDispatcher networked = networkedDispatcher(endpoint);
        adapter = new AgenorA2AAdapter(networked, agentDirectory, "local-agent", Duration.ofSeconds(30));
        when(agentDirectory.findById("client-agent"))
                .thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        when(agentDirectory.register(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(agentDirectory.unregister(anyString())).thenReturn(CompletableFuture.completedFuture(null));
        replyOnSend(networked, informFrom("agent-a", "client-agent"));

        DialogueMessage response = adapter.sendInternal(request("client-agent")).get(5, TimeUnit.SECONDS);

        assertThat(response.performative()).isEqualTo(Performative.INFORM);
        var order = inOrder(agentDirectory, networked);
        ArgumentCaptor<AgentDescriptor> registered = ArgumentCaptor.forClass(AgentDescriptor.class);
        order.verify(agentDirectory).register(registered.capture());
        order.verify(networked).sendTo(any(Message.class));
        order.verify(agentDirectory, timeout(1000)).unregister("client-agent");
        assertThat(registered.getValue().agentId()).isEqualTo("client-agent");
        assertThat(registered.getValue().endpoint()).isEqualTo(endpoint);
    }

    @Test
    void sendInternal_leavesARegisteredSenderAlone() throws Exception {
        MessageDispatcher networked = networkedDispatcher(new AgentEndpoint("node-1", "redis", Map.of()));
        adapter = new AgenorA2AAdapter(networked, agentDirectory, "local-agent", Duration.ofSeconds(30));
        when(agentDirectory.findById("real-agent")).thenReturn(CompletableFuture.completedFuture(
                Optional.of(AgentDescriptor.builder("real-agent").build())));
        replyOnSend(networked, informFrom("agent-a", "real-agent"));

        adapter.sendInternal(request("real-agent")).get(5, TimeUnit.SECONDS);

        // Registering over a real agent's row, then unregistering it, would delete the agent
        verify(agentDirectory, never()).register(any());
        verify(agentDirectory, never()).unregister(anyString());
    }

    @Test
    void sendInternal_failsAtOnceWhenTheRequestCannotBeSent() {
        when(messageDispatcher.subscribeRecipient(anyString(), any(MessageHandler.class)))
                .thenReturn(mock(Subscription.class));
        when(messageDispatcher.sendTo(any(Message.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("no route")));

        // The adapter's timeout is 30 s: waiting it out is the defect
        assertThatThrownBy(() -> adapter.sendInternal(request("local-agent")).get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseMessage("no route");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** A dispatcher that, like Redis, tells the directory how this node is reached. */
    private static MessageDispatcher networkedDispatcher(AgentEndpoint endpoint) {
        MessageDispatcher dispatcher = mock(MessageDispatcher.class,
                withSettings().extraInterfaces(LocalEndpointProvider.class));
        when(((LocalEndpointProvider) dispatcher).localEndpoint()).thenReturn(Optional.of(endpoint));
        return dispatcher;
    }

    private static DialogueMessage request(String senderId) {
        return DialogueMessage.builder()
                .conversationId("c-1")
                .senderId(senderId)
                .receiverId("agent-a")
                .performative(Performative.REQUEST)
                .build();
    }

    private static Message informFrom(String sender, String receiver) {
        return DialogueMessage.builder()
                .conversationId("c-1")
                .senderId(sender)
                .receiverId(receiver)
                .performative(Performative.INFORM)
                .content("done")
                .build()
                .toMessage();
    }

    /** Delivers {@code reply} to the reply subscription when the request is sent, not before. */
    private static void replyOnSend(MessageDispatcher dispatcher, Message reply) {
        AtomicReference<MessageHandler> handler = new AtomicReference<>();
        when(dispatcher.subscribeRecipient(anyString(), any(MessageHandler.class))).thenAnswer(inv -> {
            handler.set(inv.getArgument(1));
            return mock(Subscription.class);
        });
        when(dispatcher.sendTo(any(Message.class))).thenAnswer(inv -> {
            handler.get().handle(reply);
            return CompletableFuture.completedFuture(null);
        });
    }

    /**
     * Stubs the dispatcher to immediately invoke the MessageHandler registered via
     * subscribeRecipient with each given message in order, simulating synchronous message
     * delivery for test purposes (e.g. an intermediate AGREE followed by the final INFORM).
     */
    private void stubDispatcherToReplyImmediately(Message... replyMsgs) throws Exception {
        Subscription mockSub = mock(Subscription.class);
        Answer<Subscription> triggerHandler = invocation -> {
            MessageHandler handler = invocation.getArgument(1);
            for (Message replyMsg : replyMsgs) {
                handler.handle(replyMsg);
            }
            return mockSub;
        };
        when(messageDispatcher.subscribeRecipient(anyString(), any(MessageHandler.class)))
                .thenAnswer(triggerHandler);
        when(messageDispatcher.sendTo(any(Message.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
    }
}
