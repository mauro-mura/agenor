package dev.agenor.adapters.a2a;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import io.a2a.spec.Artifact;
import io.a2a.spec.DataPart;
import io.a2a.spec.Message;
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatus;
import io.a2a.spec.TextPart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for DialogueA2AConverter.
 */
class DialogueA2AConverterTest {

    private DialogueA2AConverter converter;

    @BeforeEach
    void setUp() {
        converter = new DialogueA2AConverter();
    }

    @Test
    void shouldConvertDialogueMessageToA2AMessage() {
        var msg = DialogueMessage.builder()
            .id("msg-1")
            .conversationId("conv-1")
            .senderId("sender")
            .receiverId("receiver")
            .performative(Performative.INFORM)
            .content("result data")
            .build();

        Message a2aMsg = converter.toA2AMessage(msg);

        assertThat(a2aMsg.getMessageId()).isEqualTo("msg-1");
        assertThat(a2aMsg.getRole()).isEqualTo(Message.Role.AGENT);
        assertThat(a2aMsg.getParts()).hasSize(1);

        TextPart part = (TextPart) a2aMsg.getParts().get(0);
        assertThat(part.getText()).isEqualTo("result data");
    }

    @Test
    void shouldConvertA2AMessageToDialogueMessage() {
        TextPart textPart = new TextPart("hello world", null);
        Message a2aMsg = new Message.Builder()
            .messageId("msg-1")
            .role(Message.Role.USER)
            .parts(List.of(textPart))
            .build();

        DialogueMessage msg = converter.fromA2AMessage(a2aMsg, "task-1", "external-sender", "local-agent");

        assertThat(msg.id()).isEqualTo("msg-1");
        assertThat(msg.conversationId()).isEqualTo("task-1");
        assertThat(msg.senderId()).isEqualTo("external-sender");
        assertThat(msg.receiverId()).isEqualTo("local-agent");
        assertThat(msg.performative()).isEqualTo(Performative.REQUEST);
        assertThat(msg.content()).isEqualTo("hello world");
    }

    @Test
    void shouldConvertTaskToDialogueMessage() {
        TextPart textPart = new TextPart("Task completed successfully", null);
        Artifact artifact = new Artifact.Builder()
            .artifactId("art-1")
            .parts(List.of(textPart))
            .build();

        TaskStatus status = new TaskStatus(TaskState.COMPLETED, null, null);

        Task task = new Task.Builder()
            .id("task-1")
            .status(status)
            .artifacts(List.of(artifact))
            .metadata(Map.of(
                "agenor.conversationId", "conv-1",
                "agentId", "remote-agent"
            ))
            .contextId("ctx-1")
            .build();

        DialogueMessage msg = converter.fromTask(task, "local-agent");

        assertThat(msg.conversationId()).isEqualTo("conv-1");
        assertThat(msg.senderId()).isEqualTo("remote-agent");
        assertThat(msg.receiverId()).isEqualTo("local-agent");
        assertThat(msg.performative()).isEqualTo(Performative.INFORM);
        assertThat(msg.content().toString()).contains("Task completed successfully");
    }

    @Test
    void shouldCreateArtifactFromDialogueMessage() {
        var msg = DialogueMessage.builder()
            .id("msg-1")
            .senderId("sender")
            .performative(Performative.INFORM)
            .content("artifact content")
            .build();

        Artifact artifact = converter.toArtifact(msg);

        assertThat(artifact.artifactId()).isEqualTo("msg-1");
        assertThat(artifact.name()).isEqualTo("response");
        assertThat(artifact.parts()).hasSize(1);

        TextPart part = (TextPart) artifact.parts().get(0);
        assertThat(part.getText()).isEqualTo("artifact content");
    }

    @Test
    void shouldMapTaskStateToPerformative() {
        assertThat(converter.mapTaskStateToPerformative(TaskState.COMPLETED))
            .isEqualTo(Performative.INFORM);
        assertThat(converter.mapTaskStateToPerformative(TaskState.FAILED))
            .isEqualTo(Performative.FAILURE);
        assertThat(converter.mapTaskStateToPerformative(TaskState.CANCELED))
            .isEqualTo(Performative.CANCEL);
        assertThat(converter.mapTaskStateToPerformative(TaskState.WORKING))
            .isEqualTo(Performative.AGREE);
        assertThat(converter.mapTaskStateToPerformative(TaskState.SUBMITTED))
            .isEqualTo(Performative.AGREE);
        assertThat(converter.mapTaskStateToPerformative(TaskState.INPUT_REQUIRED))
            .isEqualTo(Performative.QUERY);
    }

    @Test
    @SuppressWarnings("removal") // the deprecated mapping itself
    void shouldMapPerformativeToTaskState() {
        assertThat(converter.mapPerformativeToTaskState(Performative.INFORM))
            .isEqualTo(TaskState.COMPLETED);
        assertThat(converter.mapPerformativeToTaskState(Performative.FAILURE))
            .isEqualTo(TaskState.FAILED);
        assertThat(converter.mapPerformativeToTaskState(Performative.CANCEL))
            .isEqualTo(TaskState.CANCELED);
        assertThat(converter.mapPerformativeToTaskState(Performative.AGREE))
            .isEqualTo(TaskState.WORKING);
        assertThat(converter.mapPerformativeToTaskState(Performative.REFUSE))
            .isEqualTo(TaskState.FAILED);
        assertThat(converter.mapPerformativeToTaskState(Performative.QUERY))
            .isEqualTo(TaskState.INPUT_REQUIRED);
    }

    @Test
    void shouldHandleNullContent() {
        var msg = DialogueMessage.builder()
            .id("msg-1")
            .senderId("sender")
            .performative(Performative.AGREE)
            .content(null)
            .build();

        Message a2aMsg = converter.toA2AMessage(msg);

        assertThat(a2aMsg.getParts()).hasSize(1);
        TextPart part = (TextPart) a2aMsg.getParts().get(0);
        assertThat(part.getText()).isEmpty();
    }

    @Test
    void shouldExtractContentFromStatusMessage() {
        TextPart statusTextPart = new TextPart("Status message content", null);
        Message statusMessage = new Message.Builder()
            .messageId("status-msg")
            .role(Message.Role.AGENT)
            .parts(List.of(statusTextPart))
            .build();

        TaskStatus status = new TaskStatus(TaskState.COMPLETED, statusMessage, null);

        Task task = new Task.Builder()
            .id("task-1")
            .status(status)
            .contextId("ctx-1")
            .build();

        DialogueMessage msg = converter.fromTask(task, "local-agent");

        assertThat(msg.content()).isEqualTo("Status message content");
    }

    /**
     * AgenorAgentExecutor reports an agent's REFUSE as {@code rejected}, with the refusal text in
     * the status message. A caller on AgenorA2AClient has to read that back as a refusal, not as
     * the INFORM the default mapping used to give it.
     */
    @Test
    void rejectedTaskIsARefusalWithItsText() {
        Message statusMessage = new Message.Builder()
            .messageId("status-msg")
            .role(Message.Role.AGENT)
            .parts(List.of(new TextPart("not allowed", null)))
            .build();

        Task task = new Task.Builder()
            .id("task-rejected")
            .status(new TaskStatus(TaskState.REJECTED, statusMessage, null))
            .contextId("ctx-1")
            .build();

        DialogueMessage msg = converter.fromTask(task, "local-agent");

        assertThat(msg.performative()).isEqualTo(Performative.REFUSE);
        assertThat(msg.content()).isEqualTo("not allowed");
    }

    /** An agent may answer with a message instead of a task; that ends the exchange too. */
    @Test
    void aReplyMessageIsAnInformWithItsText() {
        Message reply = new Message.Builder()
            .messageId("reply-1")
            .role(Message.Role.AGENT)
            .contextId("ctx-1")
            .parts(List.of(new TextPart("echo: hello", null)))
            .build();

        DialogueMessage msg = converter.fromReply(reply, "local-agent");

        assertThat(msg.performative()).isEqualTo(Performative.INFORM);
        assertThat(msg.content()).isEqualTo("echo: hello");
        assertThat(msg.conversationId()).isEqualTo("ctx-1");
        assertThat(msg.receiverId()).isEqualTo("local-agent");
    }

    @Test
    void shouldUseDefaultSenderIdWhenNotInMetadata() {
        TaskStatus status = new TaskStatus(TaskState.COMPLETED, null, null);

        Task task = new Task.Builder()
            .id("task-1")
            .status(status)
            .contextId("ctx-1")
            .build();

        DialogueMessage msg = converter.fromTask(task, "local-agent");

        assertThat(msg.senderId()).isEqualTo("external-agent");
    }

    // -----------------------------------------------------------------------
    // mapPerformativeToTaskState - default branch
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("removal") // the deprecated mapping itself
    void shouldMapRequestPerformativeToWorkingState() {
        // REQUEST is not explicitly mapped → hits the default branch
        var state = converter.mapPerformativeToTaskState(Performative.REQUEST);
        assertThat(state).isEqualTo(TaskState.WORKING);
    }

    @Test
    @SuppressWarnings("removal") // the deprecated mapping itself
    void shouldMapProposePerformativeToWorkingState() {
        var state = converter.mapPerformativeToTaskState(Performative.PROPOSE);
        assertThat(state).isEqualTo(TaskState.WORKING);
    }

    @Test
    @SuppressWarnings("removal") // the deprecated mapping itself
    void shouldMapCfpPerformativeToWorkingState() {
        var state = converter.mapPerformativeToTaskState(Performative.CFP);
        assertThat(state).isEqualTo(TaskState.WORKING);
    }

    // -----------------------------------------------------------------------
    // fromTask with empty artifacts
    // -----------------------------------------------------------------------

    @Test
    void shouldExtractEmptyContentWhenArtifactsEmpty() {
        TaskStatus status = new TaskStatus(TaskState.COMPLETED, null, null);
        Task task = new Task.Builder()
                .id("task-empty")
                .status(status)
                .artifacts(Collections.emptyList())
                .contextId("ctx-1")
                .build();

        var msg = converter.fromTask(task, "local-agent");

        assertThat(msg.content()).isEqualTo("");
    }

    @Test
    void shouldExtractEmptyContentWhenArtifactsIsNull() {
        // null artifacts list → skip to status message which is also null → return ""
        TaskStatus status = new TaskStatus(TaskState.COMPLETED, null, null);
        Task task = new Task.Builder()
                .id("task-null-art")
                .status(status)
                .contextId("ctx-1")
                .build();

        var msg = converter.fromTask(task, "local-agent");

        assertThat(msg.content()).isEqualTo("");
    }

    @Test
    void shouldFallThroughToStatusMessageWhenArtifactTextIsEmpty() {
        // Artifact whose only TextPart has empty text → sb stays empty → falls through to status message
        TextPart emptyTextPart = new TextPart("", null);
        Artifact artifactWithEmptyText = new Artifact.Builder()
                .artifactId("art-empty-text")
                .parts(List.of(emptyTextPart))
                .build();

        io.a2a.spec.Message statusMsg = new io.a2a.spec.Message.Builder()
                .messageId("sm-1")
                .role(io.a2a.spec.Message.Role.AGENT)
                .parts(List.of(new TextPart("status fallback", null)))
                .build();
        TaskStatus status = new TaskStatus(TaskState.COMPLETED, statusMsg, null);

        Task task = new Task.Builder()
                .id("task-empty-text-art")
                .status(status)
                .artifacts(List.of(artifactWithEmptyText))
                .contextId("ctx-1")
                .build();

        var msg = converter.fromTask(task, "local-agent");

        // Artifact text is empty → falls back to status message content
        assertThat(msg.content()).isEqualTo("status fallback");
    }

    // -----------------------------------------------------------------------
    // Metadata edge cases
    // -----------------------------------------------------------------------

    @Test
    void shouldUseTaskIdAsConversationIdWhenMetadataMissingConvId() {
        TaskStatus status = new TaskStatus(TaskState.COMPLETED, null, null);
        Task task = new Task.Builder()
                .id("task-999")
                .status(status)
                .contextId("ctx-999")
                .metadata(Map.of("agentId", "remote-agent"))
                .build();

        var msg = converter.fromTask(task, "local");

        // agenor.conversationId not in metadata → fallback to task.getId()
        assertThat(msg.conversationId()).isEqualTo("task-999");
    }

    @Test
    void shouldHandleNullMetadata() {
        TaskStatus status = new TaskStatus(TaskState.COMPLETED, null, null);
        Task task = new Task.Builder()
                .id("task-no-meta")
                .status(status)
                .contextId("ctx-1")
                .build();

        var msg = converter.fromTask(task, "local-agent");

        // No metadata → defaults
        assertThat(msg.senderId()).isEqualTo("external-agent");
        assertThat(msg.conversationId()).isEqualTo("task-no-meta");
    }

    @Test
    void shouldExtractInReplyToAndProtocolFromMetadata() {
        TaskStatus status = new TaskStatus(TaskState.COMPLETED, null, null);
        Task task = new Task.Builder()
                .id("task-meta")
                .status(status)
                .contextId("ctx-1")
                .metadata(Map.of(
                        "agenor.conversationId", "conv-42",
                        "agenor.inReplyTo", "msg-original",
                        "agenor.protocol", "FIPA-Request"
                ))
                .build();

        var msg = converter.fromTask(task, "local-agent");

        assertThat(msg.conversationId()).isEqualTo("conv-42");
        assertThat(msg.inReplyTo()).isEqualTo("msg-original");
        assertThat(msg.protocol()).isEqualTo("FIPA-Request");
    }

    // -----------------------------------------------------------------------
    // mapTaskStateToPerformative - all states
    // -----------------------------------------------------------------------

    @Test
    void shouldMapAllTaskStatesToPerformatives() {
        assertThat(converter.mapTaskStateToPerformative(TaskState.COMPLETED)).isEqualTo(Performative.INFORM);
        assertThat(converter.mapTaskStateToPerformative(TaskState.FAILED)).isEqualTo(Performative.FAILURE);
        assertThat(converter.mapTaskStateToPerformative(TaskState.CANCELED)).isEqualTo(Performative.CANCEL);
        assertThat(converter.mapTaskStateToPerformative(TaskState.WORKING)).isEqualTo(Performative.AGREE);
        assertThat(converter.mapTaskStateToPerformative(TaskState.SUBMITTED)).isEqualTo(Performative.AGREE);
        assertThat(converter.mapTaskStateToPerformative(TaskState.INPUT_REQUIRED)).isEqualTo(Performative.QUERY);
        assertThat(converter.mapTaskStateToPerformative(TaskState.REJECTED)).isEqualTo(Performative.REFUSE);
        assertThat(converter.mapTaskStateToPerformative(TaskState.AUTH_REQUIRED)).isEqualTo(Performative.FAILURE);
        assertThat(converter.mapTaskStateToPerformative(TaskState.UNKNOWN)).isEqualTo(Performative.FAILURE);
    }

    /** Only a completed task is a success; the mapping used to give INFORM to any state it did not name. */
    @ParameterizedTest
    @EnumSource(value = TaskState.class, names = "COMPLETED", mode = EnumSource.Mode.EXCLUDE)
    void noStateButCompletedIsAnInform(TaskState state) {
        assertThat(converter.mapTaskStateToPerformative(state)).isNotEqualTo(Performative.INFORM);
    }

    /** Agenor cannot act on auth-required or unknown: a FAILURE that names the state, and keeps the agent's text. */
    @ParameterizedTest
    @EnumSource(value = TaskState.class, names = {"AUTH_REQUIRED", "UNKNOWN"})
    void aStateAgenorCannotActOnIsAFailureNamingIt(TaskState state) {
        Message statusMessage = new Message.Builder()
            .messageId("status-msg")
            .role(Message.Role.AGENT)
            .parts(List.of(new TextPart("sign in first", null)))
            .build();
        Task task = new Task.Builder()
            .id("task-stuck")
            .status(new TaskStatus(state, statusMessage, null))
            .contextId("ctx-1")
            .build();

        DialogueMessage msg = converter.fromTask(task, "local-agent");

        assertThat(msg.performative()).isEqualTo(Performative.FAILURE);
        assertThat(msg.content())
            .isEqualTo("A2A task task-stuck is " + state.asString() + ": sign in first");
    }

    // -----------------------------------------------------------------------
    // Non-text parts are dropped, not silently
    // -----------------------------------------------------------------------

    @Test
    void aDataPartBesideTheTextIsDroppedWithAWarn() {
        Artifact artifact = new Artifact.Builder()
            .artifactId("sintesi")
            .parts(List.of(new TextPart("Sintesi di 1 note", null),
                new DataPart(Map.of("note", 1, "caratteri", 34))))
            .build();
        Task task = new Task.Builder()
            .id("task-data")
            .status(new TaskStatus(TaskState.COMPLETED))
            .contextId("ctx-1")
            .artifacts(List.of(artifact))
            .build();

        List<ILoggingEvent> warnings = warningsDuring(() -> {
            DialogueMessage msg = converter.fromTask(task, "local-agent");
            assertThat(msg.content()).isEqualTo("Sintesi di 1 note");
        });

        assertThat(warnings).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
            .contains("artifact sintesi of task task-data")
            .contains("{data=1}"));
    }

    @Test
    void textAloneLogsNothing() {
        Task task = new Task.Builder()
            .id("task-text")
            .status(new TaskStatus(TaskState.COMPLETED))
            .contextId("ctx-1")
            .artifacts(List.of(new Artifact.Builder()
                .artifactId("a")
                .parts(List.of(new TextPart("only text", null)))
                .build()))
            .build();

        assertThat(warningsDuring(() -> converter.fromTask(task, "local-agent"))).isEmpty();
    }

    private static List<ILoggingEvent> warningsDuring(Runnable action) {
        var logger = (Logger) LoggerFactory.getLogger(DialogueA2AConverter.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    // -----------------------------------------------------------------------
    // Non-string content serialization in toA2AMessage / toArtifact
    // -----------------------------------------------------------------------

    @Test
    void shouldSerializeNonStringContentToA2AMessage() {
        var msg = dev.agenor.core.dialogue.DialogueMessage.builder()
                .id("msg-obj")
                .senderId("sender")
                .performative(Performative.INFORM)
                .content(List.of("a", "b", "c"))
                .build();

        var a2aMsg = converter.toA2AMessage(msg);

        assertThat(a2aMsg.getParts()).hasSize(1);
        TextPart part = (TextPart) a2aMsg.getParts().get(0);
        assertThat(part.getText()).contains("a");
    }

    @Test
    void shouldSerializeNonStringContentToArtifact() {
        var msg = dev.agenor.core.dialogue.DialogueMessage.builder()
                .id("art-obj")
                .senderId("sender")
                .performative(Performative.INFORM)
                .content(42)
                .build();

        var artifact = converter.toArtifact(msg);

        assertThat(artifact.parts()).hasSize(1);
        TextPart part = (TextPart) artifact.parts().get(0);
        assertThat(part.getText()).isEqualTo("42");
    }
    record Draft(String ticket, java.time.Instant at) {}

    @Test
    void contentTextKeepsAStringAsItIs() {
        assertThat(DialogueA2AConverter.contentText("plain {not json}")).isEqualTo("plain {not json}");
        assertThat(DialogueA2AConverter.contentText(null)).isEmpty();
    }

    @Test
    void contentTextWritesAnythingElseAsJson() {
        assertThat(DialogueA2AConverter.contentText(Map.of("k", 1))).isEqualTo("{\"k\":1}");
        assertThat(DialogueA2AConverter.contentText(List.of(1, 2))).isEqualTo("[1,2]");
        assertThat(DialogueA2AConverter.contentText(new Draft("T-1", java.time.Instant.parse("2026-10-24T10:00:00Z"))))
                .as("a record, with its date as ISO text")
                .isEqualTo("{\"ticket\":\"T-1\",\"at\":\"2026-10-24T10:00:00Z\"}");
    }

    @Test
    void contentTextFallsBackToToStringWithAWarn() {
        Object opaque = new Object() {
            private final Object self = this;   // a cycle Jackson cannot write

            public Object getSelf() {
                return self;
            }

            @Override
            public String toString() {
                return "opaque";
            }
        };
        Logger logger = (Logger) LoggerFactory.getLogger(DialogueA2AConverter.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(DialogueA2AConverter.contentText(opaque)).isEqualTo("opaque");
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).anySatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN));
    }
}
