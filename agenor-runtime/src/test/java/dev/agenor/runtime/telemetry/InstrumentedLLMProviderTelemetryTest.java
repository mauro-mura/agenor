package dev.agenor.runtime.telemetry;

import dev.agenor.core.llm.LLMProvider;
import dev.agenor.core.llm.LLMRequest;
import dev.agenor.core.llm.LLMResponse;
import dev.agenor.core.llm.StreamingChunk;
import dev.agenor.core.telemetry.AgenorTelemetry;
import dev.agenor.core.telemetry.Span;
import dev.agenor.core.telemetry.SpanBuilder;
import dev.agenor.core.telemetry.SpanScope;
import dev.agenor.core.telemetry.SpanStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code llm.model} names the model that answered. A request that names no model is answered by
 * the model the provider was built with, which {@code getDefaultModel()} does not report: it is
 * each provider class's own default, so a span labelled from it named a model nobody called.
 */
class InstrumentedLLMProviderTelemetryTest {

    private static final LLMRequest NO_MODEL = LLMRequest.builder().userMessage("hello").build();

    private final RecordingTelemetry telemetry = new RecordingTelemetry();

    @Test
    void chatLabelsTheSpanWithTheModelThatAnswered() {
        var provider = new InstrumentedLLMProvider(new ConfiguredProvider(), telemetry);

        provider.chat(NO_MODEL).join();

        assertThat(telemetry.single("llm.chat").stringAttrs)
                .containsEntry("llm.model", "configured");
    }

    @Test
    void chatStreamLabelsTheSpanWithTheModelThatAnswered() {
        var provider = new InstrumentedLLMProvider(new ConfiguredProvider(), telemetry);

        provider.chatStream(NO_MODEL, chunk -> { }).join();

        assertThat(telemetry.single("llm.chat.stream").stringAttrs)
                .containsEntry("llm.model", "configured");
    }

    /** Built with {@code configured}; its class default is something else, as in every adapter. */
    static class ConfiguredProvider implements LLMProvider {

        @Override
        public CompletableFuture<LLMResponse> chat(LLMRequest request) {
            return CompletableFuture.completedFuture(
                    LLMResponse.builder("r1", "configured").content("hi").build());
        }

        @Override
        public CompletableFuture<Void> chatStream(LLMRequest request,
                                                  Consumer<StreamingChunk> chunks) {
            chunks.accept(StreamingChunk.of("c1", "configured", "h", 0));
            chunks.accept(StreamingChunk.of("c1", "configured", "i", 1));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<List<String>> getAvailableModels() {
            return CompletableFuture.completedFuture(List.of("configured"));
        }

        @Override
        public String getProviderName() {
            return "Configured";
        }

        @Override
        public String getDefaultModel() {
            return "class-default";
        }
    }

    // -------------------------------------------------------------------------
    // Recording doubles
    // -------------------------------------------------------------------------

    static class RecordingTelemetry implements AgenorTelemetry {
        final List<RecordingSpan> spans = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public SpanBuilder spanBuilder(String name) {
            return new RecordingSpanBuilder(name, this);
        }

        RecordingSpan single(String name) {
            assertThat(spans).filteredOn(s -> s.name.equals(name)).hasSize(1);
            RecordingSpan span = spans.stream()
                    .filter(s -> s.name.equals(name))
                    .findFirst().orElseThrow();
            assertThat(span.ended).isTrue();
            return span;
        }
    }

    static class RecordingSpanBuilder implements SpanBuilder {
        private final String name;
        private final RecordingTelemetry telemetry;
        final Map<String, String> stringAttrs = new LinkedHashMap<>();

        RecordingSpanBuilder(String name, RecordingTelemetry telemetry) {
            this.name = name;
            this.telemetry = telemetry;
        }

        @Override public SpanBuilder setAttribute(String k, String v)  { stringAttrs.put(k, v); return this; }
        @Override public SpanBuilder setAttribute(String k, long v)    { return this; }
        @Override public SpanBuilder setAttribute(String k, boolean v) { return this; }

        @Override
        public Span startSpan() {
            var span = new RecordingSpan(name, new LinkedHashMap<>(stringAttrs));
            telemetry.spans.add(span);
            return span;
        }
    }

    static class RecordingSpan implements Span {
        final String name;
        final Map<String, String> stringAttrs;
        volatile SpanStatus status;
        volatile boolean ended;

        RecordingSpan(String name, Map<String, String> stringAttrs) {
            this.name = name;
            this.stringAttrs = stringAttrs;
        }

        @Override public Span setAttribute(String k, String v)  { stringAttrs.put(k, v); return this; }
        @Override public Span setAttribute(String k, long v)    { return this; }
        @Override public Span setAttribute(String k, boolean v) { return this; }
        @Override public Span setAttribute(String k, double v)  { return this; }
        @Override public Span recordException(Throwable t)      { return this; }
        @Override public Span setStatus(SpanStatus s)           { this.status = s; return this; }
        @Override public SpanScope makeCurrent() { return () -> {}; }
        @Override public void end()                             { this.ended = true; }
    }
}
