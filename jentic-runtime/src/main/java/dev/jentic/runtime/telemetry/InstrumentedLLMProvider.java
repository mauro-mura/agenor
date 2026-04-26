package dev.jentic.runtime.telemetry;

import dev.jentic.core.llm.LLMProvider;
import dev.jentic.core.llm.LLMRequest;
import dev.jentic.core.llm.LLMResponse;
import dev.jentic.core.llm.StreamingChunk;
import dev.jentic.core.telemetry.JenticTelemetry;
import dev.jentic.core.telemetry.Span;
import dev.jentic.core.telemetry.SpanStatus;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * {@link LLMProvider} decorator that records OpenTelemetry spans for every
 * {@code chat} and {@code chatStream} call.
 *
 * <p>Emits the following spans:
 * <ul>
 *   <li>{@code llm.chat} — attributes: {@code llm.provider}, {@code llm.model},
 *       {@code llm.tokens.input}, {@code llm.tokens.output}, {@code llm.latency_ms}</li>
 *   <li>{@code llm.chat.stream} — same attributes plus {@code llm.stream.chunks}</li>
 * </ul>
 *
 * <p>When telemetry is {@link JenticTelemetry#noop()}, this decorator adds no overhead
 * beyond a single interface dispatch.
 *
 * @since 0.19.0
 */
public final class InstrumentedLLMProvider implements LLMProvider {

    private final LLMProvider delegate;
    private final JenticTelemetry telemetry;

    /**
     * Creates an {@code InstrumentedLLMProvider}.
     *
     * @param delegate  the underlying provider; never {@code null}
     * @param telemetry the telemetry instance; never {@code null}
     */
    public InstrumentedLLMProvider(LLMProvider delegate, JenticTelemetry telemetry) {
        this.delegate  = Objects.requireNonNull(delegate,  "delegate must not be null");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry must not be null");
    }

    @Override
    public CompletableFuture<LLMResponse> chat(LLMRequest request) {
        Span span = telemetry.spanBuilder("llm.chat")
                .setAttribute("llm.provider", delegate.getProviderName())
                .setAttribute("llm.model", resolveModel(request))
                .startSpan();

        long startMs = System.currentTimeMillis();

        return delegate.chat(request)
                .whenComplete((response, ex) -> {
                    if (ex != null) {
                        span.recordException(ex instanceof Exception e ? e : new Exception(ex))
                            .setStatus(SpanStatus.ERROR);
                    } else if (response != null) {
                        span.setAttribute("llm.latency_ms", System.currentTimeMillis() - startMs);
                        if (response.usage() != null) {
                            span.setAttribute("llm.tokens.input",  response.usage().promptTokens())
                                .setAttribute("llm.tokens.output", response.usage().completionTokens());
                        }
                        span.setStatus(SpanStatus.OK);
                    }
                    span.end();
                });
    }

    @Override
    public CompletableFuture<Void> chatStream(LLMRequest request, Consumer<StreamingChunk> chunkHandler) {
        Span span = telemetry.spanBuilder("llm.chat.stream")
                .setAttribute("llm.provider", delegate.getProviderName())
                .setAttribute("llm.model", resolveModel(request))
                .startSpan();

        long startMs = System.currentTimeMillis();
        var chunkCount = new AtomicInteger(0);

        Consumer<StreamingChunk> instrumentedHandler = chunk -> {
            chunkCount.incrementAndGet();
            chunkHandler.accept(chunk);
        };

        return delegate.chatStream(request, instrumentedHandler)
                .whenComplete((v, ex) -> {
                    span.setAttribute("llm.stream.chunks", (long) chunkCount.get())
                        .setAttribute("llm.latency_ms", System.currentTimeMillis() - startMs);
                    if (ex != null) {
                        span.recordException(ex instanceof Exception e ? e : new Exception(ex))
                            .setStatus(SpanStatus.ERROR);
                    } else {
                        span.setStatus(SpanStatus.OK);
                    }
                    span.end();
                });
    }

    @Override
    public String getProviderName() {
        return delegate.getProviderName();
    }

    @Override
    public CompletableFuture<List<String>> getAvailableModels() {
        return delegate.getAvailableModels();
    }

    @Override
    public boolean supportsFunctionCalling() {
        return delegate.supportsFunctionCalling();
    }

    @Override
    public boolean supportsStreaming() {
        return delegate.supportsStreaming();
    }

    @Override
    public String getDefaultModel() {
        return delegate.getDefaultModel();
    }

    // -------------------------------------------------------------------------

    private String resolveModel(LLMRequest request) {
        if (request != null && request.model() != null) {
            return request.model();
        }
        String defaultModel = delegate.getDefaultModel();
        return defaultModel != null ? defaultModel : "unknown";
    }
}
