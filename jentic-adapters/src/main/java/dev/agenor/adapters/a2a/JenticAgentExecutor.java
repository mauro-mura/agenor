package dev.agenor.adapters.a2a;

import dev.agenor.core.Message;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Implements A2A SDK AgentExecutor to expose Jentic agents as A2A servers.
 *
 * <p>Routes incoming A2A requests to internal Jentic agents via MessageDispatcher.
 * Uses the official A2A Java SDK v0.3.2.Final API.
 *
 * <p>Usage with CDI/Quarkus:
 * <pre>{@code
 * @ApplicationScoped
 * public class MyAgentExecutorProducer {
 *     @Inject
 *     MessageDispatcher messageDispatcher;
 *
 *     @Produces
 *     public AgentExecutor createExecutor() {
 *         return new JenticAgentExecutor("my-agent", messageDispatcher, Duration.ofMinutes(5));
 *     }
 * }
 * }</pre>
 *
 * @since 0.5.0
 */
public class JenticAgentExecutor implements AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(JenticAgentExecutor.class);

    private final String internalAgentId;
    private final MessageDispatcher messageDispatcher;
    private final Duration timeout;
    private final DialogueA2AConverter converter;

    /**
     * @param internalAgentId   the Jentic agent ID to route A2A requests to
     * @param messageDispatcher the runtime message dispatcher
     * @param timeout           per-request timeout
     * @since 0.20.0
     */
    public JenticAgentExecutor(String internalAgentId, MessageDispatcher messageDispatcher, Duration timeout) {
        this.internalAgentId = internalAgentId;
        this.messageDispatcher = messageDispatcher;
        this.timeout = timeout;
        this.converter = new DialogueA2AConverter();
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
            String userMessage = extractTextFromMessage(a2aMessage);

            // Convert to Jentic DialogueMessage
            String externalSenderId = "a2a-client-" + taskId;
            DialogueMessage incomingMsg = DialogueMessage.builder()
                .conversationId(taskId)
                .senderId(externalSenderId)
                .receiverId(internalAgentId)
                .performative(Performative.REQUEST)
                .content(userMessage)
                .build();

            // Subscribe for the reply before sending to avoid races; unsubscribe when done
            CompletableFuture<Message> replyFuture = new CompletableFuture<>();
            var subscription = messageDispatcher.subscribeRecipient(externalSenderId,
                    msg -> { replyFuture.complete(msg); return java.util.concurrent.CompletableFuture.completedFuture(null); });
            Message responseMsg;
            try {
                messageDispatcher.sendTo(incomingMsg.toMessage());
                responseMsg = replyFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } finally {
                subscription.unsubscribe();
            }

            // Convert response
            DialogueMessage responseDialogue = DialogueMessage.fromMessage(responseMsg);

            // Create response part
            String responseText = responseDialogue.content() != null
                ? responseDialogue.content().toString()
                : "";
            TextPart responsePart = new TextPart(responseText, null);
            List<Part<?>> parts = List.of(responsePart);

            // Add artifact and complete based on performative
            updater.addArtifact(parts, null, null, null);

            if (responseDialogue.performative() == Performative.FAILURE ||
                responseDialogue.performative() == Performative.REFUSE) {
                updater.fail();
            } else {
                updater.complete();
            }

            log.info("A2A request completed: taskId={}", taskId);

        } catch (java.util.concurrent.TimeoutException e) {
            log.error("A2A request timeout: taskId={}", taskId);
            updater.fail();

        } catch (Exception e) {
            log.error("A2A request failed: taskId={}, error={}", taskId, e.getMessage(), e);
            updater.fail();
        }
    }

    @Override
    public void cancel(RequestContext context, EventQueue eventQueue) throws JSONRPCError {
        String taskId = context.getTaskId();
        log.info("Cancelling A2A request: taskId={}", taskId);

        TaskUpdater updater = new TaskUpdater(context, eventQueue);

        // Check if task can be cancelled
        if (context.getTask() != null) {
            TaskState state = context.getTask().getStatus().state();
            if (state == TaskState.CANCELED || state == TaskState.COMPLETED) {
                throw new TaskNotCancelableError();
            }
        }

        try {
            // Send CANCEL message to internal agent
            DialogueMessage cancelMsg = DialogueMessage.builder()
                .conversationId(taskId)
                .senderId("a2a-cancel")
                .receiverId(internalAgentId)
                .performative(Performative.CANCEL)
                .content("Cancelled by A2A client")
                .build();

            messageDispatcher.sendTo(cancelMsg.toMessage());

            // Update A2A task status
            updater.cancel();

        } catch (Exception e) {
            log.error("Failed to cancel A2A request: taskId={}", taskId, e);
            throw new TaskNotCancelableError();
        }
    }

    /**
     * Returns the internal Jentic agent ID this executor routes to.
     */
    public String getInternalAgentId() {
        return internalAgentId;
    }

    /**
     * Extracts text content from A2A Message parts.
     */
    private String extractTextFromMessage(io.a2a.spec.Message message) {
        StringBuilder textBuilder = new StringBuilder();
        if (message.getParts() != null) {
            for (Part<?> part : message.getParts()) {
                if (part instanceof TextPart textPart) {
                    textBuilder.append(textPart.getText());
                }
            }
        }
        return textBuilder.toString();
    }
}
