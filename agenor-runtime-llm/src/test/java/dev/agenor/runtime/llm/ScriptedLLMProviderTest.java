package dev.agenor.runtime.llm;

import dev.agenor.core.llm.FunctionCall;
import dev.agenor.core.llm.LLMException;
import dev.agenor.core.llm.LLMRequest;
import dev.agenor.core.llm.LLMResponse;
import dev.agenor.core.llm.StreamingChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.*;

/**
 * The behaviour beyond the {@code LLMProvider} contract, which
 * {@code ScriptedLLMProviderContractTest} in agenor-adapters checks against the same clauses as
 * the shipped adapters.
 */
class ScriptedLLMProviderTest {

    private static LLMRequest ask(String text) {
        return LLMRequest.builder().userMessage(text).build();
    }

    private static Throwable failureOf(CompletableFuture<?> future) {
        assertThat(future).isCompletedExceptionally();
        return catchThrowableOfType(ExecutionException.class, future::get).getCause();
    }

    @Test
    void repliesInTheOrderTheyWereQueued() {
        var llm = ScriptedLLMProvider.create()
                .replyCalls(FunctionCall.of("read_file", "{\"path\": \"notes.md\"}"))
                .reply("February.");

        LLMResponse first = llm.chat(ask("When?")).join();
        LLMResponse second = llm.chat(ask("And so?")).join();

        assertThat(first.functionCalls()).extracting(FunctionCall::name).containsExactly("read_file");
        assertThat(first.finishReason()).isEqualTo("tool_calls");
        assertThat(second.content()).isEqualTo("February.");
        assertThat(second.hasFunctionCalls()).isFalse();
        assertThat(second.isComplete()).isTrue();
    }

    @Test
    void anEmptyQueueFallsBackToTheFunctionOfTheRequest() {
        var llm = ScriptedLLMProvider.create()
                .reply("queued")
                .orElse(req -> req.messages().get(0).content().startsWith("Classify") ? "technical" : "draft");

        assertThat(llm.chat(ask("Classify this")).join().content()).isEqualTo("queued");
        assertThat(llm.chat(ask("Classify that")).join().content()).isEqualTo("technical");
        assertThat(llm.chat(ask("Write a reply")).join().content()).isEqualTo("draft");
    }

    @Test
    void aQueuedFailureFailsOnlyTheCallThatTakesIt() {
        LLMException down = LLMException.networkError("Ollama", "connection refused", null);
        var llm = ScriptedLLMProvider.create().fail(down).orElse(req -> "back");

        assertThat(failureOf(llm.chat(ask("one")))).isSameAs(down);
        assertThat(llm.chat(ask("two")).join().content()).isEqualTo("back");
    }

    @Test
    void anOrElseThatThrowsMakesAFailureThatLasts() {
        LLMException down = LLMException.networkError("Ollama", "connection refused", null);
        var llm = ScriptedLLMProvider.create().orElse(req -> { throw down; });

        assertThat(failureOf(llm.chat(ask("one")))).isSameAs(down);
        assertThat(failureOf(llm.chat(ask("two")))).isSameAs(down);
    }

    @Test
    void withNothingScriptedTheCallFailsAndSaysWhichRequest() {
        var llm = ScriptedLLMProvider.create().reply("only one");
        llm.chat(ask("first")).join();

        assertThat(failureOf(llm.chat(ask("second question"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("request #2")
                .hasMessageContaining("second question");
    }

    @Test
    void recordsEveryRequestFailedOnesIncluded() {
        var llm = ScriptedLLMProvider.create().reply("ok").fail(new RuntimeException("boom"));
        LLMRequest first = ask("one");
        LLMRequest second = ask("two");

        llm.chat(first);
        llm.chat(second);

        assertThat(llm.requests()).containsExactly(first, second);
    }

    @Test
    void estimatesUsageSoTelemetryIsNotZero() {
        var llm = ScriptedLLMProvider.create().reply("A reasonably long answer to count.");

        LLMResponse response = llm.chat(ask("A question long enough to count")).join();

        assertThat(response.usage().promptTokens()).isPositive();
        assertThat(response.usage().completionTokens()).isPositive();
        assertThat(response.usage().totalTokens())
                .isEqualTo(response.usage().promptTokens() + response.usage().completionTokens());
    }

    @Test
    void streamsTheQueuedTextWordByWordThenATerminalChunk() {
        var llm = ScriptedLLMProvider.create().reply("one two three");
        List<StreamingChunk> chunks = new ArrayList<>();

        llm.chatStream(ask("count"), chunks::add).join();

        assertThat(chunks).filteredOn(StreamingChunk::hasContent).extracting(StreamingChunk::content)
                .containsExactly("one ", "two ", "three");
        assertThat(chunks.get(chunks.size() - 1).isLast()).isTrue();
        assertThat(llm.requests()).hasSize(1);
    }

    @Test
    void aStreamedFunctionCallFailsInsteadOfDisappearing() {
        var llm = ScriptedLLMProvider.create().replyCalls(FunctionCall.of("read_file", "{}"));
        List<StreamingChunk> chunks = new ArrayList<>();

        assertThat(failureOf(llm.chatStream(ask("go"), chunks::add)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("use chat()");
        assertThat(chunks).isEmpty();
    }

    @Test
    void resetClearsQueueRequestsAndFallback() {
        var llm = ScriptedLLMProvider.create().reply("stale").orElse(req -> "stale too");
        llm.chat(ask("before")).join();

        llm.reset();

        assertThat(llm.requests()).isEmpty();
        assertThat(failureOf(llm.chat(ask("after")))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replyCallsNeedsACall() {
        assertThatThrownBy(() -> ScriptedLLMProvider.create().replyCalls())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
