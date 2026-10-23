package dev.agenor.runtime.llm;

import dev.agenor.core.llm.FunctionCall;
import dev.agenor.core.llm.LLMMessage;
import dev.agenor.core.llm.LLMProvider;
import dev.agenor.core.llm.LLMRequest;
import dev.agenor.core.llm.LLMResponse;
import dev.agenor.core.llm.StreamingChunk;
import dev.agenor.runtime.memory.llm.SimpleTokenEstimator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * An {@link LLMProvider} that answers from a script instead of a model: for tests, and for
 * examples that must run without a network or an API key.
 *
 * <p>It is to {@code LLMProvider} what {@code InMemoryMessageDispatcher} is to
 * {@code MessageDispatcher}: a real implementation of the contract, held to the same contract
 * test as the shipped adapters, that happens to need nothing outside the JVM.
 *
 * <p>Replies are taken from a queue, in order; when the queue is empty, from the function given
 * to {@link #orElse}. Every request is recorded, failed ones included:
 * <pre>{@code
 * var llm = ScriptedLLMProvider.create()
 *         .replyCalls(FunctionCall.of("read_file", "{\"path\": \"notes.md\"}"))
 *         .reply("The notes say February.");
 *
 * // ... the code under test asks, runs read_file, and asks again with its result ...
 *
 * assertThat(llm.requests()).hasSize(2);
 * assertThat(llm.requests().get(1).messages()).anyMatch(LLMMessage::isFunction);
 * }</pre>
 *
 * <p>A reply can depend on the request, and a failure can last:
 * <pre>{@code
 * llm.orElse(req -> req.messages().get(0).content().startsWith("Classify") ? "technical" : "draft");
 * llm.orElse(req -> { throw LLMException.networkError("Ollama", "down", null); });   // every call fails
 * llm.fail(LLMException.rateLimit("OpenAI", "slow down"));                           // only the next one
 * }</pre>
 *
 * <p>With nothing queued and no {@code orElse}, a call fails with an
 * {@link IllegalStateException} that says which request had no reply, rather than inventing one.
 *
 * <p>{@link #chatStream} takes from the same queue and delivers the text word by word, then a
 * terminal chunk. A queued function call fails the stream instead: a {@link StreamingChunk}
 * carries text only. Usage is estimated with {@link SimpleTokenEstimator}, so token counts in
 * telemetry are plausible rather than zero. The model is the one the request names, else
 * {@value #MODEL}.
 *
 * <p>Thread-safe.
 *
 * @since 0.39.0
 */
public final class ScriptedLLMProvider implements LLMProvider {

    /** The model a response is labelled with when the request names none. */
    public static final String MODEL = "scripted";

    private static final SimpleTokenEstimator ESTIMATOR = new SimpleTokenEstimator();

    private record Entry(String text, List<FunctionCall> calls, Throwable failure) {
    }

    private final Deque<Entry> script = new ArrayDeque<>();
    private final List<LLMRequest> requests = new ArrayList<>();
    private Function<LLMRequest, String> fallback;

    private ScriptedLLMProvider() {
    }

    /** A provider with an empty script. */
    public static ScriptedLLMProvider create() {
        return new ScriptedLLMProvider();
    }

    /** Queues a text reply. */
    public synchronized ScriptedLLMProvider reply(String text) {
        script.add(new Entry(Objects.requireNonNull(text, "text"), List.of(), null));
        return this;
    }

    /** Queues a reply that asks for these function calls, with no text. */
    public synchronized ScriptedLLMProvider replyCalls(FunctionCall... calls) {
        if (calls.length == 0) {
            throw new IllegalArgumentException("replyCalls needs at least one call; use reply(\"\") for an empty answer");
        }
        script.add(new Entry("", List.of(calls), null));
        return this;
    }

    /** Queues a failure: the call that takes it completes exceptionally with {@code failure}. */
    public synchronized ScriptedLLMProvider fail(Throwable failure) {
        script.add(new Entry(null, List.of(), Objects.requireNonNull(failure, "failure")));
        return this;
    }

    /**
     * Answers from {@code reply} once the queue is empty, replacing any earlier one. If it throws,
     * the call completes exceptionally with what it threw, which makes a failure that lasts.
     */
    public synchronized ScriptedLLMProvider orElse(Function<LLMRequest, String> reply) {
        this.fallback = Objects.requireNonNull(reply, "reply");
        return this;
    }

    /** The requests received so far, in order, failed ones included. */
    public synchronized List<LLMRequest> requests() {
        return List.copyOf(requests);
    }

    /** Clears the queue, the recorded requests and the {@code orElse}: for a provider shared across tests. */
    public synchronized void reset() {
        script.clear();
        requests.clear();
        fallback = null;
    }

    @Override
    public CompletableFuture<LLMResponse> chat(LLMRequest request) {
        Entry entry;
        int number;
        synchronized (this) {
            requests.add(request);
            number = requests.size();
            try {
                entry = next(request, number);
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        }
        if (entry.failure() != null) {
            return CompletableFuture.failedFuture(entry.failure());
        }
        int prompt = ESTIMATOR.estimateTokens(request.messages());
        int completion = ESTIMATOR.estimateTokens(entry.text());
        return CompletableFuture.completedFuture(LLMResponse.builder("scripted-" + number, modelFor(request))
                .role(LLMMessage.Role.ASSISTANT)
                .content(entry.text())
                .functionCalls(entry.calls())
                .finishReason(entry.calls().isEmpty() ? "stop" : "tool_calls")
                .usage(prompt, completion, prompt + completion)
                .build());
    }

    @Override
    public CompletableFuture<Void> chatStream(LLMRequest request, Consumer<StreamingChunk> chunkHandler) {
        Entry entry;
        int number;
        synchronized (this) {
            requests.add(request);
            number = requests.size();
            try {
                entry = next(request, number);
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        }
        if (entry.failure() != null) {
            return CompletableFuture.failedFuture(entry.failure());
        }
        if (!entry.calls().isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Request #" + number + " was streamed, but its scripted reply is a function call, "
                            + "which a StreamingChunk cannot carry; use chat()"));
        }
        String id = "scripted-" + number;
        String model = modelFor(request);
        int index = 0;
        for (String word : entry.text().split("(?<=\\s)")) {
            if (!word.isEmpty()) {
                chunkHandler.accept(StreamingChunk.of(id, model, word, index++));
            }
        }
        chunkHandler.accept(StreamingChunk.of(id, model, "", "stop", index));
        return CompletableFuture.completedFuture(null);
    }

    private Entry next(LLMRequest request, int number) {
        Entry queued = script.poll();
        if (queued != null) {
            return queued;
        }
        if (fallback == null) {
            throw new IllegalStateException("No reply scripted for request #" + number
                    + " and no orElse; its last message was: " + lastMessage(request));
        }
        return new Entry(Objects.requireNonNull(fallback.apply(request), "orElse returned null"), List.of(), null);
    }

    private static String lastMessage(LLMRequest request) {
        List<LLMMessage> messages = request.messages();
        LLMMessage last = messages.get(messages.size() - 1);
        String content = String.valueOf(last.content());
        return last.role() + " \"" + (content.length() > 80 ? content.substring(0, 80) + "…" : content) + "\"";
    }

    private static String modelFor(LLMRequest request) {
        String requested = request.model();
        return requested != null && !requested.isBlank() ? requested : MODEL;
    }

    @Override
    public CompletableFuture<List<String>> getAvailableModels() {
        return CompletableFuture.completedFuture(List.of(MODEL));
    }

    @Override
    public String getProviderName() {
        return "Scripted";
    }

    @Override
    public String getDefaultModel() {
        return MODEL;
    }
}
