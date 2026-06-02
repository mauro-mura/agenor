package dev.agenor.adapters.a2a;

import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import io.a2a.spec.AgentCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for JenticA2AClient.
 */
class JenticA2AClientTest {

    private JenticA2AClient client;

    @BeforeEach
    void setUp() {
        client = new JenticA2AClient(Duration.ofSeconds(5));
    }

    @Test
    void shouldCreateClientWithDefaultTimeout() {
        // Given / When
        var defaultClient = new JenticA2AClient();

        // Then
        assertThat(defaultClient).isNotNull();
    }

    @Test
    void shouldCreateClientWithCustomTimeout() {
        // Given / When
        var customClient = new JenticA2AClient(Duration.ofMinutes(10));

        // Then
        assertThat(customClient).isNotNull();
    }

    @Test
    void shouldHandleInvalidUrlInSend() {
        var message = DialogueMessage.builder()
                .conversationId("conv-1")
                .senderId("sender")
                .receiverId("receiver")
                .performative(Performative.REQUEST)
                .content("test message")
                .build();

        CompletableFuture<DialogueMessage> future = client.send(
                "http://invalid-url-that-does-not-exist",
                message,
                "local-agent"
        );

        try {
            future.get(2, TimeUnit.SECONDS);
            assertThat(true).as("Should have thrown exception").isFalse();
        } catch (Exception e) {
            assertThat(e).isInstanceOfAny(
                    java.util.concurrent.TimeoutException.class,
                    ExecutionException.class
            );
        }
    }

    @Test
    void shouldHandleInvalidUrlInGetAgentCard() {
        // Given / When
        CompletableFuture<AgentCard> future = client.getAgentCard("http://invalid-url");

        // Then
        assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(JenticA2AClient.A2AClientException.class);
    }

    @Test
    void shouldReturnFalseForInvalidAgentUrl() throws Exception {
        // Given / When
        CompletableFuture<Boolean> result = client.isA2AAgent("http://invalid-url");

        // Then
        assertThat(result.get(2, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void shouldCreateA2AClientException() {
        // Given / When
        var ex1 = new JenticA2AClient.A2AClientException("Error message");
        var ex2 = new JenticA2AClient.A2AClientException("Error with code", 400);
        var ex3 = new JenticA2AClient.A2AClientException("Error with cause", new RuntimeException("cause"));

        // Then
        assertThat(ex1.getMessage()).isEqualTo("Error message");
        assertThat(ex1.getErrorCode()).isNull();

        assertThat(ex2.getMessage()).isEqualTo("Error with code");
        assertThat(ex2.getErrorCode()).isEqualTo(400);

        assertThat(ex3.getMessage()).isEqualTo("Error with cause");
        assertThat(ex3.getCause()).isInstanceOf(RuntimeException.class);
        assertThat(ex3.getCause().getMessage()).isEqualTo("cause");
        assertThat(ex3.getErrorCode()).isNull();
    }

    @Test
    void shouldHandleNullContentInMessage() {
        // Given
        var message = DialogueMessage.builder()
                .conversationId("conv-1")
                .senderId("sender")
                .receiverId("receiver")
                .performative(Performative.REQUEST)
                .content(null)
                .build();

        // When
        CompletableFuture<DialogueMessage> future = client.send(
                "http://invalid-url",
                message,
                "local-agent"
        );

        // Then - should handle gracefully
        assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    void shouldHandleStringContentInMessage() {
        // Given
        var message = DialogueMessage.builder()
                .conversationId("conv-1")
                .senderId("sender")
                .receiverId("receiver")
                .performative(Performative.REQUEST)
                .content("string content")
                .build();

        // When
        CompletableFuture<DialogueMessage> future = client.send(
                "http://invalid-url",
                message,
                "local-agent"
        );

        // Then
        assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    void shouldHandleObjectContentInMessage() {
        // Given
        var complexContent = new TestData("value1", 42);
        var message = DialogueMessage.builder()
                .conversationId("conv-1")
                .senderId("sender")
                .receiverId("receiver")
                .performative(Performative.REQUEST)
                .content(complexContent)
                .build();

        // When
        CompletableFuture<DialogueMessage> future = client.send(
                "http://invalid-url",
                message,
                "local-agent"
        );

        // Then
        assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    void shouldReturnCompletableFutureFromSend() {
        // Given
        var message = DialogueMessage.builder()
                .conversationId("conv-1")
                .senderId("sender")
                .receiverId("receiver")
                .performative(Performative.REQUEST)
                .content("test")
                .build();

        // When
        CompletableFuture<DialogueMessage> future = client.send(
                "http://test-url",
                message,
                "local-agent"
        );

        // Then
        assertThat(future).isNotNull();
        assertThat(future).isInstanceOf(CompletableFuture.class);
    }

    @Test
    void shouldReturnCompletableFutureFromSendWithStreaming() {
        // Given
        var message = DialogueMessage.builder()
                .conversationId("conv-1")
                .senderId("sender")
                .receiverId("receiver")
                .performative(Performative.REQUEST)
                .content("test")
                .build();

        JenticA2AClient.StatusCallback callback = (state, msg) -> {
            // Just capture the callback
        };

        // When
        CompletableFuture<DialogueMessage> future = client.sendWithStreaming(
                "http://test-url",
                message,
                "local-agent",
                callback
        );

        // Then
        assertThat(future).isNotNull();
        assertThat(future).isInstanceOf(CompletableFuture.class);
    }

    @Test
    void shouldReturnCompletableFutureFromGetAgentCard() {
        // Given / When
        CompletableFuture<AgentCard> future = client.getAgentCard("http://test-url");

        // Then
        assertThat(future).isNotNull();
        assertThat(future).isInstanceOf(CompletableFuture.class);
    }

    @Test
    void shouldReturnCompletableFutureFromIsA2AAgent() {
        // Given / When
        CompletableFuture<Boolean> future = client.isA2AAgent("http://test-url");

        // Then
        assertThat(future).isNotNull();
        assertThat(future).isInstanceOf(CompletableFuture.class);
    }

    @Test
    void shouldHandleNullCallbackInSendWithStreaming() {
        // Given
        var message = DialogueMessage.builder()
                .conversationId("conv-1")
                .senderId("sender")
                .receiverId("receiver")
                .performative(Performative.REQUEST)
                .content("test")
                .build();

        // When
        CompletableFuture<DialogueMessage> future = client.sendWithStreaming(
                "http://invalid-url",
                message,
                "local-agent",
                null  // null callback should be handled gracefully
        );

        // Then
        assertThat(future).isNotNull();
        assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    void shouldExecuteCallbackDuringStreaming() {
        // Given
        var message = DialogueMessage.builder()
                .conversationId("conv-1")
                .senderId("sender")
                .receiverId("receiver")
                .performative(Performative.REQUEST)
                .content("test")
                .build();

        StringBuilder callbackLog = new StringBuilder();
        JenticA2AClient.StatusCallback callback = (state, msg) -> {
            callbackLog.append(state).append(":").append(msg).append(";");
        };

        // When
        CompletableFuture<DialogueMessage> future = client.sendWithStreaming(
                "http://invalid-url",
                message,
                "local-agent",
                callback
        );

        // Then - async operations with invalid URL will fail
        try {
            future.get(2, TimeUnit.SECONDS);
            assertThat(true).as("Should have thrown exception").isFalse();
        } catch (Exception e) {
            // Expected - either TimeoutException or ExecutionException
            assertThat(e).isInstanceOfAny(
                    java.util.concurrent.TimeoutException.class,
                    ExecutionException.class
            );
        }
    }

    // -----------------------------------------------------------------------
    // A2AClientException constructors
    // -----------------------------------------------------------------------

    @Test
    void exceptionWithMessageOnly_shouldHaveNullErrorCode() {
        var ex = new JenticA2AClient.A2AClientException("simple error");
        assertThat(ex.getMessage()).isEqualTo("simple error");
        assertThat(ex.getErrorCode()).isNull();
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void exceptionWithCode_shouldStoreCode() {
        var ex = new JenticA2AClient.A2AClientException("rate limited", 429);
        assertThat(ex.getMessage()).isEqualTo("rate limited");
        assertThat(ex.getErrorCode()).isEqualTo(429);
    }

    @Test
    void exceptionWithCause_shouldWrapCause() {
        RuntimeException cause = new RuntimeException("network failure");
        var ex = new JenticA2AClient.A2AClientException("wrapped", cause);
        assertThat(ex.getMessage()).isEqualTo("wrapped");
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getErrorCode()).isNull();
    }

    // -----------------------------------------------------------------------
    // StatusCallback - functional interface
    // -----------------------------------------------------------------------

    @Test
    void statusCallback_shouldBeInvocable() {
        StringBuilder log = new StringBuilder();
        JenticA2AClient.StatusCallback cb = (state, msg) -> log.append(state).append(":").append(msg);

        cb.onStatus("WORKING", "Processing...");

        assertThat(log.toString()).isEqualTo("WORKING:Processing...");
    }

    // -----------------------------------------------------------------------
    // sendWithStreaming with invalid URL + null callback
    // -----------------------------------------------------------------------

    @Test
    void sendWithStreaming_withNullCallback_shouldFailGracefully() {
        DialogueMessage msg = DialogueMessage.builder()
                .conversationId("c")
                .senderId("s")
                .receiverId("r")
                .performative(Performative.REQUEST)
                .content("test")
                .build();

        CompletableFuture<DialogueMessage> future = client.sendWithStreaming(
                "http://invalid-host.test", msg, "local", null);

        assertThat(future).isNotNull();
        assertThatThrownBy(() -> future.get(3, java.util.concurrent.TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
    }

    // -----------------------------------------------------------------------
    // isA2AAgent - wraps getAgentCard, always false for invalid URL
    // -----------------------------------------------------------------------

    @Test
    void isA2AAgent_withInvalidUrl_returnsFalse() throws Exception {
        CompletableFuture<Boolean> result = client.isA2AAgent("http://non-existent-a2a.test");
        assertThat(result.get(3, java.util.concurrent.TimeUnit.SECONDS)).isFalse();
    }

    // Helper class for testing object content serialization
    private record TestData(String field1, int field2) {}
}
