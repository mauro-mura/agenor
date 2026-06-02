package dev.agenor.core.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable request object for LLM chat completions.
 *
 * <p>This class uses the builder pattern for flexible construction and
 * represents all parameters needed for an LLM request. It is designed to
 * work across different LLM providers with reasonable defaults.
 *
 * <p>Example usage (model inferred from provider):
 * <pre>{@code
 * LLMRequest request = LLMRequest.builder()
 *     .addMessage(LLMMessage.system("You are a helpful assistant."))
 *     .addMessage(LLMMessage.user("What is the capital of France?"))
 *     .temperature(0.7)
 *     .maxTokens(100)
 *     .build();
 * }</pre>
 *
 * <p>Explicit per-request model override:
 * <pre>{@code
 * LLMRequest request = LLMRequest.builder()
 *     .model("gpt-4o")
 *     .addMessage(LLMMessage.user("Solve this complex problem..."))
 *     .build();
 * }</pre>
 *
 * @param model the model to use, or {@code null} to defer to the provider's configured model
 * @param messages list of messages in the conversation
 * @param temperature sampling temperature (0.0 to 2.0)
 * @param maxTokens maximum tokens to generate in response
 * @param functions optional list of function definitions for tool use
 * @param functionCall optional control for specific function calling
 * @param topP nucleus sampling parameter (0.0 to 1.0)
 * @param n number of responses to generate
 * @param stop optional list of stop sequences
 * @param presencePenalty presence penalty (-2.0 to 2.0)
 * @param frequencyPenalty frequency penalty (-2.0 to 2.0)
 * @param additionalParameters provider-specific extra parameters
 *
 * @since 0.3.0
 */
public record LLMRequest(
    String model,
    List<LLMMessage> messages,
    Double temperature,
    Integer maxTokens,
    List<FunctionDefinition> functions,
    String functionCall,
    Double topP,
    Integer n,
    List<String> stop,
    Double presencePenalty,
    Double frequencyPenalty,
    Map<String, Object> additionalParameters
) {

    /**
     * Compact constructor with validation and defensive copying.
     */
    public LLMRequest {
        Objects.requireNonNull(messages, "Messages cannot be null");

        if (messages.isEmpty()) {
            throw new IllegalArgumentException("Messages cannot be empty");
        }

        // Defensive copies
        messages = List.copyOf(messages);
        if (functions != null) {
            functions = List.copyOf(functions);
        }
        if (stop != null) {
            stop = List.copyOf(stop);
        }
        if (additionalParameters != null) {
            additionalParameters = Map.copyOf(additionalParameters);
        }

        // Validation
        if (temperature != null && (temperature < 0.0 || temperature > 2.0)) {
            throw new IllegalArgumentException("Temperature must be between 0.0 and 2.0");
        }
        if (maxTokens != null && maxTokens <= 0) {
            throw new IllegalArgumentException("MaxTokens must be positive");
        }
        if (topP != null && (topP < 0.0 || topP > 1.0)) {
            throw new IllegalArgumentException("TopP must be between 0.0 and 1.0");
        }
        if (n != null && n <= 0) {
            throw new IllegalArgumentException("N must be positive");
        }
        if (presencePenalty != null && (presencePenalty < -2.0 || presencePenalty > 2.0)) {
            throw new IllegalArgumentException("PresencePenalty must be between -2.0 and 2.0");
        }
        if (frequencyPenalty != null && (frequencyPenalty < -2.0 || frequencyPenalty > 2.0)) {
            throw new IllegalArgumentException("FrequencyPenalty must be between -2.0 and 2.0");
        }
    }

    /**
     * Create a new builder with no pre-set model.
     * The provider's configured model will be used at execution time.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Create a new builder for the specified model.
     *
     * @param model the model to use (e.g., "gpt-4o"); may be {@code null} to defer to the provider
     * @return a new builder instance
     * @deprecated since 0.16.0 — use {@link #builder()} and call {@link Builder#model(String)}
     *             when an explicit per-request override is needed. The model will be resolved
     *             from the provider's configured default when not specified.
     */
    @Deprecated(since = "0.16.0", forRemoval = true)
    public static Builder builder(String model) {
        return new Builder(model);
    }

    /**
     * Builder for creating LLMRequest instances.
     */
    public static class Builder {
        private String model;
        private final List<LLMMessage> messages = new ArrayList<>();
        private Double temperature;
        private Integer maxTokens;
        private List<FunctionDefinition> functions;
        private String functionCall;
        private Double topP;
        private Integer n;
        private List<String> stop;
        private Double presencePenalty;
        private Double frequencyPenalty;
        private Map<String, Object> additionalParameters;

        private Builder() {
            this.model = null;
        }

        private Builder(String model) {
            this.model = model;
        }

        /**
         * Set the model for this specific request.
         *
         * <p>When set, overrides the provider's configured default model for this request only.
         * If not called, the provider will use its configured model at execution time.
         *
         * @param model the model identifier, or {@code null} to use the provider's default
         * @return this builder
         */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /**
         * Add a message to the conversation.
         *
         * @param message the message to add
         * @return this builder
         */
        public Builder addMessage(LLMMessage message) {
            this.messages.add(Objects.requireNonNull(message));
            return this;
        }

        /**
         * Set all messages at once (replaces any existing messages).
         *
         * @param messages the messages to set
         * @return this builder
         */
        public Builder messages(List<LLMMessage> messages) {
            this.messages.clear();
            this.messages.addAll(Objects.requireNonNull(messages));
            return this;
        }

        /**
         * Add a system message.
         *
         * @param content the system message content
         * @return this builder
         */
        public Builder systemMessage(String content) {
            return addMessage(LLMMessage.system(content));
        }

        /**
         * Add a user message.
         *
         * @param content the user message content
         * @return this builder
         */
        public Builder userMessage(String content) {
            return addMessage(LLMMessage.user(content));
        }

        /**
         * Add an assistant message.
         *
         * @param content the assistant message content
         * @return this builder
         */
        public Builder assistantMessage(String content) {
            return addMessage(LLMMessage.assistant(content));
        }

        /**
         * Set the temperature (0.0 to 2.0).
         *
         * <p>Controls randomness in the output. Lower values make output more
         * focused and deterministic, higher values make it more random.
         *
         * @param temperature the temperature value
         * @return this builder
         */
        public Builder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        /**
         * Set the maximum number of tokens to generate.
         *
         * @param maxTokens the maximum tokens
         * @return this builder
         */
        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        /**
         * Set the functions that can be called by the LLM.
         *
         * @param functions list of function definitions
         * @return this builder
         */
        public Builder functions(List<FunctionDefinition> functions) {
            this.functions = functions;
            return this;
        }

        /**
         * Add a single function definition.
         *
         * @param function the function to add
         * @return this builder
         */
        public Builder addFunction(FunctionDefinition function) {
            if (this.functions == null) {
                this.functions = new ArrayList<>();
            }
            this.functions.add(function);
            return this;
        }

        /**
         * Control which function the model should call.
         *
         * <p>Can be "none", "auto", or specify a function name like {"name": "my_function"}.
         *
         * @param functionCall the function call directive
         * @return this builder
         */
        public Builder functionCall(String functionCall) {
            this.functionCall = functionCall;
            return this;
        }

        /**
         * Set top_p (nucleus sampling) parameter (0.0 to 1.0).
         *
         * <p>Alternative to temperature for controlling randomness.
         *
         * @param topP the top_p value
         * @return this builder
         */
        public Builder topP(double topP) {
            this.topP = topP;
            return this;
        }

        /**
         * Set number of completions to generate.
         *
         * @param n number of completions
         * @return this builder
         */
        public Builder n(int n) {
            this.n = n;
            return this;
        }

        /**
         * Set stop sequences.
         *
         * <p>The model will stop generating when it encounters any of these sequences.
         *
         * @param stop list of stop sequences
         * @return this builder
         */
        public Builder stop(List<String> stop) {
            this.stop = stop;
            return this;
        }

        /**
         * Set presence penalty (-2.0 to 2.0).
         *
         * <p>Positive values penalize new tokens based on whether they appear
         * in the text so far, encouraging the model to talk about new topics.
         *
         * @param presencePenalty the presence penalty
         * @return this builder
         */
        public Builder presencePenalty(double presencePenalty) {
            this.presencePenalty = presencePenalty;
            return this;
        }

        /**
         * Set frequency penalty (-2.0 to 2.0).
         *
         * <p>Positive values penalize new tokens based on their frequency in the
         * text so far, decreasing likelihood of repeating the same line.
         *
         * @param frequencyPenalty the frequency penalty
         * @return this builder
         */
        public Builder frequencyPenalty(double frequencyPenalty) {
            this.frequencyPenalty = frequencyPenalty;
            return this;
        }

        /**
         * Set additional provider-specific parameters.
         *
         * @param additionalParameters map of additional parameters
         * @return this builder
         */
        public Builder additionalParameters(Map<String, Object> additionalParameters) {
            this.additionalParameters = additionalParameters;
            return this;
        }

        /**
         * Build the immutable LLMRequest.
         *
         * @return the constructed request
         * @throws IllegalArgumentException if validation fails
         */
        public LLMRequest build() {
            return new LLMRequest(
                model,
                messages,
                temperature,
                maxTokens,
                functions,
                functionCall,
                topP,
                n,
                stop,
                presencePenalty,
                frequencyPenalty,
                additionalParameters
            );
        }
    }

    /**
     * Get the last user message in the conversation.
     *
     * @return the last user message, or null if none exists
     */
    public LLMMessage getLastUserMessage() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            LLMMessage msg = messages.get(i);
            if (msg.isUser()) {
                return msg;
            }
        }
        return null;
    }

    /**
     * Check if this request includes function definitions.
     *
     * @return true if functions are defined
     */
    public boolean hasFunctions() {
        return functions != null && !functions.isEmpty();
    }

    /**
     * Get the total number of messages.
     *
     * @return message count
     */
    public int messageCount() {
        return messages.size();
    }
}
