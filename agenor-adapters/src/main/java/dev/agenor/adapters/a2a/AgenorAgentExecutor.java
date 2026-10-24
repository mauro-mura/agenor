package dev.agenor.adapters.a2a;

import dev.agenor.core.AgentDescriptor;
import dev.agenor.core.AgentEndpoint;
import dev.agenor.core.AgentStatus;
import dev.agenor.core.directory.AgentRegistry;
import dev.agenor.core.messaging.LocalEndpointProvider;
import dev.agenor.core.messaging.MessageDispatcher;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.server.tasks.TaskUpdater;
import io.a2a.spec.JSONRPCError;
import io.a2a.spec.Part;
import io.a2a.spec.TaskNotCancelableError;
import io.a2a.spec.TaskState;
import io.a2a.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Implements A2A SDK AgentExecutor to expose Agenor agents as A2A servers.
 *
 * <p>Routes incoming A2A requests to internal Agenor agents via MessageDispatcher.
 * Uses the official A2A Java SDK v0.3.2.Final API.
 *
 * <p>The task ends on the agent's <em>final</em> reply, as {@code ConversationManager.request()}
 * does (ADR-026). An {@code AGREE} leaves the task {@code working}, with its text as a status
 * message. The final reply maps to a task state:
 * <ul>
 *   <li>{@code INFORM} — {@code completed}, its content as the artifact</li>
 *   <li>{@code FAILURE} — {@code failed}, its content as the status message</li>
 *   <li>{@code REFUSE} — {@code rejected}, its content as the status message</li>
 *   <li>any other performative — {@code failed}, with a status message naming it</li>
 * </ul>
 * No final reply within the timeout, or a request that cannot be sent, also fails the task with a
 * message saying why. {@code tasks/cancel} sends {@code CANCEL} to the agent from the requester's
 * id and ends the wait.
 *
 * <p>To serve it from any HTTP server, pass it to {@link AgenorA2AServer#of}. Usage with
 * CDI/Quarkus, where the SDK's reference server assembles the handlers itself:
 * <pre>{@code
 * @ApplicationScoped
 * public class MyAgentExecutorProducer {
 *     @Inject
 *     MessageDispatcher messageDispatcher;
 *
 *     @Inject
 *     AgentDirectory agentDirectory;
 *
 *     @Produces
 *     public AgentExecutor createExecutor() {
 *         return new AgenorAgentExecutor("my-agent", messageDispatcher, agentDirectory,
 *                 Duration.ofMinutes(5));
 *     }
 * }
 * }</pre>
 *
 * @since 0.5.0
 */
public class AgenorAgentExecutor implements AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgenorAgentExecutor.class);

    private final String internalAgentId;
    private final MessageDispatcher messageDispatcher;
    private final AgentRegistry agentRegistry;
    private final Duration timeout;

    /** Final replies still awaited by {@link #execute}, by task id, so that cancel can end them. */
    private final Map<String, CompletableFuture<DialogueMessage>> awaitingFinalReply =
            new ConcurrentHashMap<>();

    /**
     * @param internalAgentId   the Agenor agent ID to route A2A requests to
     * @param messageDispatcher the runtime message dispatcher
     * @param timeout           per-request timeout
     * @since 0.20.0
     */
    public AgenorAgentExecutor(String internalAgentId, MessageDispatcher messageDispatcher, Duration timeout) {
        this(internalAgentId, messageDispatcher, null, timeout);
        if (messageDispatcher instanceof LocalEndpointProvider provider && provider.localEndpoint().isPresent()) {
            log.warn("AgenorAgentExecutor for '{}' has no AgentRegistry on a networked dispatcher: an agent"
                    + " on another node cannot reply, because the reply recipient is not in the directory."
                    + " Pass the runtime's AgentDirectory to the four-argument constructor", internalAgentId);
        }
    }

    /**
     * Creates an executor whose agent may live on another node.
     *
     * <p>The agent replies to a per-task recipient, {@code a2a-client-<taskId>}. On a networked
     * dispatcher (one that is a {@link LocalEndpointProvider}) the agent's node finds that
     * recipient only through the directory, so the executor registers it there, at this node's
     * endpoint, for the life of the task. Without a networked dispatcher nothing is registered.
     *
     * @param internalAgentId   the Agenor agent ID to route A2A requests to
     * @param messageDispatcher the runtime message dispatcher
     * @param agentRegistry     the registry the agent's node resolves recipients from, typically
     *                          the runtime's {@code AgentDirectory}
     * @param timeout           per-request timeout
     * @since 0.39.0
     */
    public AgenorAgentExecutor(String internalAgentId, MessageDispatcher messageDispatcher,
                               AgentRegistry agentRegistry, Duration timeout) {
        this.internalAgentId = internalAgentId;
        this.messageDispatcher = messageDispatcher;
        this.agentRegistry = agentRegistry;
        this.timeout = timeout;
    }

    @Override
    public void execute(RequestContext context, EventQueue eventQueue) throws JSONRPCError {
        String taskId = context.getTaskId();
        TaskUpdater updater = new TaskUpdater(context, eventQueue);

        log.info("Executing A2A request: taskId={}, agent={}", taskId, internalAgentId);

        try {
            // Mark as submitted and start working
            if (context.getTask() == null) {
                updater.submit();
            }
            updater.startWork();

            // Extract message from context
            io.a2a.spec.Message a2aMessage = context.getMessage();
            if (a2aMessage == null) {
                throw new IllegalArgumentException("No message in request context");
            }

            // Extract text from message parts
            String userMessage = DialogueA2AConverter.textOf(a2aMessage.getParts(), "request for task " + taskId);

            // Convert to Agenor DialogueMessage
            String externalSenderId = requesterId(taskId);
            DialogueMessage incomingMsg = DialogueMessage.builder()
                .conversationId(taskId)
                .senderId(externalSenderId)
                .receiverId(internalAgentId)
                .performative(Performative.REQUEST)
                .content(userMessage)
                .build();

            // Subscribe before sending to avoid races. An AGREE is progress, not the answer: the
            // subscription stays open until the final reply, the timeout, or a cancel (ADR-026).
            CompletableFuture<DialogueMessage> finalReply = new CompletableFuture<>();
            awaitingFinalReply.put(taskId, finalReply);
            var subscription = messageDispatcher.subscribeRecipient(externalSenderId, msg -> {
                DialogueMessage reply = DialogueMessage.fromMessage(msg);
                if (reply.performative() == Performative.AGREE) {
                    reportAgreed(updater, reply, finalReply);
                } else {
                    finalReply.complete(reply);
                }
                return CompletableFuture.completedFuture(null);
            });
            DialogueMessage reply;
            boolean registered = false;
            try {
                // The requester has to be resolvable before the agent can answer it from another node
                Optional<AgentEndpoint> endpoint = networkedEndpoint();
                registered = endpoint.isPresent();
                CompletableFuture<Void> resolvable = endpoint
                        .map(ep -> agentRegistry.register(AgentDescriptor.builder(externalSenderId)
                                .agentName("A2A client, task " + taskId)
                                .agentType("a2a-client")
                                .status(AgentStatus.RUNNING)
                                .endpoint(ep)
                                .build()))
                        .orElseGet(() -> CompletableFuture.completedFuture(null));
                resolvable
                        .thenCompose(ignored -> messageDispatcher.sendTo(incomingMsg.toMessage()))
                        .whenComplete((ignored, ex) -> {
                            if (ex != null) {
                                finalReply.completeExceptionally(
                                        ex instanceof CompletionException && ex.getCause() != null
                                                ? ex.getCause() : ex);
                            }
                        });
                reply = finalReply.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } finally {
                subscription.unsubscribe();
                awaitingFinalReply.remove(taskId, finalReply);
                if (registered) {
                    unregister(externalSenderId);
                }
            }

            endTask(updater, reply);
            log.info("A2A request ended: taskId={}, reply={}", taskId, reply.performative());

        } catch (CancellationException e) {
            // cancel() has told the agent and reported the task cancelled: nothing left to say
            log.info("A2A request cancelled: taskId={}", taskId);

        } catch (TimeoutException e) {
            log.error("A2A request timeout: taskId={}", taskId);
            updater.fail(agentText(updater,
                    "No final reply from '" + internalAgentId + "' within " + timeout));

        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("A2A request could not be sent: taskId={}, error={}", taskId, cause.getMessage(), cause);
            updater.fail(agentText(updater,
                    "Could not send the request to '" + internalAgentId + "': " + cause.getMessage()));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("A2A request interrupted: taskId={}", taskId);
            updater.fail(agentText(updater, "Interrupted while waiting for '" + internalAgentId + "'"));

        } catch (Exception e) {
            log.error("A2A request failed: taskId={}, error={}", taskId, e.getMessage(), e);
            updater.fail(agentText(updater, "A2A request failed: " + e.getMessage()));
        }
    }

    @Override
    public void cancel(RequestContext context, EventQueue eventQueue) throws JSONRPCError {
        String taskId = context.getTaskId();
        log.info("Cancelling A2A request: taskId={}", taskId);

        TaskUpdater updater = new TaskUpdater(context, eventQueue);

        // Check if task can be cancelled
        if (context.getTask() != null && context.getTask().getStatus().state().isFinal()) {
            throw new TaskNotCancelableError();
        }

        try {
            // CANCEL comes from the requester, the participant the agent is talking to
            DialogueMessage cancelMsg = DialogueMessage.builder()
                .conversationId(taskId)
                .senderId(requesterId(taskId))
                .receiverId(internalAgentId)
                .performative(Performative.CANCEL)
                .content("Cancelled by A2A client")
                .build();

            messageDispatcher.sendTo(cancelMsg.toMessage()).whenComplete((ignored, ex) -> {
                if (ex != null) {
                    log.warn("CANCEL for A2A task {} did not reach '{}': {}",
                            taskId, internalAgentId, ex.getMessage());
                }
            });

        } catch (Exception e) {
            log.error("Failed to cancel A2A request: taskId={}", taskId, e);
            throw new TaskNotCancelableError();
        }

        // A final reply that arrived first has already ended the task in execute()
        CompletableFuture<DialogueMessage> waiting = awaitingFinalReply.remove(taskId);
        if (waiting != null && !waiting.cancel(false)) {
            throw new TaskNotCancelableError();
        }

        // Update A2A task status
        updater.cancel();
    }

    /**
     * Returns the internal Agenor agent ID this executor routes to.
     */
    public String getInternalAgentId() {
        return internalAgentId;
    }

    private static String requesterId(String taskId) {
        return "a2a-client-" + taskId;
    }

    /** This node's endpoint, when replies may come from another node and there is a registry. */
    private Optional<AgentEndpoint> networkedEndpoint() {
        if (agentRegistry != null && messageDispatcher instanceof LocalEndpointProvider provider) {
            return provider.localEndpoint();
        }
        return Optional.empty();
    }

    private void unregister(String requester) {
        agentRegistry.unregister(requester).whenComplete((ignored, ex) -> {
            if (ex != null) {
                log.warn("Could not unregister A2A requester '{}' from the directory: {}",
                        requester, ex.getMessage());
            }
        });
    }

    /** The agent accepted: the task stays working, with what the agent said about it. */
    private void reportAgreed(TaskUpdater updater, DialogueMessage agree,
                              CompletableFuture<DialogueMessage> finalReply) {
        String text = contentText(agree);
        // An AGREE after the task has ended (late, or after a timeout) has no task to update
        if (text.isEmpty() || finalReply.isDone()) {
            return;
        }
        try {
            updater.updateStatus(TaskState.WORKING, agentText(updater, text));
        } catch (IllegalStateException e) {
            log.debug("AGREE arrived after the task ended: taskId={}", updater.getTaskId());
        }
    }

    /** Maps the final reply to the task's terminal state. No performative completes by default. */
    private void endTask(TaskUpdater updater, DialogueMessage reply) {
        String text = contentText(reply);
        switch (reply.performative()) {
            case INFORM -> {
                updater.addArtifact(List.of(new TextPart(text, null)), null, null, null);
                updater.complete();
            }
            case FAILURE -> updater.fail(agentText(updater,
                    text.isEmpty() ? "'" + internalAgentId + "' replied FAILURE" : text));
            case REFUSE -> updater.reject(agentText(updater,
                    text.isEmpty() ? "'" + internalAgentId + "' replied REFUSE" : text));
            default -> updater.fail(agentText(updater,
                    "'" + internalAgentId + "' replied " + reply.performative()
                            + ", which does not end a request"));
        }
    }

    private static String contentText(DialogueMessage message) {
        return message.content() != null ? message.content().toString() : "";
    }

    private static io.a2a.spec.Message agentText(TaskUpdater updater, String text) {
        List<Part<?>> parts = List.of(new TextPart(text, null));
        return updater.newAgentMessage(parts, null);
    }
}
