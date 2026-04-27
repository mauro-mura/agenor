package dev.jentic.adapters.a2a;

import dev.jentic.core.AgentDescriptor;
import dev.jentic.core.AgentDirectory;
import dev.jentic.core.Message;
import dev.jentic.core.MessageHandler;
import dev.jentic.core.messaging.Subscription;
import dev.jentic.core.dialogue.DialogueMessage;
import dev.jentic.core.dialogue.Performative;
import dev.jentic.core.messaging.MessageDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for JenticA2AAdapter.
 */
@ExtendWith(MockitoExtension.class)
class JenticA2AAdapterTest {

    @Mock
    private MessageDispatcher messageDispatcher;

    @Mock
    private AgentDirectory agentDirectory;

    private JenticA2AAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new JenticA2AAdapter(
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
        verify(messageDispatcher).sendTo(eq("internal-agent"), any(Message.class));
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
        verify(messageDispatcher).sendTo(eq("https://local-agent.com"), any(Message.class));
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
    void sendInternal_shouldDelegateToMessageService() throws Exception {
        // Given
        Message responseMsg = Message.builder()
                .id("r-1")
                .senderId("agent-a")
                .receiverId("local-agent")
                .header("conversationId", "c-1")
                .header("performative", "AGREE")
                .build();

        stubDispatcherToReplyImmediately(responseMsg);

        DialogueMessage request = DialogueMessage.builder()
                .conversationId("c-1")
                .senderId("local-agent")
                .receiverId("agent-a")
                .performative(Performative.REQUEST)
                .build();

        // When
        DialogueMessage response = adapter.sendInternal(request).get(5, TimeUnit.SECONDS);

        // Then
        assertThat(response.performative()).isEqualTo(Performative.AGREE);
        verify(messageDispatcher).subscribeRecipient(eq("local-agent"), any());
        verify(messageDispatcher).sendTo(eq("agent-a"), any(Message.class));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Stubs the dispatcher to immediately invoke the MessageHandler registered via
     * subscribeRecipient, simulating synchronous message delivery for test purposes.
     */
    private void stubDispatcherToReplyImmediately(Message replyMsg) throws Exception {
        Subscription mockSub = mock(Subscription.class);
        Answer<Subscription> triggerHandler = invocation -> {
            MessageHandler handler = invocation.getArgument(1);
            handler.handle(replyMsg);
            return mockSub;
        };
        when(messageDispatcher.subscribeRecipient(anyString(), any(MessageHandler.class)))
                .thenAnswer(triggerHandler);
        when(messageDispatcher.sendTo(anyString(), any(Message.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
    }
}
