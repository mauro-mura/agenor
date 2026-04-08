package dev.jentic.adapters.llm.anthropic;

import dev.jentic.adapters.llm.ToolConversionUtils;
import dev.jentic.core.llm.*;
import dev.jentic.core.memory.llm.ModelTokenLimits;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.data.message.*;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class AnthropicProvider implements LLMProvider {

    private final AnthropicChatModel chatModel;
    private final AnthropicStreamingChatModel streamingModel;
    private final String modelName;

    // -------------------------------------------------------------------------
    // Single source of truth for Anthropic model → context window size.
    // Both getAvailableModels() and ModelTokenLimits registration derive from here.
    // Source: https://platform.claude.com/docs/en/about-claude/models/overview
    // Last verified: 2026-04
    // -------------------------------------------------------------------------
    private static final Map<String, Integer> KNOWN_MODELS = Map.ofEntries(
        // Claude 4.x family
        Map.entry("claude-opus-4-6",              200_000),
        Map.entry("claude-sonnet-4-6",            200_000),
        Map.entry("claude-opus-4-5-20251101",     200_000),
        Map.entry("claude-opus-4-1",              200_000),
        Map.entry("claude-opus-4-20250514",       200_000),
        Map.entry("claude-sonnet-4-5",            200_000),
        Map.entry("claude-sonnet-4-20250514",     200_000),
        Map.entry("claude-haiku-4-5-20251001",    200_000),
        // Claude 3.x family
        Map.entry("claude-3-7-sonnet-20250219",   200_000),
        Map.entry("claude-3-5-sonnet-20241022",   200_000),
        Map.entry("claude-3-5-haiku-20241022",    200_000),
        Map.entry("claude-3-5-sonnet-20240620",   200_000),
        Map.entry("claude-3-opus-20240229",       200_000),
        Map.entry("claude-3-haiku-20240307",      200_000),
        // Claude 2.x (legacy)
        Map.entry("claude-2.1",                   200_000),
        Map.entry("claude-2.0",                   100_000),
        Map.entry("claude-instant-1.2",           100_000)
    );

    static {
        KNOWN_MODELS.forEach(ModelTokenLimits::register);
    }

    private AnthropicProvider(Builder builder) {
        this.modelName = builder.modelName;
        this.chatModel = AnthropicChatModel.builder()
                .apiKey(builder.apiKey)
                .baseUrl(builder.baseUrl)
                .modelName(builder.modelName)
                .temperature(builder.temperature)
                .maxTokens(builder.maxTokens)
                .timeout(builder.timeout)
                .logRequests(builder.logRequests)
                .logResponses(builder.logResponses)
                .build();

        this.streamingModel = AnthropicStreamingChatModel.builder()
                .apiKey(builder.apiKey)
                .baseUrl(builder.baseUrl)
                .modelName(builder.modelName)
                .temperature(builder.temperature)
                .maxTokens(builder.maxTokens)
                .timeout(builder.timeout)
                .build();
    }

    @Override
    public CompletableFuture<LLMResponse> chat(LLMRequest request) {
        return CompletableFuture.supplyAsync(() -> {

            ChatRequest.Builder chatRequestBuilder = ChatRequest.builder()
                    .messages(convertMessages(request));

            // ✅ ADD FUNCTION CALLING SUPPORT
            if (request.hasFunctions()) {
                List<ToolSpecification> toolSpecs = ToolConversionUtils.convertFunctionsToToolSpecs(request.functions());
                chatRequestBuilder.toolSpecifications(toolSpecs);
            }

            ChatResponse response = chatModel.chat(chatRequestBuilder.build());

            LLMResponse.Builder builder = LLMResponse.builder(response.id(), modelName);
            builder.role(LLMMessage.Role.ASSISTANT);

            if (response.aiMessage() != null && response.aiMessage().text() != null) {
                builder.content(response.aiMessage().text());
            }

            // Handle tool execution requests (function calls)
            if (response.aiMessage() != null && response.aiMessage().hasToolExecutionRequests()) {
                List<FunctionCall> functionCalls = new ArrayList<>();
                response.aiMessage().toolExecutionRequests().forEach(toolExecutionRequest -> {
                    functionCalls.add(new FunctionCall(
                            toolExecutionRequest.id(),
                            toolExecutionRequest.name(),
                            toolExecutionRequest.arguments()
                    ));
                });
                builder.functionCalls(functionCalls);
            }

            if (response.tokenUsage() != null) {
                builder.usage(
                        response.tokenUsage().inputTokenCount(),
                        response.tokenUsage().outputTokenCount(),
                        response.tokenUsage().totalTokenCount()
                );
            }

            if (response.finishReason() != null) {
                builder.finishReason(response.finishReason().toString());
            }

            Map<String, Object> metadata = new HashMap<>();
            builder.metadata(metadata);
            return builder.build();

        });
    }

    @Override
    public CompletableFuture<Void> chatStream(LLMRequest request, Consumer<StreamingChunk> handler) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        final String streamId = UUID.randomUUID().toString();
        final int[] idx = new int[] { 0 };

        ChatRequest.Builder chatRequestBuilder = ChatRequest.builder()
                .messages(convertMessages(request));

        // ✅ ADD FUNCTION CALLING SUPPORT FOR STREAMING
        if (request.hasFunctions()) {
            List<ToolSpecification> toolSpecs = ToolConversionUtils.convertFunctionsToToolSpecs(request.functions());
            chatRequestBuilder.toolSpecifications(toolSpecs);
        }

        streamingModel.chat(
                chatRequestBuilder.build(),
                new StreamingChatResponseHandler() {
                    @Override
                    public void onCompleteResponse(ChatResponse completeResponse) {
                        String content = (completeResponse != null && completeResponse.aiMessage() != null)
                                ? completeResponse.aiMessage().text()
                                : "";
                        String finish = (completeResponse != null && completeResponse.finishReason() != null)
                                ? completeResponse.finishReason().toString()
                                : "stop";
                        if (content != null && !content.isEmpty()) {
                            handler.accept(StreamingChunk.of(streamId, modelName, content, idx[0]++));
                        }
                        handler.accept(StreamingChunk.of(streamId, modelName, "", finish, idx[0]));
                        future.complete(null);
                    }

                    @Override
                    public void onError(Throwable error) {
                        future.completeExceptionally(error);
                    }
                }
        );
        return future;
    }

    @Override
    public CompletableFuture<List<String>> getAvailableModels() {
        return CompletableFuture.completedFuture(List.copyOf(KNOWN_MODELS.keySet()));
    }

    @Override
    public String getProviderName() {
        return "Anthropic";
    }

    private List<ChatMessage> convertMessages(LLMRequest request) {
        return request.messages().stream().map(msg -> {
            return switch (msg.role()) {
                case SYSTEM -> (ChatMessage) SystemMessage.from(msg.content());
                case USER -> (ChatMessage) UserMessage.from(msg.content());
                case ASSISTANT -> (ChatMessage) AiMessage.from(msg.content());
                default -> (ChatMessage) UserMessage.from(msg.content());
            };
        }).collect(Collectors.toList());
    }


    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String apiKey;
        private String baseUrl;
        private String modelName = "claude-3-5-sonnet-20241022";
        private Double temperature = 0.7;
        private Integer maxTokens = 4096;
        private Duration timeout = Duration.ofSeconds(60);
        private boolean logRequests = false;
        private boolean logResponses = false;

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder model(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder logRequests(boolean logRequests) {
            this.logRequests = logRequests;
            return this;
        }

        public Builder logResponses(boolean logResponses) {
            this.logResponses = logResponses;
            return this;
        }

        public AnthropicProvider build() {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("API key is required");
            }
            return new AnthropicProvider(this);
        }
    }
}
