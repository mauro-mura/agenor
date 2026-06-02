package dev.agenor.adapters.a2a;

import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import io.a2a.spec.Artifact;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;
import io.a2a.spec.TextPart;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Converts between Jentic DialogueMessage and A2A SDK types.
 *
 * @since 0.5.0
 */
public class DialogueA2AConverter {

    private static final String META_PERFORMATIVE = "agenor.performative";
    private static final String META_CONVERSATION_ID = "agenor.conversationId";
    private static final String META_IN_REPLY_TO = "agenor.inReplyTo";
    private static final String META_PROTOCOL = "agenor.protocol";

    /**
     * Converts A2A Task response to DialogueMessage.
     */
    public DialogueMessage fromTask(Task task, String localAgentId) {
        // Determine performative from task state
        Performative performative = mapTaskStateToPerformative(task.getStatus().state());

        // Extract content from artifacts or status message
        Object content = extractContent(task);

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
     * Converts A2A Message to DialogueMessage (for incoming requests).
     */
    public DialogueMessage fromA2AMessage(Message a2aMessage, String taskId, String senderId, String receiverId) {
        // Extract text content from parts
        String textContent = extractTextFromParts(a2aMessage.getParts());

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
     * Maps A2A TaskState to Jentic Performative.
     */
    public Performative mapTaskStateToPerformative(TaskState state) {
        return switch (state) {
            case COMPLETED -> Performative.INFORM;
            case FAILED -> Performative.FAILURE;
            case CANCELED -> Performative.CANCEL;
            case WORKING, SUBMITTED -> Performative.AGREE;
            case INPUT_REQUIRED -> Performative.QUERY;
            default -> Performative.INFORM;
        };
    }

    /**
     * Maps Jentic Performative to A2A TaskState.
     */
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

    private Object extractContent(Task task) {
        // First try artifacts
        List<Artifact> artifacts = task.getArtifacts();
        if (artifacts != null && !artifacts.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Artifact artifact : artifacts) {
                String text = extractTextFromParts(artifact.parts());
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
            return extractTextFromParts(msg.getParts());
        }

        return "";
    }

    private String extractTextFromParts(List<Part<?>> parts) {
        if (parts == null) return "";

        StringBuilder sb = new StringBuilder();
        for (Part<?> part : parts) {
            if (part instanceof TextPart textPart) {
                sb.append(textPart.getText());
            }
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
