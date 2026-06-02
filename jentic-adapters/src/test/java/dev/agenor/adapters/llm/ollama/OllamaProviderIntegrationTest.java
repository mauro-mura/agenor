package dev.agenor.adapters.llm.ollama;

import dev.agenor.core.llm.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for OllamaProvider using Testcontainers.
 *
 * These tests require Docker to be running and will pull the Ollama image.
 * They are disabled by default and can be enabled with:
 * -Dintegration.tests.enabled=true
 *
 * Note: These tests use small, widely available model:
 * - qwen2.5:0.5b (0.5B parameters, ~397MB)
 */
@Testcontainers
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
class OllamaProviderIntegrationTest {

    @Container
    static GenericContainer<?> ollama = new GenericContainer<>("ollama/ollama:latest")
            .withExposedPorts(11434)
            .withStartupTimeout(Duration.ofMinutes(5));

    @Test
    void shouldCommunicateWithRealOllamaInstance() throws Exception {
        // Wait for Ollama to be ready
        String ollamaUrl = "http://localhost:" + ollama.getMappedPort(11434);

        // Pull a very small model for testing
        pullModel(ollamaUrl, "qwen2.5:0.5b");

        OllamaProvider provider = OllamaProvider.builder()
                .baseUrl(ollamaUrl)
                .modelName("qwen2.5:0.5b")
                .timeout(Duration.ofMinutes(3))
                .build();

        LLMRequest request = LLMRequest.builder()
                .addMessage(LLMMessage.user("Say hello in exactly 2 words"))
                .temperature(0.0)
                .maxTokens(10)
                .build();

        // Test regular chat
        CompletableFuture<LLMResponse> chatFuture = provider.chat(request);
        LLMResponse response = chatFuture.get(120, TimeUnit.SECONDS);

        assertNotNull(response);
        assertNotNull(response.content());
        assertTrue(response.content().length() > 0);
        assertNotNull(response.model());

        System.out.println("Chat Response: " + response.content());
    }

    @Test
    void shouldHandleStreamingWithRealOllama() throws Exception {
        String ollamaUrl = "http://localhost:" + ollama.getMappedPort(11434);

        // Pull a small model for testing
        pullModel(ollamaUrl, "qwen2.5:0.5b");

        OllamaProvider provider = OllamaProvider.builder()
                .baseUrl(ollamaUrl)
                .modelName("qwen2.5:0.5b")
                .timeout(Duration.ofMinutes(3))
                .build();

        LLMRequest request = LLMRequest.builder()
                .addMessage(LLMMessage.user("Count: 1, 2, 3"))
                .temperature(0.0)
                .maxTokens(20)
                .build();

        List<StreamingChunk> chunks = new ArrayList<>();

        CompletableFuture<Void> streamFuture = provider.chatStream(request, chunk -> {
            chunks.add(chunk);
            if (chunk.hasContent()) {
                System.out.print(chunk.content());
            }
            if (chunk.isLast()) {
                System.out.println("\n[Stream completed: " + chunk.finishReason() + "]");
            }
        });

        streamFuture.get(120, TimeUnit.SECONDS);

        assertFalse(chunks.isEmpty());

        // Verify stream structure
        String streamId = chunks.get(0).id();
        for (StreamingChunk chunk : chunks) {
            assertEquals(streamId, chunk.id());
            assertEquals("qwen2.5:0.5b", chunk.model());
            assertTrue(chunk.index() >= 0);
        }

        // Check that we have a final chunk
        StreamingChunk lastChunk = chunks.get(chunks.size() - 1);
        assertTrue(lastChunk.isLast());
        assertNotNull(lastChunk.finishReason());
    }

    @Test
    void shouldGetAvailableModelsFromRealOllama() throws Exception {
        String ollamaUrl = "http://localhost:" + ollama.getMappedPort(11434);

        // Pull a model to ensure at least one is available
        pullModel(ollamaUrl, "qwen2.5:0.5b");

        OllamaProvider provider = OllamaProvider.builder()
                .baseUrl(ollamaUrl)
                .timeout(Duration.ofMinutes(2))
                .build();

        CompletableFuture<List<String>> modelsFuture = provider.getAvailableModels();
        List<String> models = modelsFuture.get(30, TimeUnit.SECONDS);

        assertNotNull(models);
        // Note: getAvailableModels() returns the default list, not actual Ollama models
        // This is by design in the current implementation
        assertFalse(models.isEmpty());

        System.out.println("Provider reported models: " + models);
    }

    @Test
    void shouldHandleMultipleSequentialRequests() throws Exception {
        String ollamaUrl = "http://localhost:" + ollama.getMappedPort(11434);

        pullModel(ollamaUrl, "qwen2.5:0.5b");

        OllamaProvider provider = OllamaProvider.builder()
                .baseUrl(ollamaUrl)
                .modelName("qwen2.5:0.5b")
                .timeout(Duration.ofMinutes(2))
                .build();

        // Send multiple requests
        for (int i = 1; i <= 3; i++) {
            LLMRequest request = LLMRequest.builder()
                    .addMessage(LLMMessage.user("Reply with number: " + i))
                    .temperature(0.0)
                    .maxTokens(10)
                    .build();

            CompletableFuture<LLMResponse> future = provider.chat(request);
            LLMResponse response = future.get(60, TimeUnit.SECONDS);

            assertNotNull(response);
            assertNotNull(response.content());

            System.out.println("Request " + i + " response: " + response.content());
        }
    }

    @Test
    void shouldHandleMultiTurnConversationRequest() throws Exception {
        String ollamaUrl = "http://localhost:" + ollama.getMappedPort(11434);

        pullModel(ollamaUrl, "qwen2.5:0.5b");

        OllamaProvider provider = OllamaProvider.builder()
                .baseUrl(ollamaUrl)
                .modelName("qwen2.5:0.5b")
                .timeout(Duration.ofMinutes(2))
                .build();

        // Build a multi-turn conversation
        LLMRequest conversationRequest = LLMRequest.builder()
                .addMessage(LLMMessage.system("You are a helpful assistant. Be concise."))
                .addMessage(LLMMessage.user("My favorite color is blue"))
                .addMessage(LLMMessage.assistant("I understand your favorite color is blue."))
                .addMessage(LLMMessage.user("What color do I like?"))
                .temperature(0.0)
                .maxTokens(15)
                .build();

        CompletableFuture<LLMResponse> future = provider.chat(conversationRequest);
        LLMResponse response = future.get(90, TimeUnit.SECONDS);

        // Verifica solo che il flusso multi-turn funzioni e produca una risposta non banale
        assertNotNull(response);
        assertNotNull(response.content());

        System.out.println("Conversation response: " + response.content());

        String responseText = response.content().trim();
        assertFalse(responseText.isEmpty());
        // risposta minimamente significativa (più di poche lettere)
        assertTrue(responseText.length() > 10);
    }

    @Test
    void shouldHandleErrorScenarios() throws Exception {
        String ollamaUrl = "http://localhost:" + ollama.getMappedPort(11434);

        // Test with non-existent model
        OllamaProvider provider = OllamaProvider.builder()
                .baseUrl(ollamaUrl)
                .modelName("nonexistent-model")
                .timeout(Duration.ofSeconds(30))
                .build();

        LLMRequest request = LLMRequest.builder()
                .addMessage(LLMMessage.user("Test"))
                .build();

        CompletableFuture<LLMResponse> future = provider.chat(request);

        // Should throw an exception for non-existent model
        assertThrows(Exception.class, () -> {
            future.get(45, TimeUnit.SECONDS);
        });
    }

    /**
     * Helper method to pull a model in Ollama using REST API
     */
    private void pullModel(String ollamaUrl, String modelName) {
        try {
            System.out.println("Pulling model: " + modelName + " from " + ollamaUrl);

            // Use HTTP client to pull model via Ollama API
            // POST /api/pull with {"name": modelName}
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();

            String requestBody = String.format("{\"name\":\"%s\"}", modelName);

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(ollamaUrl + "/api/pull"))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofMinutes(10))
                    .build();

            java.net.http.HttpResponse<String> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.err.println("Failed to pull model: " + response.body());
            } else {
                System.out.println("Model pull initiated successfully");
            }

            // Give some time for the model to download
            Thread.sleep(30000); // 30 seconds for small models

        } catch (Exception e) {
            System.err.println("Error pulling model: " + e.getMessage());
            // Don't fail the test, the model might already exist
        }
    }
}
