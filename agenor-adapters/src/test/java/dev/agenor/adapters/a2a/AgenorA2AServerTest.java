package dev.agenor.adapters.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.server.tasks.TaskUpdater;
import io.a2a.spec.TextPart;
import io.a2a.util.Utils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AgenorA2AServer")
class AgenorA2AServerTest {

    private final AgenorA2AServer server = AgenorA2AServer.withExecutor(A2AAdapterConfig.create()
            .agentName("echo")
            .agentDescription("answers with what it was sent")
            .baseUrl("http://localhost:9999")
            .streamingEnabled(true), new Echo());

    @AfterEach
    void close() {
        server.close();
    }

    @Test
    @DisplayName("serves the card the config builds")
    void servesTheCard() throws Exception {
        JsonNode card = json(server.agentCardJson());

        assertThat(card.path("name").asText()).isEqualTo("echo");
        assertThat(card.path("url").asText()).isEqualTo("http://localhost:9999");
        assertThat(card.path("capabilities").path("streaming").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("message/send completes a task, and tasks/get finds it")
    void sendThenGet() throws Exception {
        JsonNode sent = json(server.handle(send("message/send", 1, "hello")));

        JsonNode task = sent.path("result");
        assertThat(sent.path("id").asInt()).isEqualTo(1);
        assertThat(task.path("status").path("state").asText()).isEqualTo("completed");
        assertThat(task.path("artifacts").get(0).path("parts").get(0).path("text").asText())
                .isEqualTo("echo: hello");

        JsonNode got = json(server.handle("""
                {"jsonrpc":"2.0","id":"g","method":"tasks/get","params":{"id":"%s"}}"""
                .formatted(task.path("id").asText())));
        assertThat(got.path("id").asText()).isEqualTo("g");
        assertThat(got.path("result").path("status").path("state").asText()).isEqualTo("completed");
    }

    @Test
    @DisplayName("message/stream is recognised, and its events end with the completed task")
    void streams() throws Exception {
        String body = send("message/stream", 2, "hello");
        assertThat(server.isStreamingRequest(body)).isTrue();
        assertThat(server.isStreamingRequest(send("message/send", 3, "hello"))).isFalse();

        List<JsonNode> events = collect(server.handleStream(body));

        assertThat(events).isNotEmpty().allSatisfy(e -> assertThat(e.path("id").asInt()).isEqualTo(2));
        assertThat(events.getLast().path("result").path("status").path("state").asText())
                .isEqualTo("completed");
    }

    @Test
    @DisplayName("a body that is not JSON is a parse error, with a null id")
    void unreadableBody() throws Exception {
        JsonNode response = json(server.handle("not json"));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32700);
        assertThat(response.get("id").isNull()).isTrue();
        assertThat(server.isStreamingRequest("not json")).isFalse();
    }

    @Test
    @DisplayName("a request without a method is invalid, and keeps its id")
    void noMethod() throws Exception {
        JsonNode response = json(server.handle("{\"jsonrpc\":\"2.0\",\"id\":7}"));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32600);
        assertThat(response.path("id").asInt()).isEqualTo(7);
    }

    @Test
    @DisplayName("an unknown method is method-not-found, and keeps its id")
    void unknownMethod() throws Exception {
        JsonNode response = json(server.handle("{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"method\":\"tasks/list\"}"));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32601);
        assertThat(response.path("id").asText()).isEqualTo("x");
    }

    @Test
    @DisplayName("each entry point answers the other's method with an error, not a hang")
    void wrongEntryPoint() throws Exception {
        JsonNode viaHandle = json(server.handle(send("message/stream", 4, "hello")));
        assertThat(viaHandle.path("error").path("code").asInt()).isEqualTo(-32600);

        List<JsonNode> viaStream = collect(server.handleStream(send("message/send", 5, "hello")));
        assertThat(viaStream).singleElement()
                .satisfies(e -> assertThat(e.path("error").path("code").asInt()).isEqualTo(-32600));
    }

    @Test
    @DisplayName("a blocking message/send waits for an agent slower than the SDK's 5 s, and gets it completed")
    void blockingSendOnSlowAgent() throws Exception {
        try (var slow = slowServer()) {
            JsonNode task = json(slow.handle(send("message/send", 10, "hello"))).path("result");

            assertThat(task.path("status").path("state").asText()).isEqualTo("completed");
            assertThat(task.path("artifacts").get(0).path("parts").get(0).path("text").asText())
                    .isEqualTo("slow: hello");
        }
    }

    @Test
    @DisplayName("a non-blocking message/send returns at once, and tasks/get later finds the task completed")
    void nonBlockingSendOnSlowAgent() throws Exception {
        try (var slow = slowServer()) {
            JsonNode task = json(slow.handle(send("message/send", 11, "hello")
                    .replace("\"params\":{", "\"params\":{\"configuration\":{\"blocking\":false},"))).path("result");
            assertThat(task.path("status").path("state").asText()).isNotEqualTo("completed");

            String get = """
                    {"jsonrpc":"2.0","id":"g","method":"tasks/get","params":{"id":"%s"}}"""
                    .formatted(task.path("id").asText());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Slow.DELAY_SECONDS + 5);
            String state;
            do {
                Thread.sleep(200);
                state = json(slow.handle(get)).path("result").path("status").path("state").asText();
            } while (!"completed".equals(state) && System.nanoTime() < deadline);
            assertThat(state).isEqualTo("completed");
        }
    }

    @Test
    @DisplayName("message/stream on an agent slower than 5 s ends with the completed task")
    void streamOnSlowAgent() throws Exception {
        try (var slow = slowServer()) {
            List<JsonNode> events = collect(slow.handleStream(send("message/stream", 12, "hello")),
                    Slow.DELAY_SECONDS + 5);

            assertThat(events.getLast().path("result").path("status").path("state").asText())
                    .isEqualTo("completed");
        }
    }

    private static AgenorA2AServer slowServer() {
        return AgenorA2AServer.withExecutor(A2AAdapterConfig.create()
                .agentName("slow")
                .agentDescription("answers after the SDK has stopped waiting")
                .baseUrl("http://localhost:9998")
                .streamingEnabled(true)
                .timeout(Duration.ofSeconds(30)), new Slow());
    }

    private static String send(String method, int id, String text) {
        return """
                {"jsonrpc":"2.0","id":%d,"method":"%s","params":{"message":{"kind":"message",
                "messageId":"m-%d","role":"user","parts":[{"kind":"text","text":"%s"}]}}}"""
                .formatted(id, method, id, text);
    }

    private static JsonNode json(String text) throws Exception {
        return Utils.OBJECT_MAPPER.readTree(text);
    }

    private static List<JsonNode> collect(Flow.Publisher<String> events) throws Exception {
        return collect(events, 5);
    }

    private static List<JsonNode> collect(Flow.Publisher<String> events, long seconds) throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        var ended = new CountDownLatch(1);
        events.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(String event) {
                received.add(event);
            }

            @Override
            public void onError(Throwable error) {
                ended.countDown();
            }

            @Override
            public void onComplete() {
                ended.countDown();
            }
        });
        assertThat(ended.await(seconds, TimeUnit.SECONDS)).as("the stream ended").isTrue();
        var nodes = new java.util.ArrayList<JsonNode>();
        for (String event : received) {
            nodes.add(json(event));
        }
        return nodes;
    }

    /** Completes every task with its input echoed as the artifact. */
    private static final class Echo implements AgentExecutor {
        @Override
        public void execute(RequestContext context, EventQueue queue) {
            var updater = new TaskUpdater(context, queue);
            if (context.getTask() == null) {
                updater.submit();
            }
            updater.startWork();
            updater.addArtifact(List.of(new TextPart("echo: " + context.getUserInput(" "), null)), null, null, null);
            updater.complete();
        }

        @Override
        public void cancel(RequestContext context, EventQueue queue) {
        }
    }

    /** Like {@link Echo}, but answers after the 5 s the SDK holds a blocking call for. */
    private static final class Slow implements AgentExecutor {
        static final int DELAY_SECONDS = 6;

        @Override
        public void execute(RequestContext context, EventQueue queue) {
            var updater = new TaskUpdater(context, queue);
            if (context.getTask() == null) {
                updater.submit();
            }
            updater.startWork();
            try {
                Thread.sleep(Duration.ofSeconds(DELAY_SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            updater.addArtifact(List.of(new TextPart("slow: " + context.getUserInput(" "), null)), null, null, null);
            updater.complete();
        }

        @Override
        public void cancel(RequestContext context, EventQueue queue) {
        }
    }
}
