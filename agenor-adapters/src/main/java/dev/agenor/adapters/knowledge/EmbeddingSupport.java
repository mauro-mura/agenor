package dev.agenor.adapters.knowledge;

import dev.agenor.core.knowledge.EmbeddingException;
import dev.agenor.core.knowledge.EmbeddingException.ErrorType;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.exception.UnresolvedModelServerException;
import dev.langchain4j.model.embedding.EmbeddingModel;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Shared machinery behind {@code OpenAIEmbeddingProvider} and {@code OllamaEmbeddingProvider}.
 *
 * <p>Plumbing, not surface: it is public only because those two live in sibling packages, and
 * nothing outside this module should call it. The API is
 * {@link dev.agenor.core.knowledge.EmbeddingProvider} and {@link EmbeddingProviderFactory}.
 *
 * <p>It does two things neither provider should be duplicating. LangChain4j's
 * {@link EmbeddingModel} is synchronous while {@code EmbeddingProvider} is not, so the call
 * runs on a virtual thread (ADR-001). And a provider failure is classified into the
 * {@link ErrorType} a caller can branch on — previously each provider read HTTP status codes
 * itself, which meant the Ollama one classified nothing: every non-200 was {@code SERVER_ERROR},
 * so a wrong model name and a dead daemon reached the caller as the same error.
 *
 * @since 0.33.0
 */
public final class EmbeddingSupport {

    private static final Executor VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private EmbeddingSupport() {}

    /**
     * Embeds one text, as a batch of one.
     *
     * @param provider provider name, used in the error message
     * @param model    the backing LangChain4j model
     * @param text     input text; must be non-null and non-blank
     * @return the vector, or a future completed with an {@link EmbeddingException}
     */
    public static CompletableFuture<float[]> embedOne(String provider, EmbeddingModel model,
                                                      String text) {
        // Null and blank arrive as a failed future, not as a throw: the contract says an
        // EmbeddingException comes back wrapped, and a caller writing
        // provider.embed(x).exceptionally(...) should not also need a try/catch around it.
        return embedAll(provider, model, text == null ? null : List.of(text))
                .thenApply(vectors -> vectors.get(0));
    }

    /**
     * Embeds a batch in one request.
     *
     * @param provider provider name, used in the error message
     * @param model    the backing LangChain4j model
     * @param texts    input texts; must be non-null, non-empty, and each non-blank
     * @return one vector per input, in order, or a future completed with an
     *         {@link EmbeddingException}
     */
    public static CompletableFuture<List<float[]>> embedAll(String provider, EmbeddingModel model,
                                                            List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return CompletableFuture.failedFuture(new EmbeddingException(
                    provider + " embedding failed: no input texts", ErrorType.INVALID_INPUT));
        }
        List<TextSegment> segments;
        try {
            segments = texts.stream().map(t -> TextSegment.from(requireText(provider, t))).toList();
        } catch (EmbeddingException e) {
            return CompletableFuture.failedFuture(e);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                return model.embedAll(segments).content().stream()
                        .map(embedding -> embedding.vector())
                        .toList();
            } catch (Exception e) {
                throw wrap(provider, e);
            }
        }, VIRTUAL_EXECUTOR);
    }

    private static String requireText(String provider, String text) {
        if (text == null || text.isBlank()) {
            throw new EmbeddingException(provider + " embedding failed: text is null or blank",
                    ErrorType.INVALID_INPUT);
        }
        return text;
    }

    /** Wraps {@code cause} as an {@link EmbeddingException}, classified as far as it can be. */
    private static EmbeddingException wrap(String provider, Throwable cause) {
        var root = unwrap(cause);
        if (root instanceof EmbeddingException already) return already;
        return new EmbeddingException(
                provider + " embedding failed: " + root.getMessage(), classify(root), root);
    }

    private static Throwable unwrap(Throwable t) {
        var c = t;
        while ((c instanceof CompletionException || c instanceof ExecutionException)
                && c.getCause() != null) {
            c = c.getCause();
        }
        return c;
    }

    /**
     * Classifies the whole cause chain, not just the outermost throwable.
     *
     * <p>The chain is where the answer usually is. LangChain4j's Ollama model reports an
     * unreachable daemon as {@code RuntimeException: java.net.ConnectException} — matching only
     * the top frame calls that {@code UNKNOWN}, which is the least useful thing it could say
     * about the most common failure a developer will hit.
     */
    private static ErrorType classify(Throwable t) {
        for (var c = t; c != null; c = c.getCause()) {
            var type = classifyOne(c);
            if (type != ErrorType.UNKNOWN) return type;
            if (c.getCause() == c) break;   // self-referential chain
        }
        return ErrorType.UNKNOWN;
    }

    private static ErrorType classifyOne(Throwable t) {
        if (t instanceof AuthenticationException)        return ErrorType.AUTHENTICATION;
        if (t instanceof RateLimitException)             return ErrorType.RATE_LIMIT;
        if (t instanceof ModelNotFoundException)         return ErrorType.MODEL_NOT_FOUND;
        if (t instanceof InvalidRequestException)        return ErrorType.INVALID_INPUT;
        if (t instanceof TimeoutException)               return ErrorType.NETWORK;
        if (t instanceof UnresolvedModelServerException) return ErrorType.NETWORK;
        if (t instanceof java.io.IOException)            return ErrorType.NETWORK;
        if (t instanceof InternalServerException)        return ErrorType.SERVER_ERROR;
        if (t instanceof HttpException)                  return ErrorType.SERVER_ERROR;
        return ErrorType.UNKNOWN;
    }
}
