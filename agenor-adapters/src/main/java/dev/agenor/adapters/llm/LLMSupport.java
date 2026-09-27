package dev.agenor.adapters.llm;

import dev.agenor.core.llm.LLMException;
import dev.agenor.core.llm.LLMException.ErrorType;
import dev.agenor.core.llm.LLMRequest;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.ContentFilteredException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.exception.UnresolvedModelServerException;
import dev.langchain4j.exception.UnsupportedFeatureException;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Shared machinery behind the three {@link dev.agenor.core.llm.LLMProvider} adapters.
 *
 * <p>Plumbing, not surface: it is public only because those three live in sibling packages, and
 * nothing outside this module should call it. The API is
 * {@link dev.agenor.core.llm.LLMProvider} and {@link LLMProviderFactory}.
 *
 * <p>It does two things none of the three should be duplicating. LangChain4j's chat models are
 * synchronous while {@code LLMProvider} is not, so the call runs on a virtual thread (ADR-001).
 * Each provider previously called {@link CompletableFuture#supplyAsync(Supplier)} with no
 * executor, which is the common {@code ForkJoinPool} — sized {@code cores - 1}, and shared with
 * everything else in the application — for an HTTP request that lasts seconds. The embedding
 * side of this module and the Redis adapters already ran on virtual threads; the LLM side was
 * the exception.
 *
 * <p>It also classifies a provider failure into the {@link ErrorType} a caller can branch on
 * ({@link #wrap}), the same job {@code EmbeddingSupport} does for embeddings — before this, a
 * chat failure either travelled bare inside a {@link CompletionException} (OpenAI, Anthropic) or
 * arrived as an {@link LLMException} always of type {@code UNKNOWN} (Ollama).
 *
 * @since 0.35.0
 */
public final class LLMSupport {

    private static final Executor VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private LLMSupport() {}

    /**
     * Runs a blocking provider call on a virtual thread.
     *
     * @param work the blocking call
     * @param <T>  the result type
     * @return a future completed with the result, or failed with whatever {@code work} threw
     */
    public static <T> CompletableFuture<T> supplyAsync(Supplier<T> work) {
        return CompletableFuture.supplyAsync(work, VIRTUAL_EXECUTOR);
    }

    /**
     * Resolves the model for one request, per ADR-017: {@code request.model()} when it names one,
     * otherwise the model the provider was built with.
     *
     * <p>The result belongs on the request handed to the client, not only on the label of the
     * response. Every adapter used to put it on the label alone, or not read it at all, so a
     * request could name one model and be answered by another with nothing reporting it.
     *
     * @param request     the request, which may or may not name a model
     * @param builtWith   the provider's own model, used when the request names none
     * @return the model to ask, and to label the answer with
     */
    public static String resolveModel(LLMRequest request, String builtWith) {
        String requested = request.model();
        if (requested != null && !requested.isBlank()) return requested;
        return builtWith;
    }

    /**
     * Applies every {@link LLMRequest} sampling field that {@link ChatRequest.Builder} exposes
     * directly, skipping whatever the request left unset. Shared by all three adapters, which
     * previously read none of these — a request could ask for {@code topP} or a stop sequence
     * and every adapter would silently ignore it.
     *
     * <p>Deliberately not applied here: {@link LLMRequest#n()}, which no bundled adapter's
     * client library can honour — LangChain4j has no multiple-completions parameter to set it
     * on — and {@link LLMRequest#additionalParameters()}, which has no generic equivalent below
     * {@link ChatRequest.Builder}; each adapter maps it through its own provider-specific escape
     * hatch where one exists (OpenAI's {@code customParameters}) rather than pretending one
     * generic mapping covers all three.
     *
     * @param builder the request being built for the client
     * @param request the request as the caller specified it
     */
    public static void applyCommonParameters(ChatRequest.Builder builder, LLMRequest request) {
        if (request.topP() != null) {
            builder.topP(request.topP());
        }
        if (request.stop() != null && !request.stop().isEmpty()) {
            builder.stopSequences(request.stop());
        }
        if (request.presencePenalty() != null) {
            builder.presencePenalty(request.presencePenalty());
        }
        if (request.frequencyPenalty() != null) {
            builder.frequencyPenalty(request.frequencyPenalty());
        }
        ToolChoice toolChoice = toToolChoice(request.functionCall());
        if (toolChoice != null) {
            builder.toolChoice(toolChoice);
        }
    }

    /**
     * Maps {@link LLMRequest#functionCall()}'s {@code "none"}/{@code "auto"} onto LangChain4j's
     * generic {@link ToolChoice}, and a request to force some tool onto {@code REQUIRED}.
     *
     * <p>Deliberately not mapped: naming one specific function, e.g. {@code {"name":
     * "get_weather"}} — OpenAI's documented shape for this field. {@code ToolChoice} has no
     * "this one" value, only {@code AUTO}/{@code REQUIRED}/{@code NONE}; forcing a specific tool
     * is provider-specific (Anthropic exposes it as a plain tool name on its own request
     * parameters) and parsing an arbitrary JSON-ish string to reach it is not done here.
     *
     * @param functionCall the request's raw directive, or {@code null}
     * @return the matching {@link ToolChoice}, or {@code null} if {@code functionCall} is absent
     *         or names something more specific than this method maps
     */
    private static ToolChoice toToolChoice(String functionCall) {
        if (functionCall == null) {
            return null;
        }
        return switch (functionCall.trim().toLowerCase(Locale.ROOT)) {
            case "none" -> ToolChoice.NONE;
            case "auto" -> ToolChoice.AUTO;
            case "required", "any" -> ToolChoice.REQUIRED;
            default -> null;
        };
    }

    /**
     * Wraps {@code cause} as an {@link LLMException}, classified as far as it can be.
     *
     * @param provider provider name, attached to the exception and used in its message
     * @param model    the model the failed request named, or {@code null} if it was never
     *                 resolved (a failure before the request reached the client)
     * @param cause    whatever the LangChain4j client threw
     * @return the classified exception; never wraps an {@link LLMException} a second time
     */
    public static LLMException wrap(String provider, String model, Throwable cause) {
        var root = unwrap(cause);
        if (root instanceof LLMException already) return already;
        return new LLMException(provider + " request failed: " + root.getMessage(), root,
                classify(root), provider, model, statusCodeOf(root), isRetryable(root));
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
     * Classifies the whole cause chain, not just the outermost throwable — mirrors
     * {@code EmbeddingSupport.classify}. LangChain4j's Ollama model reports an unreachable
     * daemon as {@code RuntimeException: java.net.ConnectException}; matching only the top
     * frame calls that {@code UNKNOWN}, the least useful answer for the most common failure a
     * developer hits.
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
        // ContentFilteredException extends InvalidRequestException, so it must be checked first.
        if (t instanceof ContentFilteredException)       return ErrorType.CONTENT_FILTERED;
        if (t instanceof AuthenticationException)        return ErrorType.AUTHENTICATION;
        if (t instanceof RateLimitException)             return ErrorType.RATE_LIMIT;
        if (t instanceof ModelNotFoundException)         return ErrorType.MODEL_NOT_FOUND;
        if (t instanceof UnsupportedFeatureException)    return ErrorType.UNSUPPORTED_OPERATION;
        if (t instanceof InvalidRequestException)        return ErrorType.INVALID_REQUEST;
        if (t instanceof TimeoutException)               return ErrorType.NETWORK;
        if (t instanceof UnresolvedModelServerException) return ErrorType.NETWORK;
        if (t instanceof java.io.IOException)            return ErrorType.NETWORK;
        if (t instanceof InternalServerException)        return ErrorType.SERVER_ERROR;
        if (t instanceof HttpException httpEx)           return classifyByStatusCode(httpEx.statusCode());
        // QUOTA_EXCEEDED and CONTEXT_LENGTH_EXCEEDED have no LangChain4j counterpart to match on
        // and are deliberately left unclassified rather than guessed from message text.
        return ErrorType.UNKNOWN;
    }

    private static ErrorType classifyByStatusCode(int statusCode) {
        return switch (statusCode) {
            case 401, 403 -> ErrorType.AUTHENTICATION;
            case 404      -> ErrorType.MODEL_NOT_FOUND;
            case 429      -> ErrorType.RATE_LIMIT;
            default -> {
                if (statusCode >= 500) yield ErrorType.SERVER_ERROR;
                if (statusCode >= 400) yield ErrorType.INVALID_REQUEST;
                yield ErrorType.UNKNOWN;
            }
        };
    }

    /**
     * Whether retrying the same request later has a chance of succeeding, read from
     * LangChain4j's own {@link RetriableException}/{@link NonRetriableException} split where
     * the thrown type says so, and from the status code for the plain {@link HttpException}
     * that split does not cover.
     */
    private static boolean isRetryable(Throwable t) {
        for (var c = t; c != null; c = c.getCause()) {
            if (c instanceof RetriableException) return true;
            if (c instanceof NonRetriableException) return false;
            if (c instanceof HttpException httpEx) {
                return httpEx.statusCode() == 429 || httpEx.statusCode() >= 500;
            }
            if (c.getCause() == c) break;
        }
        return false;
    }

    private static Integer statusCodeOf(Throwable t) {
        for (var c = t; c != null; c = c.getCause()) {
            if (c instanceof HttpException httpEx) return httpEx.statusCode();
            if (c.getCause() == c) break;
        }
        return null;
    }

    /**
     * Returns {@code id} if present, or a freshly generated one.
     *
     * <p>{@link dev.agenor.core.llm.LLMResponse#builder} requires a non-null id, and OpenAI and
     * Anthropic pass {@code ChatResponse.id()} straight through — a completed chat response that
     * carries no id metadata therefore failed with a bare {@link NullPointerException} from
     * inside the builder, on success, not on a provider failure. Ollama already synthesizes a
     * {@link UUID} for this reason; this gives the other two the same fallback instead of the
     * same defect twice, so the field means the same thing regardless of provider.
     *
     * @param id the id the client reported, or {@code null}
     * @return {@code id}, or a new random one if it was {@code null}
     */
    public static String orGeneratedId(String id) {
        return id != null ? id : UUID.randomUUID().toString();
    }
}
