package dev.agenor.adapters.a2a;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.a2a.server.agentexecution.AgentExecutor;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;

/**
 * {@link AgenorA2AServer} served over HTTP by the JDK, with {@code message/stream} as Server-Sent
 * Events: the two endpoints an application writes in its own HTTP server, and nothing else.
 *
 * <p>One agent per server, at the root of its own port: the SDK's card resolver looks for the card
 * at {@code /.well-known/agent-card.json} of the host, whatever path the agent URL has. Any
 * {@link AgentExecutor} can stand behind it, so a test can put a fake in place of the agent while
 * the client still receives what the SDK's own handlers send.
 */
final class A2ATestServer implements AutoCloseable {

    private final HttpServer http;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final AgenorA2AServer a2a;

    /** Serves {@code executor} with a card that declares {@code streaming}, or not. */
    A2ATestServer(boolean streaming, AgentExecutor executor) throws IOException {
        http = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        http.setExecutor(threads);
        a2a = AgenorA2AServer.withExecutor(A2AAdapterConfig.create()
                .agentName("test agent")
                .agentDescription("an AgentExecutor behind the SDK's own handlers")
                .baseUrl(url())
                .streamingEnabled(streaming), executor);
        http.createContext("/", exchange -> {
            try (exchange) {
                if ("GET".equals(exchange.getRequestMethod())) {
                    respond(exchange, a2a.agentCardJson());
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (a2a.isStreamingRequest(body)) {
                    stream(exchange, a2a.handleStream(body));
                } else {
                    respond(exchange, a2a.handle(body));
                }
            }
        });
        http.start();
    }

    /** The URL the agent answers on, and the one its card declares. */
    String url() {
        return "http://localhost:" + http.getAddress().getPort();
    }

    /** Writes each event as an SSE {@code data:} line and returns when the publisher ends. */
    private static void stream(HttpExchange exchange, Flow.Publisher<String> events) throws IOException {
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
            public void onNext(String event) {
                try {
                    out.write(("data: " + event + "\n\n").getBytes(StandardCharsets.UTF_8));
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

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        http.stop(0);
        a2a.close();
        threads.shutdownNow();
    }
}
