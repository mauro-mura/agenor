package dev.agenor.examples.support.llm;

import dev.agenor.core.llm.LLMProvider;
import dev.agenor.core.llm.LLMRequest;
import dev.agenor.core.llm.LLMResponse;
import dev.agenor.examples.support.context.ConversationContext;
import dev.agenor.core.knowledge.KnowledgeDocument;
import dev.agenor.examples.support.model.SupportIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Generates natural language responses using LLM with RAG pattern.
 * Falls back to template-based responses if LLM is unavailable.
 */
public class LLMResponseGenerator {

    private static final Logger log = LoggerFactory.getLogger(LLMResponseGenerator.class);

    private final LLMProvider provider;
    private final String modelName;
    private final long timeoutSeconds;

    public LLMResponseGenerator(LLMProvider provider, String modelName) {
        this.provider = provider;
        this.modelName = modelName != null ? modelName : "gpt-4o-mini";
        this.timeoutSeconds = 30;
    }

    public LLMResponseGenerator(LLMConfig config) {
        this.provider = config.createProvider();
        this.modelName = getModelFromConfig(config);
        this.timeoutSeconds = 30;

        if (this.provider != null) {
            log.info("LLM Response Generator initialized with {} provider",
                config.getProviderType());
        } else {
            log.info("LLM Response Generator in fallback mode (no provider)");
        }
    }

    /**
     * Generates a response using RAG pattern.
     *
     * @param userQuery the user's question
     * @param documents retrieved knowledge documents
     * @param intent classified intent
     * @return generated response text
     */
    public String generate(String userQuery, List<KnowledgeDocument<SupportIntent>> documents, SupportIntent intent) {
        return generate(userQuery, documents, intent, null);
    }

    /**
     * Generates a response with conversation context.
     */
    public String generate(
            String userQuery,
            List<KnowledgeDocument<SupportIntent>> documents,
            SupportIntent intent,
            ConversationContext context) {

        // Fallback if no provider
        if (provider == null) {
            log.debug("No LLM provider, using template fallback");
            return generateFallbackResponse(userQuery, documents);
        }

        try {
            // Build prompts
            String systemPrompt = PromptTemplates.SYSTEM_PROMPT +
                PromptTemplates.getDomainContext(intent);

            String userPrompt = context != null
                ? PromptTemplates.buildContextualPrompt(userQuery, documents, context)
                : PromptTemplates.buildUserPrompt(userQuery, documents);

            // Create LLM request
            LLMRequest request = LLMRequest.builder().model(modelName)
                .systemMessage(systemPrompt)
                .userMessage(userPrompt)
                .temperature(0.7)
                .maxTokens(500)
                .build();

            // Call LLM
            CompletableFuture<LLMResponse> future = provider.chat(request);
            LLMResponse response = future.get(timeoutSeconds, TimeUnit.SECONDS);

            String content = response.content();

            log.debug("LLM response generated ({} tokens)",
                response.usage() != null ? response.usage().totalTokens() : "?");

            return content;

        } catch (Exception e) {
            log.warn("LLM generation failed, using fallback: {}", e.getMessage());
            return generateFallbackResponse(userQuery, documents);
        }
    }

    /**
     * Asynchronous generation.
     */
    public CompletableFuture<String> generateAsync(
            String userQuery,
            List<KnowledgeDocument<SupportIntent>> documents,
            SupportIntent intent) {

        if (provider == null) {
            return CompletableFuture.completedFuture(
                generateFallbackResponse(userQuery, documents));
        }

        String systemPrompt = PromptTemplates.SYSTEM_PROMPT +
            PromptTemplates.getDomainContext(intent);
        String userPrompt = PromptTemplates.buildUserPrompt(userQuery, documents);

        LLMRequest request = LLMRequest.builder().model(modelName)
            .systemMessage(systemPrompt)
            .userMessage(userPrompt)
            .temperature(0.7)
            .maxTokens(500)
            .build();

        return provider.chat(request)
            .thenApply(LLMResponse::content)
            .exceptionally(e -> {
                log.warn("Async LLM generation failed: {}", e.getMessage());
                return generateFallbackResponse(userQuery, documents);
            });
    }

    /**
     * Check if LLM is available.
     */
    public boolean isLLMEnabled() {
        return provider != null;
    }

    /**
     * Fallback response using document templates.
     */
    private String generateFallbackResponse(String userQuery, List<KnowledgeDocument<SupportIntent>> documents) {
        if (documents == null || documents.isEmpty()) {
            return generateNoMatchFallback(userQuery);
        }

        // Use best matching document
        KnowledgeDocument<SupportIntent> best = documents.get(0);

        StringBuilder sb = new StringBuilder();
        sb.append("**").append(best.title()).append("**\n\n");
        sb.append(best.content());

        // Add related topics
        if (documents.size() > 1) {
            sb.append("\n\n---\n*Related topics:* ");
            sb.append(documents.stream()
                .skip(1)
                .limit(2)
                .map(KnowledgeDocument::title)
                .reduce((a, b) -> a + " | " + b)
                .orElse(""));
        }

        return sb.toString();
    }

    private String generateNoMatchFallback(String userQuery) {
        return """
            I couldn't find specific information about your question in my knowledge base.

            Here are some things I can help you with:
            • **Account** - Balance, profile, linked accounts
            • **Transactions** - History, exports, disputes
            • **Security** - Password, 2FA, devices
            • **Budgets** - Create and track spending limits

            Could you rephrase your question or choose one of these topics?
            """;
    }

    private String getModelFromConfig(LLMConfig config) {
        return switch (config.getProviderType()) {
            case OPENAI -> "gpt-4o-mini";
            case ANTHROPIC -> "claude-3-haiku-20240307";
            case OLLAMA -> "llama3.2";
            case NONE -> null;
        };
    }
}
