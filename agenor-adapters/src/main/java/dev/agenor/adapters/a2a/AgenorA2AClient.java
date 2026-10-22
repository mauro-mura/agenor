package dev.agenor.adapters.a2a;

import dev.agenor.core.dialogue.DialogueMessage;
import io.a2a.client.http.A2ACardResolver;
import io.a2a.client.Client;
import io.a2a.client.config.ClientConfig;
import io.a2a.client.ClientEvent;
import io.a2a.client.MessageEvent;
import io.a2a.client.TaskEvent;
import io.a2a.client.TaskUpdateEvent;
import io.a2a.client.transport.jsonrpc.JSONRPCTransport;
import io.a2a.client.transport.jsonrpc.JSONRPCTransportConfig;
import io.a2a.spec.AgentCard;
import io.a2a.spec.Message;
import io.a2a.spec.Task;
import io.a2a.spec.TaskQueryParams;
import io.a2a.spec.TaskState;
import io.a2a.A2A;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A2A Client wrapper using official A2A Java SDK (v0.3.2.Final).
 *
 * <p>Handles communication with external A2A agents via HTTP/JSON-RPC.
 * Uses the official {@code io.a2a.client.Client} from the SDK.
 *
 * <p>The returned future completes on the agent's answer: a task in a final state
 * ({@code completed}, {@code failed}, {@code rejected}, {@code canceled}), a task that hands
 * control back ({@code input-required}, {@code auth-required}), or a message. A task the agent
 * returns while still working is read again with {@code tasks/get} until it gets there. If
 * nothing arrives within the timeout, the future fails.
 *
 * @since 0.5.0
 */
public class AgenorA2AClient {

    private static final Logger log = LoggerFactory.getLogger(AgenorA2AClient.class);

    /** A task that is still working is read again after this pause, doubling up to the next. */
    private static final Duration FIRST_POLL = Duration.ofMillis(100);
    private static final Duration LONGEST_POLL = Duration.ofSeconds(1);

    private final DialogueA2AConverter converter;
    private final Duration timeout;

    public AgenorA2AClient() {
        this(Duration.ofMinutes(5));
    }

    public AgenorA2AClient(Duration timeout) {
        this.converter = new DialogueA2AConverter();
        this.timeout = timeout;
    }

    /**
     * Sends a DialogueMessage to an external A2A agent, without streaming.
     *
     * @param agentUrl URL of the external A2A agent
     * @param message the DialogueMessage to send
     * @param localAgentId the local agent's ID (for response routing)
     * @return CompletableFuture with the response DialogueMessage
     */
    public CompletableFuture<DialogueMessage> send(String agentUrl, DialogueMessage message, String localAgentId) {
        return exchange(agentUrl, message, localAgentId, false, null);
    }

    /**
     * Sends a message with streaming support (for long-running tasks). If the agent's card does
     * not declare streaming, the message is sent as {@link #send} would, and the callback sees
     * the states the agent returns.
     *
     * @param agentUrl URL of the external A2A agent
     * @param message the DialogueMessage to send
     * @param localAgentId the local agent's ID
     * @param statusCallback callback for status updates
     * @return CompletableFuture with the final response
     */
    public CompletableFuture<DialogueMessage> sendWithStreaming(
            String agentUrl,
            DialogueMessage message,
            String localAgentId,
            StatusCallback statusCallback) {
        return exchange(agentUrl, message, localAgentId, true, statusCallback);
    }

    private CompletableFuture<DialogueMessage> exchange(String agentUrl, DialogueMessage message,
                                                        String localAgentId, boolean streaming,
                                                        StatusCallback statusCallback) {
        CompletableFuture<DialogueMessage> result = new CompletableFuture<>();
        CompletableFuture.delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS).execute(() ->
            result.completeExceptionally(
                new A2AClientException("No response from " + agentUrl + " within " + timeout)));

        CompletableFuture.runAsync(() -> {
            try {
                AgentCard agentCard = new A2ACardResolver(agentUrl).getAgentCard();
                log.debug("Connected to A2A agent: {} ({})", agentCard.name(), agentUrl);
                boolean stream = streaming && agentCard.capabilities() != null
                    && agentCard.capabilities().streaming();

                AtomicReference<String> taskId = new AtomicReference<>();
                AtomicReference<TaskState> lastState = new AtomicReference<>();
                Consumer<Task> onTask = task -> {
                    taskId.set(task.getId());
                    TaskState state = task.getStatus().state();
                    if (statusCallback != null && lastState.getAndSet(state) != state) {
                        statusCallback.onStatus(state.name(), extractStatusMessage(task));
                    }
                    if (endsExchange(state)) {
                        result.complete(converter.fromTask(task, localAgentId));
                    }
                };
                BiConsumer<ClientEvent, AgentCard> consumer = (event, card) -> {
                    switch (event) {
                        case TaskEvent e -> onTask.accept(e.getTask());
                        case TaskUpdateEvent e -> onTask.accept(e.getTask());
                        case MessageEvent e -> result.complete(converter.fromReply(e.getMessage(), localAgentId));
                        default -> { }
                    }
                };
                // The SDK cancels its own stream once the final event is delivered and reports
                // that as an error; an error after the answer is not a failure of the exchange.
                // null means the stream ended normally.
                Consumer<Throwable> errorHandler = error -> {
                    if (error == null) {
                        return;
                    }
                    if (result.isDone()) {
                        log.debug("A2A stream from {} ended after the answer: {}", agentUrl, error.toString());
                    } else {
                        result.completeExceptionally(
                            new A2AClientException("A2A error: " + error.getMessage(), error));
                    }
                };

                ClientConfig clientConfig = new ClientConfig.Builder()
                    .setStreaming(stream)
                    .setPolling(!stream)
                    .setAcceptedOutputModes(List.of("text"))
                    .build();

                Client client = Client.builder(agentCard)
                    .clientConfig(clientConfig)
                    .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig())
                    .addConsumers(List.of(consumer))
                    .streamingErrorHandler(errorHandler)
                    .build();
                result.whenComplete((reply, error) -> client.close());

                Message a2aMessage = A2A.toUserMessage(serializeContent(message.content()));
                client.sendMessage(a2aMessage);

                // Without streaming the call is non-blocking: the SDK server stops holding a
                // blocking call after 5 s and drops what the agent sends later. So the task comes
                // back still working, and is read again until it ends
                if (!stream && taskId.get() != null) {
                    long pause = FIRST_POLL.toMillis();
                    while (!result.isDone()) {
                        Thread.sleep(pause);
                        pause = Math.min(pause * 2, LONGEST_POLL.toMillis());
                        if (!result.isDone()) {
                            onTask.accept(client.getTask(new TaskQueryParams(taskId.get())));
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                result.completeExceptionally(new A2AClientException("A2A communication interrupted", e));
            } catch (Exception e) {
                if (!result.isDone()) {
                    log.error("Failed to send A2A message to {}: {}", agentUrl, e.getMessage(), e);
                }
                result.completeExceptionally(new A2AClientException("A2A communication failed: " + e.getMessage(), e));
            }
        });

        return result;
    }

    /**
     * Fetches the AgentCard from a remote A2A agent.
     *
     * @param agentUrl URL of the A2A agent
     * @return the AgentCard describing the agent's capabilities
     */
    public CompletableFuture<AgentCard> getAgentCard(String agentUrl) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new A2ACardResolver(agentUrl).getAgentCard();
            } catch (Exception e) {
                log.error("Failed to fetch AgentCard from {}: {}", agentUrl, e.getMessage());
                throw new A2AClientException("Failed to get AgentCard: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Checks if a URL points to a valid A2A agent.
     */
    public CompletableFuture<Boolean> isA2AAgent(String url) {
        return getAgentCard(url)
            .thenApply(card -> card != null && card.name() != null)
            .exceptionally(e -> false);
    }

    /** A final state, or one that hands control back to the client. */
    private static boolean endsExchange(TaskState state) {
        return state.isFinal() || state == TaskState.INPUT_REQUIRED || state == TaskState.AUTH_REQUIRED;
    }

    private String serializeContent(Object content) {
        if (content == null) return "";
        if (content instanceof String s) return s;
        return content.toString();
    }

    private String extractStatusMessage(Task task) {
        if (task.getStatus() != null && task.getStatus().message() != null) {
            var parts = task.getStatus().message().getParts();
            if (parts != null && !parts.isEmpty()) {
                var first = parts.get(0);
                if (first instanceof io.a2a.spec.TextPart tp) {
                    return tp.getText();
                }
            }
        }
        return "";
    }

    /**
     * Callback interface for streaming status updates.
     */
    @FunctionalInterface
    public interface StatusCallback {
        void onStatus(String state, String message);
    }

    /**
     * Exception for A2A client errors.
     */
    public static class A2AClientException extends RuntimeException {
        private final Integer errorCode;

        public A2AClientException(String message) {
            super(message);
            this.errorCode = null;
        }

        public A2AClientException(String message, int code) {
            super(message);
            this.errorCode = code;
        }

        public A2AClientException(String message, Throwable cause) {
            super(message, cause);
            this.errorCode = null;
        }

        public Integer getErrorCode() {
            return errorCode;
        }
    }
}
