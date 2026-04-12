package dev.jentic.core.llm;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Core interface for Large Language Model providers.
 * 
 * <p>This interface provides a stable, provider-agnostic abstraction for interacting
 * with various LLM services (OpenAI, Anthropic, Ollama, etc.). It follows Jentic's
 * established pattern of placing interfaces in jentic-core and implementations
 * in jentic-adapters.
 * 
 * <p>Key design principles:
 * <ul>
 *   <li>Provider-agnostic: Works with any LLM provider</li>
 *   <li>Async-first: All operations return CompletableFuture</li>
 *   <li>Immutable models: Thread-safe request/response objects</li>
 *   <li>Function calling: Native support for tool/function calls</li>
 *   <li>Streaming: Support for streaming responses</li>
 * </ul>
 * 
 * <p>Example usage:
 * <pre>{@code
 * LLMProvider provider = new OpenAIProvider(apiKey);
 * 
 * LLMRequest request = LLMRequest.builder()
 *     .addMessage(LLMMessage.user("What is the capital of France?"))
 *     .temperature(0.7)
 *     .maxTokens(100)
 *     .build();
 * 
 * provider.chat(request)
 *     .thenAccept(response -> {
 *         System.out.println(response.content());
 *     });
 * }</pre>
 * 
 * @since 0.3.0
 * @see LLMRequest
 * @see LLMResponse
 */
public interface LLMProvider {
    
    /**
     * Send a chat completion request to the LLM.
     * 
     * <p>This is the primary method for interacting with the LLM. It sends
     * a request containing messages and configuration, and returns a response
     * asynchronously.
     * 
     * @return CompletableFuture containing the LLM response
     */
    CompletableFuture<LLMResponse> chat(LLMRequest request);
    
    /**
     * Stream a chat completion request to the LLM.
     * 
     * <p>Similar to {@link #chat(LLMRequest)}, but provides streaming updates
     * as the response is generated. This is useful for long responses where
     * you want to show incremental progress to users.
     * 
     * <p>Example usage:
     * <pre>{@code
     * provider.chatStream(request, chunk -> {
     *     System.out.print(chunk.content());
     * }).thenRun(() -> {
     *     System.out.println("\nStreaming complete");
     * });
     * }</pre>
     * 
     * @return CompletableFuture that completes when streaming is finished
     */
    CompletableFuture<Void> chatStream(LLMRequest request, Consumer<StreamingChunk> chunkHandler);
    
    /**
     * Get the list of available models from this provider.
     * 
     * <p>Returns a list of model identifiers that can be used with this provider.
     * Model names are provider-specific (e.g., "gpt-4", "claude-3-opus", "llama2").
     * 
     * @return CompletableFuture containing list of available model names
     */
    CompletableFuture<List<String>> getAvailableModels();
    
    /**
     * Get the name of this provider.
     * 
     * <p>Returns a human-readable identifier for this provider, such as
     * "OpenAI", "Anthropic", or "Ollama".
     * 
     * @return the provider name
     */
    String getProviderName();
    
    /**
     * Check if this provider supports function calling.
     * 
     * <p>Function calling (also known as tool use) allows the LLM to call
     * external functions to gather information or perform actions. Not all
     * providers or models support this feature.
     * 
     * @return true if function calling is supported, false otherwise
     */
    default boolean supportsFunctionCalling() {
        return true;
    }
    
    /**
     * Check if this provider supports streaming responses.
     * 
     * <p>Streaming allows incremental delivery of responses as they are
     * generated. Most modern LLM providers support this feature.
     * 
     * @return true if streaming is supported, false otherwise
     */
    default boolean supportsStreaming() {
        return true;
    }
    
    /**
     * Get the default model for this provider.
     * 
     * <p>Returns a reasonable default model that can be used if the user
     * doesn't specify one. This should be a balanced model that works well
     * for general purposes.
     * 
     * @return the default model name, or null if no default is available
     */
    default String getDefaultModel() {
        return null;
    }
    
    /**
     * Validate a request before sending it to the LLM.
     * 
     * <p>Performs provider-specific validation on the request. This can check
     * things like token limits, model availability, required parameters, etc.
     * 
     * @param request the request to validate
     * @throws LLMException if the request is invalid
     */
    default void validateRequest(LLMRequest request) throws LLMException {
        if (request == null) {
            throw new LLMException("Request cannot be null");
        }
        if (request.messages() == null || request.messages().isEmpty()) {
            throw new LLMException("Request must contain at least one message");
        }
        // model null is allowed — resolved at execution time by the provider's chat() implementation
    }
}
