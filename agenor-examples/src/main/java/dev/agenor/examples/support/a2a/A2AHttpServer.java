package dev.agenor.examples.support.a2a;

import dev.agenor.adapters.a2a.AgenorA2AServer;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;

/**
 * {@link AgenorA2AServer} behind Jetty: the two endpoints A2A needs, and a health check.
 *
 * <ul>
 *   <li>{@code GET /.well-known/agent-card.json} - the agent card</li>
 *   <li>{@code POST /} - JSON-RPC; {@code message/stream} is answered as Server-Sent Events</li>
 *   <li>{@code GET /health} - liveness</li>
 * </ul>
 *
 * <p>Everything A2A is in {@link AgenorA2AServer}; this class only moves bodies between HTTP and
 * it, which is what an application writes in whatever server it already runs.
 */
public class A2AHttpServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(A2AHttpServer.class);

    private final int port;
    private final AgenorA2AServer a2a;
    private Server server;

    /**
     * @param port the port to listen on
     * @param a2a  the A2A server to put behind it, closed with this one
     */
    public A2AHttpServer(int port, AgenorA2AServer a2a) {
        this.port = port;
        this.a2a = a2a;
    }

    /** Starts Jetty; returns once it accepts connections. */
    public void start() throws Exception {
        server = new Server();
        var connector = new ServerConnector(server);
        connector.setPort(port);
        server.addConnector(connector);

        var context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(new ServletHolder("agent-card", new CardServlet()), "/.well-known/agent-card.json");
        context.addServlet(new ServletHolder("health", new HealthServlet()), "/health");
        context.addServlet(new ServletHolder("a2a", new JsonRpcServlet()), "/");
        server.setHandler(context);

        server.start();
        log.info("A2A server on http://localhost:{} (card at /.well-known/agent-card.json)", port);
    }

    @Override
    public void close() throws Exception {
        if (server != null) {
            server.stop();
        }
        a2a.close();
    }

    private class CardServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            write(resp, a2a.agentCardJson());
        }
    }

    private static class HealthServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            write(resp, "{\"status\":\"ok\"}");
        }
    }

    private class JsonRpcServlet extends HttpServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            String body = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (a2a.isStreamingRequest(body)) {
                stream(resp, a2a.handleStream(body));
            } else {
                write(resp, a2a.handle(body));
            }
        }
    }

    private static void write(HttpServletResponse resp, String json) throws IOException {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(json);
    }

    /** Writes each event as an SSE {@code data:} line and returns when the stream ends. */
    private static void stream(HttpServletResponse resp, Flow.Publisher<String> events) throws IOException {
        resp.setContentType("text/event-stream");
        resp.setCharacterEncoding("UTF-8");
        PrintWriter out = resp.getWriter();
        var ended = new CountDownLatch(1);
        events.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(String event) {
                out.write("data: " + event + "\n\n");
                out.flush();
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
}
