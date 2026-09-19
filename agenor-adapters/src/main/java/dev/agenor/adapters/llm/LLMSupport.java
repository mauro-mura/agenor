package dev.agenor.adapters.llm;

import dev.agenor.core.llm.LLMRequest;

import java.util.concurrent.CompletableFuture;
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
}
