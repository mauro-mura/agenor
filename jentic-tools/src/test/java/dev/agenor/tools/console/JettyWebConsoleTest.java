package dev.agenor.tools.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.agenor.runtime.AgenorRuntime;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.agenor.runtime.agent.BaseAgent;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JettyWebConsoleTest {

    private static JettyWebConsole console;
    private static AgenorRuntime runtime;
    private static HttpClient httpClient;
    private static ObjectMapper objectMapper;
    private static final int TEST_PORT = 8889;
    private static final String BASE_URL = "http://localhost:" + TEST_PORT;

    @BeforeAll
    static void setUp() throws Exception {
        runtime = AgenorRuntime.builder().build();
        runtime.registerAgent(new TestAgent("agent-1", "Test Agent 1"));
        runtime.registerAgent(new TestAgent("agent-2", "Test Agent 2"));

        console = JettyWebConsole.builder()
                .port(TEST_PORT)
                .runtime(runtime)
                .build();

        console.start().join();
        Thread.sleep(500);

        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        objectMapper = new ObjectMapper();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (console != null) console.stop().join();
        if (runtime != null) runtime.stop().join();
    }

    @Test
    @Order(1)
    void shouldStartConsole() {
        assertThat(console.isRunning()).isTrue();
        assertThat(console.getPort()).isEqualTo(TEST_PORT);
        assertThat(console.getBaseUrl()).isEqualTo(BASE_URL);
    }

    @Test
    @Order(2)
    void shouldGetAgents() throws Exception {
        var response = get("/api/agents");
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("success").asBoolean()).isTrue();
        assertThat(json.get("data").size()).isEqualTo(2);
    }

    @Test
    @Order(3)
    void shouldGetAgent() throws Exception {
        var response = get("/api/agents/agent-1");
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("data").get("id").asText()).isEqualTo("agent-1");
    }

    @Test
    @Order(4)
    void shouldReturn404ForUnknownAgent() throws Exception {
        var response = get("/api/agents/unknown");
        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    @Order(5)
    void shouldStartAgent() throws Exception {
        var response = post("/api/agents/agent-1/start");
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("data").get("status").asText()).isEqualTo("started");
    }

    @Test
    @Order(6)
    void shouldStopAgent() throws Exception {
        var response = post("/api/agents/agent-1/stop");
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("data").get("status").asText()).isEqualTo("stopped");
    }

    @Test
    @Order(7)
    void shouldGetStats() throws Exception {
        var response = get("/api/stats");
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("data").get("totalAgents").asInt()).isEqualTo(2);
    }

    @Test
    @Order(8)
    void shouldGetHealth() throws Exception {
        var response = get("/api/health");
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("data").has("status")).isTrue();
    }

    @Test
    @Order(9)
    void shouldConnectWebSocket() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        List<String> messages = new ArrayList<>();

        WebSocket ws = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + TEST_PORT + "/ws"),
                        new WebSocket.Listener() {
                            @Override
                            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                                messages.add(data.toString());
                                latch.countDown();
                                return WebSocket.Listener.super.onText(webSocket, data, last);
                            }
                        })
                .get(5, TimeUnit.SECONDS);

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(messages).isNotEmpty();

        JsonNode msg = objectMapper.readTree(messages.get(0));
        assertThat(msg.get("type").asText()).isEqualTo("connection.established");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder().uri(URI.create(BASE_URL + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder().uri(URI.create(BASE_URL + path))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static class TestAgent extends BaseAgent {
        public TestAgent(String id, String name) {
            super(id, name);
        }
    }
}
