package dev.agenor.tools.console;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agenor.runtime.AgenorRuntime;
import dev.agenor.runtime.agent.BaseAgent;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive tests for WebConsoleServer.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebConsoleServerTest {

    private static WebConsoleServer console;
    private static AgenorRuntime runtime;
    private static HttpClient httpClient;
    private static ObjectMapper objectMapper;
    private static final int TEST_PORT = 8888;
    private static final String BASE_URL = "http://localhost:" + TEST_PORT;

    @BeforeAll
    static void setUp() throws Exception {
        // Create runtime
        runtime = AgenorRuntime.builder().build();

        // Create and register test agents
        runtime.registerAgent(new TestAgent("test-agent-1", "Test Agent 1"));
        runtime.registerAgent(new TestAgent("test-agent-2", "Test Agent 2"));

        // Start console
        console = WebConsoleServer.builder()
            .port(TEST_PORT)
            .runtime(runtime)
            .build();

        console.start();

        // Wait for server to start
        Thread.sleep(1000);

        httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

        objectMapper = new ObjectMapper();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (console != null) {
            console.stop();
        }
        if (runtime != null) {
            runtime.stop().join();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Should start web console successfully")
    void testServerStart() {
        assertThat(console.isRunning()).isTrue();
        assertThat(console.getPort()).isEqualTo(TEST_PORT);
    }

    @Test
    @Order(2)
    @DisplayName("Should serve static HTML page")
    void testServeStaticPage() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/"))
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
            .isPresent()
            .get()
            .asString()
            .contains("text/html");
    }

    @Test
    @Order(3)
    @DisplayName("GET /api/agents - Should return list of agents")
    void testGetAgents() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/api/agents"))
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("success").asBoolean()).isTrue();
        assertThat(json.has("data")).isTrue();
        assertThat(json.get("data").size()).isEqualTo(2);
    }

    @Test
    @Order(4)
    @DisplayName("GET /api/agents/{id} - Should return agent details")
    void testGetAgent() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/api/agents/test-agent-1"))
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("success").asBoolean()).isTrue();

        JsonNode data = json.get("data");
        assertThat(data.get("id").asText()).isEqualTo("test-agent-1");
        assertThat(data.get("name").asText()).isEqualTo("Test Agent 1");
    }

    @Test
    @Order(5)
    @DisplayName("GET /api/agents/{id} - Should return 404 for unknown agent")
    void testGetAgentNotFound() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/api/agents/unknown"))
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);

        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("success").asBoolean()).isFalse();
    }

    @Test
    @Order(6)
    @DisplayName("POST /api/agents/{id}/start - Should start agent")
    void testStartAgent() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/api/agents/test-agent-1/start"))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

        HttpResponse<String> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("success").asBoolean()).isTrue();

        JsonNode data = json.get("data");
        assertThat(data.get("status").asText()).isEqualTo("started");
    }

    @Test
    @Order(7)
    @DisplayName("POST /api/agents/{id}/stop - Should stop agent")
    void testStopAgent() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/api/agents/test-agent-1/stop"))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

        HttpResponse<String> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("success").asBoolean()).isTrue();

        JsonNode data = json.get("data");
        assertThat(data.get("status").asText()).isEqualTo("stopped");
    }

    @Test
    @Order(8)
    @DisplayName("GET /api/stats - Should return runtime statistics")
    void testGetStats() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/api/stats"))
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("success").asBoolean()).isTrue();

        JsonNode data = json.get("data");
        assertThat(data.has("totalAgents")).isTrue();
        assertThat(data.has("activeAgents")).isTrue();
        assertThat(data.has("runtime")).isTrue();
        assertThat(data.get("totalAgents").asInt()).isEqualTo(2);
    }

    @Test
    @Order(9)
    @DisplayName("GET /api/health - Should return health status")
    void testGetHealth() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/api/health"))
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("success").asBoolean()).isTrue();

        JsonNode data = json.get("data");
        assertThat(data.has("status")).isTrue();
        assertThat(data.has("timestamp")).isTrue();
    }

    @Test
    @Order(10)
    @DisplayName("GET /api/messages - Should return empty messages list")
    void testGetMessages() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/api/messages"))
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("success").asBoolean()).isTrue();
        assertThat(json.get("data").size()).isEqualTo(0);
    }

    @Test
    @Order(11)
    @DisplayName("Should handle CORS preflight requests")
    void testCORSPreflight() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/api/agents"))
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .build();

        HttpResponse<String> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
            .isPresent()
            .get()
            .isEqualTo("*");
    }

    @Test
    @Order(12)
    @DisplayName("WebSocket - Should connect successfully")
    void testWebSocketConnection() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        List<String> messages = new ArrayList<>();

        WebSocket ws = HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .buildAsync(URI.create("ws://localhost:" + TEST_PORT + "/ws"),
                new WebSocket.Listener() {
                    @Override
                    public CompletableFuture<?> onText(WebSocket webSocket,
                                                       CharSequence data,
                                                       boolean last) {
                        messages.add(data.toString());
                        latch.countDown();
                        return WebSocket.Listener.super.onText(webSocket, data, last).toCompletableFuture();
                    }
                })
            .get(5, TimeUnit.SECONDS);

        // Wait for welcome message
        boolean received = latch.await(5, TimeUnit.SECONDS);

        assertThat(received).isTrue();
        assertThat(messages).isNotEmpty();

        // Verify welcome message
        JsonNode message = objectMapper.readTree(messages.get(0));
        assertThat(message.get("type").asText()).isEqualTo("connection.established");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "Test complete");
    }

    @Test
    @Order(13)
    @DisplayName("Should handle invalid endpoint gracefully")
    void testInvalidEndpoint() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/api/invalid"))
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);

        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("success").asBoolean()).isFalse();
    }

    /**
     * Simple test agent implementation
     */
    static class TestAgent extends BaseAgent {
        public TestAgent(String id, String name) {
            super(id, name);
        }
    }
}
