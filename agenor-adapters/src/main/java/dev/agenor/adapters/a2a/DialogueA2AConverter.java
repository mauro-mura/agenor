package dev.agenor.adapters.a2a;

import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import io.a2a.spec.Artifact;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;
import io.a2a.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Converts between Agenor DialogueMessage and A2A SDK types.
 *
 * <p>Only text parts are read. A {@code data} or {@code file} part is dropped with a WARN that
 * counts it, since a {@code DialogueMessage} carries one content and no transport would keep
 * anything put beside it.
 *
 * @since 0.5.0
 */
public class DialogueA2AConverter {

    private static final Logger log = LoggerFactory.getLogger(DialogueA2AConverter.class);

    private static final String META_PERFORMATIVE = "agenor.performative";
    private static final String META_CONVERSATION_ID = "agenor.conversationId";
    private static final String META_IN_REPLY_TO = "agenor.inReplyTo";
    private static final String META_PROTOCOL = "agenor.protocol";

    /**
     * Converts A2A Task response to DialogueMessage.
     */
    public DialogueMessage fromTask(Task task, String localAgentId) {
        // Determine performative from task state
        TaskState state = task.getStatus().state();
        Performative performative = mapTaskStateToPerformative(state);

        // Extract content from artifacts or status message
        String content = extractContent(task);
        if (state == TaskState.AUTH_REQUIRED || state == TaskState.UNKNOWN) {
            // Agenor cannot act on either, so the caller gets a FAILURE that says which it was
            content = "A2A task " + task.getId() + " is " + state.asString()
                + (content.isEmpty() ? "" : ": " + content);
        }

        // Extract metadata
        Map<String, Object> metadata = task.getMetadata();
        String conversationId = getMetadataString(metadata, META_CONVERSATION_ID, task.getId());
        String inReplyTo = getMetadataString(metadata, META_IN_REPLY_TO, null);
        String protocol = getMetadataString(metadata, META_PROTOCOL, null);

        return DialogueMessage.builder()
            .id(UUID.randomUUID().toString())
            .conversationId(conversationId)
            .senderId(extractSenderId(task))
            .receiverId(localAgentId)
            .performative(performative)
            .content(content)
            .protocol(protocol)
            .inReplyTo(inReplyTo)
            .build();
    }

    /**
     * Converts an agent's reply that came as a message rather than a task. A message ends the
     * exchange with its text, so it is an {@code INFORM}.
     */
    DialogueMessage fromReply(Message reply, String localAgentId) {
        String conversationId = reply.getTaskId() != null ? reply.getTaskId()
                : reply.getContextId() != null ? reply.getContextId() : reply.getMessageId();
        return DialogueMessage.builder()
            .id(UUID.randomUUID().toString())
            .conversationId(getMetadataString(reply.getMetadata(), META_CONVERSATION_ID, conversationId))
            .senderId("external-agent")
            .receiverId(localAgentId)
            .performative(Performative.INFORM)
            .content(textOf(reply.getParts(), "reply " + reply.getMessageId()))
            .build();
    }

    /**
     * Converts A2A Message to DialogueMessage (for incoming requests).
     */
    public DialogueMessage fromA2AMessage(Message a2aMessage, String taskId, String senderId, String receiverId) {
        // Extract text content from parts
        String textContent = textOf(a2aMessage.getParts(), "message for task " + taskId);

        // Default to REQUEST for incoming messages
        Performative performative = Performative.REQUEST;

        return DialogueMessage.builder()
            .id(a2aMessage.getMessageId())
            .conversationId(taskId)
            .senderId(senderId)
            .receiverId(receiverId)
            .performative(performative)
            .content(textContent)
            .build();
    }

    /**
     * Creates A2A Message for response.
     */
    public Message toA2AMessage(DialogueMessage dialogueMessage) {
        TextPart textPart = new TextPart(serializeContent(dialogueMessage.content()), null);

        return new Message.Builder()
            .messageId(dialogueMessage.id())
            .role(Message.Role.AGENT)
            .parts(List.of(textPart))
            .build();
    }

    /**
     * Creates A2A Artifact from DialogueMessage content.
     */
    public Artifact toArtifact(DialogueMessage dialogueMessage) {
        TextPart textPart = new TextPart(serializeContent(dialogueMessage.content()), null);

        return new Artifact.Builder()
            .artifactId(dialogueMessage.id())
            .name("response")
            .parts(List.of(textPart))
            .build();
    }

    /**
     * Maps A2A TaskState to Agenor Performative.
     *
     * <p>Every state is named, and none becomes {@code INFORM} by default: a state Agenor cannot
     * act on ({@code auth-required}, {@code unknown}) is a {@code FAILURE}, not a success.
     */
    public Performative mapTaskStateToPerformative(TaskState state) {
        return switch (state) {
            case COMPLETED -> Performative.INFORM;
            case FAILED, AUTH_REQUIRED, UNKNOWN -> Performative.FAILURE;
            case REJECTED -> Performative.REFUSE;
            case CANCELED -> Performative.CANCEL;
            case WORKING, SUBMITTED -> Performative.AGREE;
            case INPUT_REQUIRED -> Performative.QUERY;
        };
    }

    /**
     * Maps Agenor Performative to A2A TaskState.
     *
     * @deprecated since 0.39.0, for removal in 1.0.0. Nothing calls it, and its mapping disagrees
     *             with the one an exposed agent actually gets: {@link AgenorAgentExecutor} ends a
     *             task on {@code REFUSE} as {@code rejected}, not {@code failed}, and fails it on a
     *             performative that does not end a request, where this returns {@code working}.
     *             A state alone cannot end a task either: a custom {@code AgentExecutor} ends it
     *             with {@code TaskUpdater.complete()}, {@code fail(Message)} or
     *             {@code reject(Message)}, as {@link AgenorAgentExecutor} does.
     */
    @Deprecated(since = "0.39.0", forRemoval = true)
    public TaskState mapPerformativeToTaskState(Performative performative) {
        return switch (performative) {
            case INFORM -> TaskState.COMPLETED;
            case FAILURE -> TaskState.FAILED;
            case CANCEL -> TaskState.CANCELED;
            case AGREE -> TaskState.WORKING;
            case REFUSE -> TaskState.FAILED;
            case QUERY -> TaskState.INPUT_REQUIRED;
            default -> TaskState.WORKING;
        };
    }

    // === Private helpers ===

    private String serializeContent(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof String s) {
            return s;
        }
        return content.toString();
    }

    private String extractContent(Task task) {
        // First try artifacts
        List<Artifact> artifacts = task.getArtifacts();
        if (artifacts != null && !artifacts.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Artifact artifact : artifacts) {
                String text = textOf(artifact.parts(),
                    "artifact " + artifact.artifactId() + " of task " + task.getId());
                if (!text.isEmpty()) {
                    sb.append(text);
                }
            }
            if (!sb.isEmpty()) {
                return sb.toString();
            }
        }

        // Fall back to status message
        if (task.getStatus() != null && task.getStatus().message() != null) {
            Message msg = task.getStatus().message();
            return textOf(msg.getParts(), "status of task " + task.getId());
        }

        return "";
    }

    /**
     * The text of {@code parts}. Any other part is dropped, and a WARN says how many of each
     * kind, and where from.
     */
    static String textOf(List<Part<?>> parts, String where) {
        if (parts == null) return "";

        StringBuilder sb = new StringBuilder();
        Map<String, Integer> dropped = new TreeMap<>();
        for (Part<?> part : parts) {
            if (part instanceof TextPart textPart) {
                sb.append(textPart.getText());
            } else {
                dropped.merge(String.valueOf(part.getKind()).toLowerCase(), 1, Integer::sum);
            }
        }
        if (!dropped.isEmpty()) {
            log.warn("A2A {}: dropped non-text parts {}; only text parts are read", where, dropped);
        }
        return sb.toString();
    }

    private String extractSenderId(Task task) {
        Map<String, Object> metadata = task.getMetadata();
        if (metadata != null && metadata.containsKey("agentId")) {
            return metadata.get("agentId").toString();
        }
        return "external-agent";
    }

    private String getMetadataString(Map<String, Object> metadata, String key, String defaultValue) {
        if (metadata == null) return defaultValue;
        Object value = metadata.get(key);
        return value != null ? value.toString() : defaultValue;
    }
}
