package dev.agenor.adapters.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.a2a.server.ServerCallContext;
import io.a2a.server.TransportMetadata;
import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.auth.UnauthenticatedUser;
import io.a2a.server.events.InMemoryQueueManager;
import io.a2a.server.requesthandlers.DefaultRequestHandler;
import io.a2a.server.tasks.BasePushNotificationSender;
import io.a2a.server.tasks.InMemoryPushNotificationConfigStore;
import io.a2a.server.tasks.InMemoryTaskStore;
import io.a2a.spec.AgentCard;
import io.a2a.spec.CancelTaskRequest;
import io.a2a.spec.GetTaskRequest;
import io.a2a.spec.JSONRPCErrorResponse;
import io.a2a.spec.MethodNotFoundError;
import io.a2a.spec.NonStreamingJSONRPCRequest;
import io.a2a.spec.SendMessageRequest;
import io.a2a.spec.SendStreamingMessageRequest;
import io.a2a.spec.SendStreamingMessageResponse;
import io.a2a.spec.TransportProtocol;
import io.a2a.transport.jsonrpc.context.JSONRPCContextKeys;
import io.a2a.transport.jsonrpc.handler.JSONRPCHandler;
import io.a2a.util.Utils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;

/**
 * A real A2A 0.3 server for tests: the SDK's own request handler and JSON-RPC handler, served
 * over HTTP by the JDK, with {@code message/stream} as Server-Sent Events.
 *
 * <p>One agent per server, at the root of its own port: the SDK's card resolver looks for the card
 * at {@code /.well-known/agent-card.json} of the host, whatever path the agent URL has. Nothing
 * here is Agenor's except the executor a test puts behind it, so what the client receives is
 * what any SDK server would send.
 */
final class A2ATestServer implements AutoCloseable {

    private final HttpServer http;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

    /** Serves {@code executor} with a card that declares {@code streaming}, or not. */
    A2ATestServer(boolean streaming, AgentExecutor executor) throws IOException {
        http = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        http.setExecutor(threads);
        AgentCard card = A2AAdapterConfig.create()
                .agentName("test agent")
                .agentDescription("an AgentExecutor behind the SDK's own handlers")
                .baseUrl(url())
                .streamingEnabled(streaming)
                .toAgentCard();
        var tasks = new InMemoryTaskStore();
        var push = new InMemoryPushNotificationConfigStore();
        // create, not new: new leaves the blocking-mode timeouts at 0 (CDI injects them)
        var requests = DefaultRequestHandler.create(executor, tasks, new InMemoryQueueManager(tasks),
                push, new BasePushNotificationSender(push), threads);
        var jsonrpc = new JSONRPCHandler(card, requests, threads);
        http.createContext("/", exchange -> {
            try (exchange) {
                if ("GET".equals(exchange.getRequestMethod())) {
                    respond(exchange, "application/json", Utils.toJsonString(card));
                } else {
                    answer(exchange, jsonrpc);
                }
            }
        });
        http.start();
    }

    /** The URL the agent answers on, and the one its card declares. */
    String url() {
        return "http://localhost:" + http.getAddress().getPort();
    }

    private static void answer(HttpExchange exchange, JSONRPCHandler jsonrpc) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode method = Utils.OBJECT_MAPPER.readTree(body).get("method");
        var context = new ServerCallContext(UnauthenticatedUser.INSTANCE,
                new HashMap<>(Map.of(JSONRPCContextKeys.METHOD_NAME_KEY, method.asText())), Set.of());
        if (SendStreamingMessageRequest.METHOD.equals(method.asText())) {
            stream(exchange, jsonrpc.onMessageSendStream(
                    Utils.OBJECT_MAPPER.readValue(body, SendStreamingMessageRequest.class), context));
            return;
        }
        Object response = switch (Utils.OBJECT_MAPPER.readValue(body, NonStreamingJSONRPCRequest.class)) {
            case SendMessageRequest r -> jsonrpc.onMessageSend(r, context);
            case GetTaskRequest r -> jsonrpc.onGetTask(r, context);
            case CancelTaskRequest r -> jsonrpc.onCancelTask(r, context);
            case NonStreamingJSONRPCRequest<?> r -> new JSONRPCErrorResponse(r.getId(), new MethodNotFoundError());
        };
        respond(exchange, "application/json", Utils.toJsonString(response));
    }

    /** Writes each event as an SSE {@code data:} line and returns when the publisher ends. */
    private static void stream(HttpExchange exchange, Flow.Publisher<SendStreamingMessageResponse> events)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        CountDownLatch ended = new CountDownLatch(1);
        events.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(SendStreamingMessageResponse event) {
                try {
                    out.write(("data: " + Utils.toJsonString(event) + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException e) {
                    // the client went away; the publisher ends on its own
                }
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
        try {
            ended.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void respond(HttpExchange exchange, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        http.stop(0);
        threads.shutdownNow();
    }

    /**
     * Tells the SDK's JSON-RPC handler that this server provides JSON-RPC; without it the handler
     * rejects the card. Registered in {@code META-INF/services/io.a2a.server.TransportMetadata}.
     */
    public static final class JsonRpcTransport implements TransportMetadata {
        @Override
        public String getTransportProtocol() {
            return TransportProtocol.JSONRPC.asString();
        }
    }
}
