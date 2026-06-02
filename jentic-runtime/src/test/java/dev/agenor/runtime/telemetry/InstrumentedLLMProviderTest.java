package dev.agenor.runtime.telemetry;

import dev.agenor.core.llm.LLMProvider;
import dev.agenor.core.llm.LLMRequest;
import dev.agenor.core.llm.LLMResponse;
import dev.agenor.core.llm.StreamingChunk;
import dev.agenor.core.telemetry.JenticTelemetry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifies that {@link InstrumentedLLMProvider} correctly delegates all calls to the
 * underlying provider and does not interfere with responses or exceptions.
 * Uses {@link JenticTelemetry#noop()} so no real OTel wiring is needed.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InstrumentedLLMProvider")
class InstrumentedLLMProviderTest {

    @Mock
    private LLMProvider delegate;

    private InstrumentedLLMProvider instrumented;

    private static final LLMRequest REQUEST = LLMRequest.builder()
            .model("gpt-mock")
            .userMessage("hello")
            .build();

    @BeforeEach
    void setUp() {
        instrumented = new InstrumentedLLMProvider(delegate, JenticTelemetry.noop());
    }

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("constructor rejects null delegate")
    void constructor_nullDelegate_throws() {
        org.assertj.core.api.Assertions.assertThatNullPointerException()
                .isThrownBy(() -> new InstrumentedLLMProvider(null, JenticTelemetry.noop()));
    }

    @Test
    @DisplayName("constructor rejects null telemetry")
    void constructor_nullTelemetry_throws() {
        org.assertj.core.api.Assertions.assertThatNullPointerException()
                .isThrownBy(() -> new InstrumentedLLMProvider(delegate, null));
    }

    // -------------------------------------------------------------------------
    // chat()
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("chat() delegates to underlying provider and returns its response")
    void chat_delegatesAndReturnsResponse() throws Exception {
        LLMResponse response = LLMResponse.builder("resp-1", "gpt-mock")
                .content("hello back")
                .build();
        when(delegate.chat(REQUEST)).thenReturn(CompletableFuture.completedFuture(response));

        LLMResponse result = instrumented.chat(REQUEST).get();

        assertThat(result).isSameAs(response);
        verify(delegate).chat(REQUEST);
    }

    @Test
    @DisplayName("chat() propagates exception from delegate")
    void chat_propagatesException() {
        RuntimeException boom = new RuntimeException("LLM unavailable");
        when(delegate.chat(any())).thenReturn(CompletableFuture.failedFuture(boom));

        var future = instrumented.chat(REQUEST);
        assertThatThrownBy(future::get).hasCauseInstanceOf(RuntimeException.class);
    }

    // -------------------------------------------------------------------------
    // chatStream()
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("chatStream() delegates to underlying provider")
    void chatStream_delegates() throws Exception {
        when(delegate.chatStream(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        Consumer<StreamingChunk> handler = chunk -> {};
        instrumented.chatStream(REQUEST, handler).get();

        verify(delegate).chatStream(eq(REQUEST), any());
    }

    @Test
    @DisplayName("chatStream() wraps chunk handler to count chunks")
    void chatStream_countsChunks() throws Exception {
        AtomicInteger delegateCalls = new AtomicInteger();

        when(delegate.chatStream(any(), any())).thenAnswer(inv -> {
            Consumer<StreamingChunk> wrappedHandler = inv.getArgument(1);
            // Simulate 3 chunks arriving
            for (int i = 0; i < 3; i++) {
                wrappedHandler.accept(new StreamingChunk(
                        "chunk-" + i, "mock-model", "token-" + i, null, i, null));
                delegateCalls.incrementAndGet();
            }
            return CompletableFuture.completedFuture(null);
        });

        AtomicInteger received = new AtomicInteger();
        instrumented.chatStream(REQUEST, chunk -> received.incrementAndGet()).get();

        assertThat(received.get()).isEqualTo(3);
    }

    // -------------------------------------------------------------------------
    // Metadata delegation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getProviderName() delegates to underlying provider")
    void getProviderName_delegates() {
        when(delegate.getProviderName()).thenReturn("mock-llm");
        assertThat(instrumented.getProviderName()).isEqualTo("mock-llm");
    }

    @Test
    @DisplayName("getDefaultModel() delegates to underlying provider")
    void getDefaultModel_delegates() {
        when(delegate.getDefaultModel()).thenReturn("gpt-mock");
        assertThat(instrumented.getDefaultModel()).isEqualTo("gpt-mock");
    }

    @Test
    @DisplayName("supportsFunctionCalling() delegates to underlying provider")
    void supportsFunctionCalling_delegates() {
        when(delegate.supportsFunctionCalling()).thenReturn(true);
        assertThat(instrumented.supportsFunctionCalling()).isTrue();
    }

    @Test
    @DisplayName("supportsStreaming() delegates to underlying provider")
    void supportsStreaming_delegates() {
        when(delegate.supportsStreaming()).thenReturn(false);
        assertThat(instrumented.supportsStreaming()).isFalse();
    }

    @Test
    @DisplayName("getAvailableModels() delegates to underlying provider")
    void getAvailableModels_delegates() throws Exception {
        List<String> models = List.of("gpt-4o", "gpt-4o-mini");
        when(delegate.getAvailableModels()).thenReturn(CompletableFuture.completedFuture(models));

        assertThat(instrumented.getAvailableModels().get()).isEqualTo(models);
    }
}
