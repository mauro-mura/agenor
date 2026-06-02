package dev.agenor.adapters.a2a;

import dev.agenor.core.dialogue.DialogueMessage;
import io.a2a.client.http.A2ACardResolver;
import io.a2a.client.Client;
import io.a2a.client.config.ClientConfig;
import io.a2a.client.ClientEvent;
import io.a2a.client.TaskEvent;
import io.a2a.client.transport.jsonrpc.JSONRPCTransport;
import io.a2a.client.transport.jsonrpc.JSONRPCTransportConfig;
import io.a2a.spec.AgentCard;
import io.a2a.spec.Message;
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;
import io.a2a.A2A;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A2A Client wrapper using official A2A Java SDK (v0.3.2.Final).
 *
 * <p>Handles communication with external A2A agents via HTTP/JSON-RPC.
 * Uses the official {@code io.a2a.client.Client} from the SDK.
 *
 * @since 0.5.0
 */
public class JenticA2AClient {

    private static final Logger log = LoggerFactory.getLogger(JenticA2AClient.class);

    private final DialogueA2AConverter converter;
    private final Duration timeout;

    public JenticA2AClient() {
        this(Duration.ofMinutes(5));
    }

    public JenticA2AClient(Duration timeout) {
        this.converter = new DialogueA2AConverter();
        this.timeout = timeout;
    }

    /**
     * Sends a DialogueMessage to an external A2A agent.
     *
     * @param agentUrl URL of the external A2A agent
     * @param message the DialogueMessage to send
     * @param localAgentId the local agent's ID (for response routing)
     * @return CompletableFuture with the response DialogueMessage
     */
    public CompletableFuture<DialogueMessage> send(String agentUrl, DialogueMessage message, String localAgentId) {
        CompletableFuture<DialogueMessage> result = new CompletableFuture<>();

        CompletableFuture.runAsync(() -> {
            try {
                // Get agent card using A2ACardResolver
                AgentCard agentCard = new A2ACardResolver(agentUrl).getAgentCard();
                log.debug("Connected to A2A agent: {} ({})", agentCard.name(), agentUrl);

                // Create result holder
                AtomicReference<Task> taskResult = new AtomicReference<>();
                AtomicReference<Throwable> errorResult = new AtomicReference<>();

                // Create event consumers
                List<BiConsumer<ClientEvent, AgentCard>> consumers = List.of(
                    (event, card) -> {
                        if (event instanceof TaskEvent taskEvent) {
                            Task task = taskEvent.getTask();
                            taskResult.set(task);
                            if (task.getStatus().state() == TaskState.COMPLETED ||
                                task.getStatus().state() == TaskState.FAILED ||
                                task.getStatus().state() == TaskState.CANCELED) {
                                // Terminal state reached
                                synchronized (taskResult) {
                                    taskResult.notifyAll();
                                }
                            }
                        }
                    }
                );

                // Create error handler
                Consumer<Throwable> errorHandler = error -> {
                    errorResult.set(error);
                    synchronized (taskResult) {
                        taskResult.notifyAll();
                    }
                };

                // Configure client
                ClientConfig clientConfig = new ClientConfig.Builder()
                    .setAcceptedOutputModes(List.of("text"))
                    .build();

                // Build client with JSON-RPC transport
                Client client = Client.builder(agentCard)
                    .clientConfig(clientConfig)
                    .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig())
                    .addConsumers(consumers)
                    .streamingErrorHandler(errorHandler)
                    .build();

                // Convert and send message
                String textContent = serializeContent(message.content());
                Message a2aMessage = A2A.toUserMessage(textContent);

                client.sendMessage(a2aMessage);

                // Wait for response with timeout
                synchronized (taskResult) {
                    taskResult.wait(timeout.toMillis());
                }

                // Check for errors
                if (errorResult.get() != null) {
                    result.completeExceptionally(
                        new A2AClientException("A2A error: " + errorResult.get().getMessage(), errorResult.get())
                    );
                    return;
                }

                // Convert response
                Task task = taskResult.get();
                if (task != null) {
                    DialogueMessage response = converter.fromTask(task, localAgentId);
                    result.complete(response);
                } else {
                    result.completeExceptionally(new A2AClientException("No response received within timeout"));
                }

            } catch (Exception e) {
                log.error("Failed to send A2A message to {}: {}", agentUrl, e.getMessage(), e);
                result.completeExceptionally(new A2AClientException("A2A communication failed: " + e.getMessage(), e));
            }
        });

        return result;
    }

    /**
     * Sends a message with streaming support (for long-running tasks).
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

        CompletableFuture<DialogueMessage> result = new CompletableFuture<>();

        CompletableFuture.runAsync(() -> {
            try {
                AgentCard agentCard = new A2ACardResolver(agentUrl).getAgentCard();

                AtomicReference<Task> finalTask = new AtomicReference<>();

                List<BiConsumer<ClientEvent, AgentCard>> consumers = List.of(
                    (event, card) -> {
                        if (event instanceof TaskEvent taskEvent) {
                            Task task = taskEvent.getTask();
                            finalTask.set(task);

                            // Notify status callback
                            if (statusCallback != null) {
                                statusCallback.onStatus(
                                    task.getStatus().state().name(),
                                    extractStatusMessage(task)
                                );
                            }

                            // Check for terminal state
                            if (isTerminalState(task.getStatus().state())) {
                                synchronized (finalTask) {
                                    finalTask.notifyAll();
                                }
                            }
                        }
                    }
                );

                Consumer<Throwable> errorHandler = error -> {
                    result.completeExceptionally(
                        new A2AClientException("Streaming error: " + error.getMessage(), error)
                    );
                    synchronized (finalTask) {
                        finalTask.notifyAll();
                    }
                };

                ClientConfig clientConfig = new ClientConfig.Builder()
                    .setAcceptedOutputModes(List.of("text"))
                    .build();

                Client client = Client.builder(agentCard)
                    .clientConfig(clientConfig)
                    .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig())
                    .addConsumers(consumers)
                    .streamingErrorHandler(errorHandler)
                    .build();

                String textContent = serializeContent(message.content());
                Message a2aMessage = A2A.toUserMessage(textContent);

                client.sendMessage(a2aMessage);

                // Wait for completion
                synchronized (finalTask) {
                    finalTask.wait(timeout.toMillis());
                }

                Task task = finalTask.get();
                if (task != null && !result.isDone()) {
                    result.complete(converter.fromTask(task, localAgentId));
                } else if (!result.isDone()) {
                    result.completeExceptionally(new A2AClientException("No response received"));
                }

            } catch (Exception e) {
                log.error("Streaming A2A request failed: {}", e.getMessage(), e);
                result.completeExceptionally(new A2AClientException("Streaming failed: " + e.getMessage(), e));
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

    private boolean isTerminalState(TaskState state) {
        return state == TaskState.COMPLETED ||
               state == TaskState.FAILED ||
               state == TaskState.CANCELED;
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
