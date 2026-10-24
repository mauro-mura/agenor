package dev.agenor.adapters.a2a;

import com.fasterxml.jackson.databind.JsonNode;
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
import io.a2a.spec.InvalidRequestError;
import io.a2a.spec.JSONParseError;
import io.a2a.spec.JSONRPCError;
import io.a2a.spec.JSONRPCErrorResponse;
import io.a2a.spec.MethodNotFoundError;
import io.a2a.spec.SendMessageRequest;
import io.a2a.spec.SendStreamingMessageRequest;
import io.a2a.spec.SendStreamingMessageResponse;
import io.a2a.spec.TransportProtocol;
import io.a2a.transport.jsonrpc.context.JSONRPCContextKeys;
import io.a2a.transport.jsonrpc.handler.JSONRPCHandler;
import io.a2a.util.Utils;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;

/**
 * Exposes one Agenor agent as an A2A 0.3 server over JSON-RPC, without choosing the HTTP server.
 *
 * <p>{@link AgenorAgentExecutor} turns an A2A task into a request to an Agenor agent, but it is
 * only the innermost piece: the SDK also needs a request handler, a task store, a queue manager,
 * a JSON-RPC handler, a call context per request, and a {@code TransportMetadata} registered as a
 * service, without which the JSON-RPC handler rejects the card. This type assembles them. What is
 * left to the application is two endpoints in whatever HTTP server it already runs:
 *
 * <pre>{@code
 * var server = AgenorA2AServer.of(
 *         A2AAdapterConfig.create().agentName("support").baseUrl("http://localhost:8080"),
 *         new AgenorAgentExecutor("support-agent", runtime.getMessageDispatcher(),
 *                 runtime.getAgentDirectory(), Duration.ofMinutes(2)));
 *
 * // GET  /.well-known/agent-card.json  -> server.agentCardJson()
 * // POST /                             -> server.isStreamingRequest(body)
 * //                                         ? each server.handleStream(body) item as an SSE "data:" line
 * //                                         : server.handle(body) as application/json
 * }</pre>
 *
 * <p>The SDK reads the card from {@code /.well-known/agent-card.json} at the root of the host,
 * whatever path the agent URL has, so serve one agent per host and port.
 *
 * <p>Supported methods are {@code message/send}, {@code message/stream}, {@code tasks/get} and
 * {@code tasks/cancel}. Any other method is answered with JSON-RPC {@code -32601}, a body that is
 * not JSON with {@code -32700}, and one that is JSON but not a request with {@code -32600}.
 *
 * <p>Tasks are held in memory: a {@code tasks/get} after a restart, or on another node, does not
 * find a task this instance created.
 *
 * @since 0.39.0
 */
public final class AgenorA2AServer implements AutoCloseable {

    private static final String MESSAGE_SEND = SendMessageRequest.METHOD;
    private static final String MESSAGE_STREAM = SendStreamingMessageRequest.METHOD;
    private static final String TASKS_GET = GetTaskRequest.METHOD;
    private static final String TASKS_CANCEL = CancelTaskRequest.METHOD;

    private final AgentCard card;
    private final String cardJson;
    private final JSONRPCHandler jsonrpc;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

    private AgenorA2AServer(A2AAdapterConfig config, AgentExecutor executor) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(executor, "executor must not be null");
        this.card = config.toAgentCard();
        this.cardJson = toJson(card);
        var tasks = new InMemoryTaskStore();
        var push = new InMemoryPushNotificationConfigStore();
        // create, not new: new leaves the blocking-mode timeouts at 0, because CDI injects them
        var requests = DefaultRequestHandler.create(executor, tasks, new InMemoryQueueManager(tasks),
                push, new BasePushNotificationSender(push), threads);
        this.jsonrpc = new JSONRPCHandler(card, requests, threads);
    }

    /**
     * Creates a server for the agent behind {@code executor}, with the card {@code config} builds.
     *
     * @param config   the agent card; its {@code baseUrl} must be the URL this server is reached at
     * @param executor the executor that routes each task to the Agenor agent
     * @return a server; close it to stop its threads
     */
    public static AgenorA2AServer of(A2AAdapterConfig config, AgenorAgentExecutor executor) {
        return new AgenorA2AServer(config, executor);
    }

    /** Any SDK executor, for tests that stand in for an agent. */
    static AgenorA2AServer withExecutor(A2AAdapterConfig config, AgentExecutor executor) {
        return new AgenorA2AServer(config, executor);
    }

    /** The agent card, as served at {@code GET /.well-known/agent-card.json}. */
    public String agentCardJson() {
        return cardJson;
    }

    /**
     * Whether {@code body} is a {@code message/stream} request, which {@link #handleStream} answers
     * instead of {@link #handle}.
     *
     * @param body the JSON-RPC request body
     * @return true if the response must be sent as Server-Sent Events
     */
    public boolean isStreamingRequest(String body) {
        JsonNode request = parse(body);
        return request != null && MESSAGE_STREAM.equals(request.path("method").asText(null));
    }

    /**
     * Answers one JSON-RPC request that is not {@code message/stream}.
     *
     * @param body the JSON-RPC request body
     * @return the JSON-RPC response, a result or an error, to send as {@code application/json}
     */
    public String handle(String body) {
        JsonNode request = parse(body);
        if (request == null) {
            return error(null, new JSONParseError());
        }
        Object id = idOf(request);
        String method = request.path("method").asText(null);
        if (method == null) {
            return error(id, new InvalidRequestError("method is required"));
        }
        try {
            return switch (method) {
                case MESSAGE_SEND -> toJson(jsonrpc.onMessageSend(
                        Utils.OBJECT_MAPPER.treeToValue(request, SendMessageRequest.class), context(method)));
                case TASKS_GET -> toJson(jsonrpc.onGetTask(
                        Utils.OBJECT_MAPPER.treeToValue(request, GetTaskRequest.class), context(method)));
                case TASKS_CANCEL -> toJson(jsonrpc.onCancelTask(
                        Utils.OBJECT_MAPPER.treeToValue(request, CancelTaskRequest.class), context(method)));
                case MESSAGE_STREAM -> error(id, new InvalidRequestError(
                        "message/stream is answered as a stream: use handleStream"));
                default -> error(id, new MethodNotFoundError());
            };
        } catch (Exception e) {
            return error(id, new InvalidRequestError(e.getMessage()));
        }
    }

    /**
     * Answers a {@code message/stream} request with its events, each a JSON-RPC response to send
     * as one SSE {@code data:} line. The publisher completes when the task ends.
     *
     * <p>A body that is not a {@code message/stream} request yields a single error event.
     *
     * @param body the JSON-RPC request body
     * @return the events, as JSON
     */
    public Flow.Publisher<String> handleStream(String body) {
        JsonNode request = parse(body);
        if (request == null) {
            return single(error(null, new JSONParseError()));
        }
        Object id = idOf(request);
        if (!MESSAGE_STREAM.equals(request.path("method").asText(null))) {
            return single(error(id, new InvalidRequestError("only message/stream is answered as a stream")));
        }
        try {
            var events = jsonrpc.onMessageSendStream(
                    Utils.OBJECT_MAPPER.treeToValue(request, SendStreamingMessageRequest.class),
                    context(MESSAGE_STREAM));
            return subscriber -> events.subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscriber.onSubscribe(subscription);
                }

                @Override
                public void onNext(SendStreamingMessageResponse event) {
                    subscriber.onNext(toJson(event));
                }

                @Override
                public void onError(Throwable error) {
                    subscriber.onError(error);
                }

                @Override
                public void onComplete() {
                    subscriber.onComplete();
                }
            });
        } catch (Exception e) {
            return single(error(id, new InvalidRequestError(e.getMessage())));
        }
    }

    /** Stops the threads the SDK handlers run on; tasks still running are interrupted. */
    @Override
    public void close() {
        threads.shutdownNow();
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private static JsonNode parse(String body) {
        if (body == null) {
            return null;
        }
        try {
            JsonNode node = Utils.OBJECT_MAPPER.readTree(body);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Object idOf(JsonNode request) {
        JsonNode id = request.get("id");
        if (id == null || id.isNull()) {
            return null;
        }
        return id.isNumber() ? id.numberValue() : id.asText();
    }

    private static ServerCallContext context(String method) {
        return new ServerCallContext(UnauthenticatedUser.INSTANCE,
                new HashMap<>(Map.of(JSONRPCContextKeys.METHOD_NAME_KEY, method)), Set.of());
    }

    private static String error(Object id, JSONRPCError error) {
        String json = toJson(new JSONRPCErrorResponse(id, error));
        if (id != null) {
            return json;
        }
        // The SDK leaves a null id out; JSON-RPC 2.0 requires it, as null, when the request's id
        // could not be read
        try {
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) Utils.OBJECT_MAPPER.readTree(json);
            node.putNull("id");
            return Utils.OBJECT_MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            return json;
        }
    }

    private static String toJson(Object value) {
        return Utils.toJsonString(value);
    }

    private static Flow.Publisher<String> single(String event) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private boolean done;

            @Override
            public synchronized void request(long n) {
                if (done || n <= 0) {
                    return;
                }
                done = true;
                subscriber.onNext(event);
                subscriber.onComplete();
            }

            @Override
            public synchronized void cancel() {
                done = true;
            }
        });
    }

    /**
     * Declares to the SDK that JSON-RPC is served, which its JSON-RPC handler requires of the card.
     * Registered in {@code META-INF/services/io.a2a.server.TransportMetadata}; not for application
     * use.
     */
    public static final class JsonRpcTransport implements TransportMetadata {
        @Override
        public String getTransportProtocol() {
            return TransportProtocol.JSONRPC.asString();
        }
    }
}
