package dev.jentic.adapters.llm.ollama;

import dev.jentic.core.llm.*;
import dev.jentic.core.memory.llm.ModelTokenLimits;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class OllamaProvider implements LLMProvider {

    private final OllamaChatModel chatModel;
    private final OllamaStreamingChatModel streamingModel;
    private final String modelName;
    private final String baseUrl;

    // -------------------------------------------------------------------------
    // Model enum — family/base names with their context window size.
    // Versioned tags (":8b", ":70b") share the base context window and are
    // registered separately in the static block below.
    // Source: https://ollama.com/library — Last verified: 2026-04
    // -------------------------------------------------------------------------
    public enum Models {
        // Llama 3.x (Meta)
        LLAMA_3_2     ("llama3.2",      128_000),
        LLAMA_3_1     ("llama3.1",      128_000),
        LLAMA_3       ("llama3",          8_192),
        // Llama 2 (legacy)
        LLAMA_2       ("llama2",          4_096),
        // Mistral / Mixtral
        MISTRAL       ("mistral",        32_768),
        MIXTRAL       ("mixtral",        32_768),
        MISTRAL_NEMO  ("mistral-nemo",  128_000),
        // Qwen 2.5 (Alibaba)
        QWEN_2_5      ("qwen2.5",       128_000),
        QWEN_2_5_CODER("qwen2.5-coder", 128_000),
        // Gemma (Google)
        GEMMA_3       ("gemma3",          8_192),
        GEMMA_2       ("gemma2",          8_192),
        GEMMA         ("gemma",           8_192),
        // Phi (Microsoft)
        PHI_4         ("phi4",           16_384),
        PHI_3         ("phi3",            4_096),
        PHI_3_5       ("phi3.5",          4_096),
        // DeepSeek
        DEEPSEEK_R1        ("deepseek-r1",       128_000),
        DEEPSEEK_CODER_V2  ("deepseek-coder-v2", 128_000),
        // Code-focused
        CODELLAMA     ("codellama",      16_384),
        CODEGEMMA     ("codegemma",       8_192);

        public final String id;
        public final int contextWindow;

        Models(String id, int contextWindow) {
            this.id = id;
            this.contextWindow = contextWindow;
        }
    }

    static {
        // Register base model names from enum
        Arrays.stream(Models.values())
            .forEach(m -> ModelTokenLimits.register(m.id, m.contextWindow));

        // Register versioned tags — same context window as the base family.
        // These are not in the enum because the tag combinations are open-ended.
        ModelTokenLimits.register("llama3.2:1b",    Models.LLAMA_3_2.contextWindow);
        ModelTokenLimits.register("llama3.2:3b",    Models.LLAMA_3_2.contextWindow);
        ModelTokenLimits.register("llama3.1:8b",    Models.LLAMA_3_1.contextWindow);
        ModelTokenLimits.register("llama3.1:70b",   Models.LLAMA_3_1.contextWindow);
        ModelTokenLimits.register("llama3.1:405b",  Models.LLAMA_3_1.contextWindow);
        ModelTokenLimits.register("llama3:8b",      Models.LLAMA_3.contextWindow);
        ModelTokenLimits.register("llama3:70b",     Models.LLAMA_3.contextWindow);
        ModelTokenLimits.register("llama2:7b",      Models.LLAMA_2.contextWindow);
        ModelTokenLimits.register("llama2:13b",     Models.LLAMA_2.contextWindow);
        ModelTokenLimits.register("llama2:70b",     Models.LLAMA_2.contextWindow);
        ModelTokenLimits.register("mistral:7b",     Models.MISTRAL.contextWindow);
        ModelTokenLimits.register("mixtral:8x7b",   Models.MIXTRAL.contextWindow);
        ModelTokenLimits.register("mixtral:8x22b",  65_536);
        ModelTokenLimits.register("qwen2.5:7b",     Models.QWEN_2_5.contextWindow);
        ModelTokenLimits.register("qwen2.5:72b",    Models.QWEN_2_5.contextWindow);
        ModelTokenLimits.register("codellama:7b",   Models.CODELLAMA.contextWindow);
        ModelTokenLimits.register("codellama:13b",  Models.CODELLAMA.contextWindow);
        ModelTokenLimits.register("codellama:34b",  Models.CODELLAMA.contextWindow);
    }

    private OllamaProvider(Builder builder) {
        this.modelName = builder.modelName;
        this.baseUrl = builder.baseUrl;

        this.chatModel = OllamaChatModel.builder()
                .baseUrl(builder.baseUrl)
                .modelName(builder.modelName)
                .temperature(builder.temperature)
                .timeout(builder.timeout)
                .logRequests(builder.logRequests)
                .logResponses(builder.logResponses)
                .build();

        this.streamingModel = OllamaStreamingChatModel.builder()
                .baseUrl(builder.baseUrl)
                .modelName(builder.modelName)
                .temperature(builder.temperature)
                .timeout(builder.timeout)
                .build();
    }

    @Override
    public CompletableFuture<LLMResponse> chat(LLMRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<ChatMessage> messages = convertMessages(request.messages());

                ChatRequest.Builder requestBuilder = ChatRequest.builder()
                        .messages(messages);

                if (request.temperature() != null) {
                    requestBuilder.temperature(request.temperature());
                }
                if (request.maxTokens() != null) {
                    requestBuilder.maxOutputTokens(request.maxTokens());
                }

                ChatResponse response = chatModel.chat(requestBuilder.build());

                return LLMResponse.builder(
                                UUID.randomUUID().toString(),
                                modelName != null ? modelName : request.model()
                        )
                        .content(response.aiMessage().text())
                        .role(LLMMessage.Role.ASSISTANT)
                        .finishReason(mapFinishReason(response.metadata() != null ? response.metadata().finishReason() : null))
                        .usage(mapUsage(response.metadata() != null ? response.metadata().tokenUsage() : null))
                        .build();

            } catch (Exception e) {
                throw new RuntimeException(new LLMException("Ollama chat request failed", e));
            }
        });
    }

    @Override
    public CompletableFuture<Void> chatStream(LLMRequest request, Consumer<StreamingChunk> chunkHandler) {
        CompletableFuture<Void> result = new CompletableFuture<>();

        try {
            List<ChatMessage> messages = convertMessages(request.messages());
            final String streamId = UUID.randomUUID().toString();
            final int[] chunkIndex = {0};

            ChatRequest.Builder requestBuilder = ChatRequest.builder()
                    .messages(messages);

            if (request.temperature() != null) {
                requestBuilder.temperature(request.temperature());
            }
            if (request.maxTokens() != null) {
                requestBuilder.maxOutputTokens(request.maxTokens());
            }

            streamingModel.chat(requestBuilder.build(), new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    StreamingChunk chunk = StreamingChunk.of(
                            streamId,
                            modelName != null ? modelName : request.model(),
                            partialResponse,
                            chunkIndex[0]++
                    );
                    chunkHandler.accept(chunk);
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    String finishReason = null;
                    if (completeResponse != null && completeResponse.metadata() != null
                            && completeResponse.metadata().finishReason() != null) {
                        finishReason = mapFinishReason(completeResponse.metadata().finishReason());
                    }

                    StreamingChunk finalChunk = StreamingChunk.of(
                            streamId,
                            modelName != null ? modelName : request.model(),
                            "",
                            finishReason,
                            chunkIndex[0]
                    );
                    chunkHandler.accept(finalChunk);
                    result.complete(null);
                }

                @Override
                public void onError(Throwable error) {
                    result.completeExceptionally(new LLMException("Ollama streaming failed", error));
                }
            });

        } catch (Exception e) {
            result.completeExceptionally(new LLMException("Ollama streaming request failed", e));
        }

        return result;
    }

    @Override
    public CompletableFuture<List<String>> getAvailableModels() {
        // Returns base model names only; versioned tags can be used freely via modelName(String).
        return CompletableFuture.completedFuture(
            Arrays.stream(Models.values()).map(m -> m.id).toList()
        );
    }

    @Override
    public String getProviderName() {
        return "Ollama";
    }

    @Override
    public boolean supportsFunctionCalling() {
        return false; // Ollama doesn't support function calling in most models
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public String getDefaultModel() { return Models.LLAMA_3_2.id; }

    private List<ChatMessage> convertMessages(List<LLMMessage> messages) {
        return messages.stream()
                .map(this::convertMessage)
                .collect(Collectors.toList());
    }

    private ChatMessage convertMessage(LLMMessage message) {
        return switch (message.role()) {
            case SYSTEM -> SystemMessage.from(message.content());
            case USER -> UserMessage.from(message.content());
            case ASSISTANT -> AiMessage.from(message.content());
            default -> throw new IllegalArgumentException("Unsupported message role: " + message.role());
        };
    }

    private String mapFinishReason(FinishReason finishReason) {
        if (finishReason == null) return null;
        return switch (finishReason) {
            case STOP -> "stop";
            case LENGTH -> "length";
            case TOOL_EXECUTION -> "tool_calls";
            case CONTENT_FILTER -> "content_filter";
            case OTHER -> "other";
        };
    }

    private LLMResponse.Usage mapUsage(TokenUsage tokenUsage) {
        if (tokenUsage == null) {
            return null;
        }
        return new LLMResponse.Usage(
                tokenUsage.inputTokenCount(),
                tokenUsage.outputTokenCount(),
                tokenUsage.totalTokenCount()
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String baseUrl = "http://localhost:11434";
        private String modelName = Models.LLAMA_3_2.id;
        private Double temperature;
        private Duration timeout = Duration.ofMinutes(5);
        private boolean logRequests = false;
        private boolean logResponses = false;

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /** Set model by string ID — use for versioned tags (e.g. "llama3.2:70b"). */
        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        /** Set model by enum constant — preferred for base model names. */
        public Builder modelName(Models model) {
            this.modelName = model.id;
            return this;
        }

        public Builder temperature(double temperature) {
            this.temperature = temperature;
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

        public OllamaProvider build() {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("Base URL is required");
            }
            if (modelName == null || modelName.isBlank()) {
                throw new IllegalArgumentException("Model name is required");
            }
            return new OllamaProvider(this);
        }
    }
}
