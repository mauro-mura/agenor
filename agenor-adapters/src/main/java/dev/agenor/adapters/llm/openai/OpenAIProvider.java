package dev.agenor.adapters.llm.openai;

import dev.agenor.adapters.llm.LLMSupport;
import dev.agenor.adapters.llm.ToolConversionUtils;
import dev.agenor.core.llm.*;
import dev.agenor.core.memory.llm.ModelTokenLimits;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class OpenAIProvider implements LLMProvider {

    private final OpenAiChatModel chatModel;
    private final OpenAiStreamingChatModel streamingModel;
    private final String modelName;

    // -------------------------------------------------------------------------
    // Model enum — single source of truth for model ID + context window size.
    // Source: https://platform.openai.com/docs/models — Last verified: 2026-04
    // -------------------------------------------------------------------------
    public enum Models {
        // GPT-4.1 family (1M context)
        GPT_4_1             ("gpt-4.1",               1_000_000),
        GPT_4_1_MINI        ("gpt-4.1-mini",          1_000_000),
        GPT_4_1_NANO        ("gpt-4.1-nano",          1_000_000),
        // o-series reasoning models
        O3                  ("o3",                      200_000),
        O4_MINI             ("o4-mini",                 200_000),
        O3_MINI             ("o3-mini",                 200_000),
        O1                  ("o1",                      200_000),
        O1_MINI             ("o1-mini",                 128_000),
        O1_PREVIEW          ("o1-preview",              128_000),
        // GPT-4o family
        GPT_4O              ("gpt-4o",                  128_000),
        GPT_4O_MINI         ("gpt-4o-mini",             128_000),
        // GPT-4 Turbo
        GPT_4_TURBO         ("gpt-4-turbo",             128_000),
        GPT_4_TURBO_PREVIEW ("gpt-4-turbo-preview",     128_000),
        GPT_4_1106_PREVIEW  ("gpt-4-1106-preview",      128_000),
        GPT_4_0125_PREVIEW  ("gpt-4-0125-preview",      128_000),
        // GPT-4 original
        GPT_4               ("gpt-4",                     8_192),
        GPT_4_0314          ("gpt-4-0314",                8_192),
        GPT_4_0613          ("gpt-4-0613",                8_192),
        GPT_4_32K           ("gpt-4-32k",                32_768),
        // GPT-3.5 (legacy)
        GPT_3_5_TURBO       ("gpt-3.5-turbo",            16_385),
        GPT_3_5_TURBO_16K   ("gpt-3.5-turbo-16k",        16_385),
        GPT_3_5_TURBO_1106  ("gpt-3.5-turbo-1106",       16_385),
        GPT_3_5_TURBO_0125  ("gpt-3.5-turbo-0125",       16_385);

        public final String id;
        public final int contextWindow;

        Models(String id, int contextWindow) {
            this.id = id;
            this.contextWindow = contextWindow;
        }
    }

    static {
        Arrays.stream(Models.values())
            .forEach(m -> ModelTokenLimits.register(m.id, m.contextWindow));
    }

    private OpenAIProvider(Builder builder) {
        this.modelName = builder.modelName;
        this.chatModel = OpenAiChatModel.builder()
                .apiKey(builder.apiKey)
                .baseUrl(builder.baseUrl)
                .modelName(builder.modelName)
                .temperature(builder.temperature)
                .maxTokens(builder.maxTokens)
                .timeout(builder.timeout)
                .logRequests(builder.logRequests)
                .logResponses(builder.logResponses)
                .build();
        this.streamingModel = OpenAiStreamingChatModel.builder()
                .apiKey(builder.apiKey)
                .baseUrl(builder.baseUrl)
                .modelName(builder.modelName)
                .temperature(builder.temperature)
                .maxTokens(builder.maxTokens)
                .timeout(builder.timeout)
                .logRequests(builder.logRequests)
                .logResponses(builder.logResponses)
                .build();
    }

    @Override
    public CompletableFuture<LLMResponse> chat(LLMRequest request) {
        final String resolvedModel = LLMSupport.resolveModel(request, modelName);
        return LLMSupport.supplyAsync(() -> {
            ChatRequest.Builder chatRequestBuilder = ChatRequest.builder()
                    .modelName(resolvedModel)
                    .messages(convertMessages(request));

            // ✅ ADD FUNCTION CALLING SUPPORT
            if (request.hasFunctions()) {
                List<ToolSpecification> toolSpecs = ToolConversionUtils.convertFunctionsToToolSpecs(request.functions());
                chatRequestBuilder.toolSpecifications(toolSpecs);
            }

            ChatResponse response = chatModel.chat(chatRequestBuilder.build());

            LLMResponse.Builder builder = LLMResponse.builder(response.id(), resolvedModel);
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
        final String resolvedModel = LLMSupport.resolveModel(request, modelName);
        final int[] idx = new int[] { 0 };
        final boolean[] sawPartial = new boolean[] { false };

        ChatRequest.Builder chatRequestBuilder = ChatRequest.builder()
                .modelName(resolvedModel)
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
                    public void onPartialResponse(String partialResponse) {
                        if (partialResponse == null || partialResponse.isEmpty()) {
                            return;
                        }
                        sawPartial[0] = true;
                        handler.accept(
                                StreamingChunk.of(streamId, resolvedModel, partialResponse, idx[0]++));
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse completeResponse) {
                        String content = (completeResponse != null && completeResponse.aiMessage() != null)
                                ? completeResponse.aiMessage().text()
                                : "";
                        String finish = (completeResponse != null && completeResponse.finishReason() != null)
                                ? completeResponse.finishReason().toString()
                                : "stop";
                        // Only when nothing was streamed: a response that produced no incremental
                        // events must still reach the handler, and one that did must not arrive
                        // a second time in full.
                        if (!sawPartial[0] && content != null && !content.isEmpty()) {
                            handler.accept(StreamingChunk.of(streamId, resolvedModel, content, idx[0]++));
                        }
                        handler.accept(StreamingChunk.of(streamId, resolvedModel, "", finish, idx[0]));
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
        return CompletableFuture.completedFuture(
            Arrays.stream(Models.values()).map(m -> m.id).toList()
        );
    }

    @Override
    public String getProviderName() {
        return "OpenAI";
    }

    @Override
    public String getDefaultModel() { return Models.GPT_4O.id; }


    // ========================================================================
    // Conversion Methods
    // ========================================================================

    private List<ChatMessage> convertMessages(LLMRequest request) {
        return request.messages().stream().map(msg -> {
            String content = msg.content() == null ? "" : msg.content();
            return switch (msg.role()) {
                case SYSTEM -> (ChatMessage) SystemMessage.from(content);
                case ASSISTANT -> (ChatMessage) AiMessage.from(content);
                default -> (ChatMessage) UserMessage.from(content);
            };
        }).collect(Collectors.toList());
    }

    // ========================================================================
    // Builder
    // ========================================================================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String apiKey;
        private String baseUrl;
        private String modelName = Models.GPT_4O.id;
        private Double temperature = 0.7;
        private Integer maxTokens = 2000;
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

        /** Set model by string ID (for custom/external models). */
        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        /** Set model by enum constant — preferred for known models. */
        public Builder modelName(Models model) {
            this.modelName = model.id;
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

        public OpenAIProvider build() {
            if (apiKey == null) throw new IllegalStateException("API key required");
            return new OpenAIProvider(this);
        }
    }
}
