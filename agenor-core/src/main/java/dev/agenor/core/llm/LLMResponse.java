package dev.agenor.core.llm;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable response object from an LLM chat completion.
 *
 * <p>Contains the generated content, usage statistics, and metadata about
 * the response. This class is thread-safe and immutable.
 *
 * <p>Example usage:
 * <pre>{@code
 * provider.chat(request).thenAccept(response -> {
 *     System.out.println("Response: " + response.content());
 *     System.out.println("Tokens used: " + response.usage().totalTokens());
 *     System.out.println("Model: " + response.model());
 * });
 * }</pre>
 *
 * @param id unique identifier for the response
 * @param model the model that generated the response
 * @param content the generated textual content
 * @param role the role assigned to the response message (usually ASSISTANT)
 * @param functionCalls list of function calls requested by the model
 * @param finishReason the reason why generation finished (e.g., "stop", "length")
 * @param usage token usage statistics for the request
 * @param created timestamp when the response was created
 * @param metadata additional provider-specific metadata
 *
 * @since 0.3.0
 */
public record LLMResponse(
    String id,
    String model,
    String content,
    LLMMessage.Role role,
    List<FunctionCall> functionCalls,
    String finishReason,
    Usage usage,
    Instant created,
    Map<String, Object> metadata
) {

    /**
     * Token usage information.
     *
     * @param promptTokens tokens in the prompt
     * @param completionTokens tokens in the completion
     * @param totalTokens total tokens used (prompt + completion)
     */
    public record Usage(
        int promptTokens,
        int completionTokens,
        int totalTokens
    ) {
        public Usage {
            if (promptTokens < 0) throw new IllegalArgumentException("promptTokens cannot be negative");
            if (completionTokens < 0) throw new IllegalArgumentException("completionTokens cannot be negative");
            if (totalTokens < 0) throw new IllegalArgumentException("totalTokens cannot be negative");
        }

        /**
         * Calculate the cost estimate based on token usage.
         *
         * <p>This is a rough estimate and actual costs vary by provider and model.
         *
         * @param promptCostPer1k cost per 1k prompt tokens
         * @param completionCostPer1k cost per 1k completion tokens
         * @return estimated cost in the same currency as the rates
         */
        public double estimateCost(double promptCostPer1k, double completionCostPer1k) {
            double promptCost = (promptTokens / 1000.0) * promptCostPer1k;
            double completionCost = (completionTokens / 1000.0) * completionCostPer1k;
            return promptCost + completionCost;
        }
    }

    /**
     * Compact constructor with validation and defensive copying.
     */
    public LLMResponse {
        Objects.requireNonNull(id, "ID cannot be null");
        Objects.requireNonNull(model, "Model cannot be null");

        if (functionCalls != null) {
            functionCalls = List.copyOf(functionCalls);
        }
        if (metadata != null) {
            metadata = Map.copyOf(metadata);
        }
        if (created == null) {
            created = Instant.now();
        }
    }

    /**
     * Create a new builder.
     *
     * @param id the response ID
     * @param model the model that generated the response
     * @return a new builder
     */
    public static Builder builder(String id, String model) {
        return new Builder(id, model);
    }

    /**
     * Builder for creating LLMResponse instances.
     */
    public static class Builder {
        private final String id;
        private final String model;
        private String content;
        private LLMMessage.Role role = LLMMessage.Role.ASSISTANT;
        private List<FunctionCall> functionCalls;
        private String finishReason;
        private Usage usage;
        private Instant created = Instant.now();
        private Map<String, Object> metadata;

        private Builder(String id, String model) {
            this.id = Objects.requireNonNull(id);
            this.model = Objects.requireNonNull(model);
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder role(LLMMessage.Role role) {
            this.role = role;
            return this;
        }

        public Builder functionCalls(List<FunctionCall> functionCalls) {
            this.functionCalls = functionCalls;
            return this;
        }

        public Builder finishReason(String finishReason) {
            this.finishReason = finishReason;
            return this;
        }

        public Builder usage(Usage usage) {
            this.usage = usage;
            return this;
        }

        public Builder usage(int promptTokens, int completionTokens, int totalTokens) {
            this.usage = new Usage(promptTokens, completionTokens, totalTokens);
            return this;
        }

        public Builder created(Instant created) {
            this.created = created;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public LLMResponse build() {
            return new LLMResponse(
                id,
                model,
                content,
                role,
                functionCalls,
                finishReason,
                usage,
                created,
                metadata
            );
        }
    }

    /**
     * Check if this response includes function calls.
     *
     * @return true if the response contains function calls
     */
    public boolean hasFunctionCalls() {
        return functionCalls != null && !functionCalls.isEmpty();
    }

    /**
     * Check if the response was truncated due to token limits.
     *
     * @return true if finish reason indicates truncation
     */
    public boolean wasTruncated() {
        return "length".equals(finishReason);
    }

    /**
     * Check if the response completed normally.
     *
     * @return true if finish reason indicates normal completion
     */
    public boolean isComplete() {
        return "stop".equals(finishReason);
    }

    /**
     * Convert this response to an LLMMessage.
     *
     * <p>Useful for adding the response to a conversation history.
     *
     * @return an LLMMessage representing this response
     */
    public LLMMessage toMessage() {
        if (hasFunctionCalls()) {
            return LLMMessage.assistant(content, functionCalls);
        } else {
            return LLMMessage.assistant(content);
        }
    }

    /**
     * Get a truncated version of the content for logging.
     *
     * @param maxLength maximum length
     * @return truncated content
     */
    public String truncatedContent(int maxLength) {
        if (content == null) {
            return "[no content]";
        }
        if (content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength - 3) + "...";
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("LLMResponse{");
        sb.append("id='").append(id).append('\'');
        sb.append(", model='").append(model).append('\'');
        if (content != null) {
            sb.append(", content='").append(truncatedContent(50)).append('\'');
        }
        if (hasFunctionCalls()) {
            sb.append(", functionCalls=").append(functionCalls.size());
        }
        sb.append(", finishReason='").append(finishReason).append('\'');
        if (usage != null) {
            sb.append(", tokens=").append(usage.totalTokens());
        }
        sb.append('}');
        return sb.toString();
    }
}
