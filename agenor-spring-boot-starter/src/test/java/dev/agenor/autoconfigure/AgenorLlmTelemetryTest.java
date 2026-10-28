package dev.agenor.autoconfigure;

import com.sun.net.httpserver.HttpServer;
import dev.agenor.adapters.telemetry.OtelAgenorTelemetry;
import dev.agenor.core.llm.LLMProvider;
import dev.agenor.core.llm.LLMRequest;
import dev.agenor.core.telemetry.AgenorTelemetry;
import dev.agenor.runtime.AgenorRuntime;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.llm.LLMAgent;
import dev.agenor.runtime.llm.ScriptedLLMProvider;
import dev.agenor.runtime.telemetry.InstrumentedLLMProvider;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The starter's {@link LLMProvider} emits an {@code llm.chat} span for every call, whoever makes
 * it (issue #5). Telemetry used to be attached at registration, to the provider an
 * {@link LLMAgent} was given through {@code setLLMProvider}; an agent that calls the provider it
 * holds, as every downstream agent does, and a {@link BaseAgent}, which is not an
 * {@code LLMAgent} at all, produced none.
 *
 * <p>The provider is the starter's real {@code OllamaProvider}, against a JDK server that answers
 * {@code /api/chat} the way Ollama does, so the token counts are the ones a real response carries.
 * The model is not {@code OllamaProvider}'s class default, so a span labelled from
 * {@code getDefaultModel()} instead of the model that answered would fail.
 */
class AgenorLlmTelemetryTest {

    private static final String OLLAMA_REPLY = """
            {"model":"qwen2.5:7b","created_at":"2026-10-28T12:00:00Z",
             "message":{"role":"assistant","content":"pong"},
             "done":true,"done_reason":"stop","prompt_eval_count":12,"eval_count":3}""";

    private HttpServer ollama;

    @BeforeEach
    void startOllama() throws IOException {
        ollama = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        ollama.createContext("/api/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = OLLAMA_REPLY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        ollama.start();
    }

    @AfterEach
    void stopOllama() {
        ollama.stop(0);
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgenorAutoConfiguration.class))
                .withUserConfiguration(Spans.class)
                .withPropertyValues(
                        "agenor.llm.provider=ollama",
                        "agenor.llm.model=qwen2.5:7b",
                        "agenor.llm.base-url=http://localhost:" + ollama.getAddress().getPort());
    }

    @Test
    void everyCallThroughTheStarterProviderEmitsAnLlmChatSpan() {
        runner().run(ctx -> {
            LLMProvider provider = ctx.getBean(LLMProvider.class);
            AgenorRuntime runtime = ctx.getBean(AgenorRuntime.class);
            var llmAgent = new HoldingLlmAgent(provider);
            var baseAgent = new HoldingBaseAgent(provider);
            runtime.registerAgent(llmAgent);
            runtime.registerAgent(baseAgent);

            llmAgent.ask("ping");
            baseAgent.ask("ping");

            List<SpanData> spans = ctx.getBean(InMemorySpanExporter.class)
                    .getFinishedSpanItems().stream()
                    .filter(span -> span.getName().equals("llm.chat"))
                    .toList();
            assertThat(spans).hasSize(2).allSatisfy(span -> {
                var attributes = span.getAttributes();
                assertThat(attributes.get(stringKey("llm.provider"))).isEqualTo("Ollama");
                assertThat(attributes.get(stringKey("llm.model"))).isEqualTo("qwen2.5:7b");
                assertThat(attributes.get(longKey("llm.tokens.input"))).isEqualTo(12L);
                assertThat(attributes.get(longKey("llm.tokens.output"))).isEqualTo(3L);
            });
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "anthropic", "ollama"})
    void eachStarterProviderIsWrappedWhenTelemetryIsOn(String provider) {
        runner().withPropertyValues("agenor.llm.provider=" + provider,
                        "agenor.llm.api-key=test-key")
                .run(ctx -> assertThat(ctx.getBean(LLMProvider.class))
                        .isInstanceOf(InstrumentedLLMProvider.class));
    }

    /** Telemetry off (the default exporter is {@code none}): the bean is the provider itself. */
    @Test
    void theProviderIsNotWrappedWhenTelemetryIsOff() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgenorAutoConfiguration.class))
                .withPropertyValues("agenor.llm.provider=ollama")
                .run(ctx -> {
                    assertThat(ctx.getBean(AgenorTelemetry.class)).isSameAs(AgenorTelemetry.noop());
                    assertThat(ctx.getBean(LLMProvider.class))
                            .isNotInstanceOf(InstrumentedLLMProvider.class);
                });
    }

    /** A provider the application declares is its own to wrap: the starter leaves it alone. */
    @Test
    void aDeclaredProviderIsNotWrapped() {
        runner().withBean(LLMProvider.class, () -> ScriptedLLMProvider.create().orElse(r -> "ok"))
                .run(ctx -> assertThat(ctx.getBean(LLMProvider.class))
                        .isInstanceOf(ScriptedLLMProvider.class));
    }

    /**
     * Real telemetry with the spans kept in memory. One exporter per context: closing the context
     * closes the telemetry, and the exporter with it, so a static one would go silent after the
     * first.
     */
    @Configuration(proxyBeanMethods = false)
    static class Spans {

        @Bean
        InMemorySpanExporter spanExporter() {
            return InMemorySpanExporter.create();
        }

        @Bean
        AgenorTelemetry telemetry(InMemorySpanExporter spanExporter) {
            var tracer = SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                    .build();
            return new OtelAgenorTelemetry(
                    OpenTelemetrySdk.builder().setTracerProvider(tracer).build());
        }
    }

    /** Calls the provider it was constructed with, never {@code setLLMProvider}. */
    static class HoldingLlmAgent extends LLMAgent {

        private final LLMProvider llm;

        HoldingLlmAgent(LLMProvider llm) {
            super("holding-llm-agent", "HoldingLlmAgent");
            this.llm = llm;
        }

        String ask(String question) {
            return llm.chat(LLMRequest.builder().userMessage(question).build()).join().content();
        }
    }

    /** Not an {@link LLMAgent}: nothing at registration knows it calls an LLM. */
    static class HoldingBaseAgent extends BaseAgent {

        private final LLMProvider llm;

        HoldingBaseAgent(LLMProvider llm) {
            super("holding-base-agent", "HoldingBaseAgent");
            this.llm = llm;
        }

        String ask(String question) {
            return llm.chat(LLMRequest.builder().userMessage(question).build()).join().content();
        }
    }
}
