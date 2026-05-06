package dev.jentic.adapters.a2a;

import dev.jentic.core.Message;
import dev.jentic.core.MessageHandler;
import dev.jentic.core.messaging.MessageDispatcher;
import dev.jentic.core.messaging.Subscription;
import dev.jentic.core.dialogue.DialogueMessage;
import dev.jentic.core.dialogue.Performative;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatus;
import io.a2a.spec.TextPart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JenticAgentExecutorTest {

    @Mock
    private MessageDispatcher messageDispatcher;

    @Mock
    private RequestContext requestContext;

    @Mock
    private EventQueue eventQueue;

    private JenticAgentExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new JenticAgentExecutor("test-agent", messageDispatcher, Duration.ofSeconds(5));
    }

    @Test
    void shouldReturnInternalAgentId() {
        String agentId = executor.getInternalAgentId();
        assertThat(agentId).isEqualTo("test-agent");
    }

    @Test
    void shouldExecuteSuccessfulRequest() throws Exception {
        String taskId = "task-123";
        String contextId = "ctx-123";
        when(requestContext.getTaskId()).thenReturn(taskId);
        when(requestContext.getContextId()).thenReturn(contextId);
        when(requestContext.getTask()).thenReturn(null);

        io.a2a.spec.Message a2aMessage = new io.a2a.spec.Message.Builder()
                .messageId("msg-1")
                .role(io.a2a.spec.Message.Role.USER)
                .parts(List.of(new TextPart("Hello agent", null)))
                .build();
        when(requestContext.getMessage()).thenReturn(a2aMessage);

        Message responseMsg = DialogueMessage.builder()
                .conversationId(taskId)
                .senderId("test-agent")
                .receiverId("a2a-client-" + taskId)
                .performative(Performative.INFORM)
                .content("Response message")
                .build()
                .toMessage();

        stubDispatcherToReplyImmediately(responseMsg);

        executor.execute(requestContext, eventQueue);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageDispatcher).sendTo(messageCaptor.capture());

        Message sentMessage = messageCaptor.getValue();
        DialogueMessage sentDialogue = DialogueMessage.fromMessage(sentMessage);
        assertThat(sentDialogue.conversationId()).isEqualTo(taskId);
        assertThat(sentDialogue.senderId()).contains("a2a-client");
        assertThat(sentDialogue.receiverId()).isEqualTo("test-agent");
        assertThat(sentDialogue.performative()).isEqualTo(Performative.REQUEST);
        assertThat(sentDialogue.content()).isEqualTo("Hello agent");
    }

    @Test
    void shouldHandleFailureResponse() throws Exception {
        String taskId = "task-456";
        String contextId = "ctx-456";
        when(requestContext.getTaskId()).thenReturn(taskId);
        when(requestContext.getContextId()).thenReturn(contextId);
        when(requestContext.getTask()).thenReturn(null);

        io.a2a.spec.Message a2aMessage = new io.a2a.spec.Message.Builder()
                .messageId("msg-2")
                .role(io.a2a.spec.Message.Role.USER)
                .parts(List.of(new TextPart("Request", null)))
                .build();
        when(requestContext.getMessage()).thenReturn(a2aMessage);

        Message failureMsg = DialogueMessage.builder()
                .conversationId(taskId)
                .senderId("test-agent")
                .receiverId("a2a-client-" + taskId)
                .performative(Performative.FAILURE)
                .content("Operation failed")
                .build()
                .toMessage();

        stubDispatcherToReplyImmediately(failureMsg);

        executor.execute(requestContext, eventQueue);

        verify(messageDispatcher).sendTo(argThat(msg -> "test-agent".equals(msg.receiverId())));
    }

    @Test
    void shouldHandleRefuseResponse() throws Exception {
        String taskId = "task-789";
        String contextId = "ctx-789";
        when(requestContext.getTaskId()).thenReturn(taskId);
        when(requestContext.getContextId()).thenReturn(contextId);
        when(requestContext.getTask()).thenReturn(null);

        io.a2a.spec.Message a2aMessage = new io.a2a.spec.Message.Builder()
                .messageId("msg-3")
                .role(io.a2a.spec.Message.Role.USER)
                .parts(List.of(new TextPart("Request", null)))
                .build();
        when(requestContext.getMessage()).thenReturn(a2aMessage);

        Message refuseMsg = DialogueMessage.builder()
                .conversationId(taskId)
                .senderId("test-agent")
                .receiverId("a2a-client-" + taskId)
                .performative(Performative.REFUSE)
                .content("Cannot perform this action")
                .build()
                .toMessage();

        stubDispatcherToReplyImmediately(refuseMsg);

        executor.execute(requestContext, eventQueue);

        verify(messageDispatcher).sendTo(argThat(msg -> "test-agent".equals(msg.receiverId())));
    }

    @Test
    void shouldHandleNullMessageInContext() {
        // Given
        when(requestContext.getTaskId()).thenReturn("task-error");
        when(requestContext.getContextId()).thenReturn("ctx-error");
        when(requestContext.getTask()).thenReturn(null);
        when(requestContext.getMessage()).thenReturn(null);

        // When - execute handles the exception internally, no exception thrown
        executor.execute(requestContext, eventQueue);

        // Then - getMessage was called
        verify(requestContext).getMessage();
    }

    @Test
    void shouldHandleTimeout() throws Exception {
        // Use a very short timeout so the test doesn't wait 5s
        executor = new JenticAgentExecutor("test-agent", messageDispatcher, Duration.ofMillis(50));

        String taskId = "task-timeout";
        String contextId = "ctx-timeout";
        when(requestContext.getTaskId()).thenReturn(taskId);
        when(requestContext.getContextId()).thenReturn(contextId);
        when(requestContext.getTask()).thenReturn(null);

        io.a2a.spec.Message a2aMessage = new io.a2a.spec.Message.Builder()
                .messageId("msg-4")
                .role(io.a2a.spec.Message.Role.USER)
                .parts(List.of(new TextPart("Request", null)))
                .build();
        when(requestContext.getMessage()).thenReturn(a2aMessage);

        // subscribeRecipient returns a subscription but never delivers a reply → timeout
        Subscription mockSub = mock(Subscription.class);
        when(messageDispatcher.subscribeRecipient(anyString(), any(MessageHandler.class)))
                .thenReturn(mockSub);
        when(messageDispatcher.sendTo(any(Message.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        executor.execute(requestContext, eventQueue);

        verify(messageDispatcher).subscribeRecipient(anyString(), any(MessageHandler.class));
        verify(messageDispatcher).sendTo(argThat(msg -> "test-agent".equals(msg.receiverId())));
    }

    @Test
    void shouldHandleExecutionException() throws Exception {
        String taskId = "task-exception";
        String contextId = "ctx-exception";
        when(requestContext.getTaskId()).thenReturn(taskId);
        when(requestContext.getContextId()).thenReturn(contextId);
        when(requestContext.getTask()).thenReturn(null);

        io.a2a.spec.Message a2aMessage = new io.a2a.spec.Message.Builder()
                .messageId("msg-5")
                .role(io.a2a.spec.Message.Role.USER)
                .parts(List.of(new TextPart("Request", null)))
                .build();
        when(requestContext.getMessage()).thenReturn(a2aMessage);

        when(messageDispatcher.subscribeRecipient(anyString(), any(MessageHandler.class)))
                .thenThrow(new RuntimeException("Service error"));

        executor.execute(requestContext, eventQueue);

        verify(messageDispatcher).subscribeRecipient(anyString(), any(MessageHandler.class));
    }

    @Test
    void shouldCancelRequest() throws Exception {
        String taskId = "task-cancel";
        String contextId = "ctx-cancel";
        when(requestContext.getTaskId()).thenReturn(taskId);
        when(requestContext.getContextId()).thenReturn(contextId);
        when(requestContext.getTask()).thenReturn(null);

        when(messageDispatcher.sendTo(any(Message.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        executor.cancel(requestContext, eventQueue);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageDispatcher).sendTo(messageCaptor.capture());

        Message cancelMessage = messageCaptor.getValue();
        DialogueMessage cancelDialogue = DialogueMessage.fromMessage(cancelMessage);
        assertThat(cancelDialogue.conversationId()).isEqualTo(taskId);
        assertThat(cancelDialogue.performative()).isEqualTo(Performative.CANCEL);
        assertThat(cancelDialogue.receiverId()).isEqualTo("test-agent");
    }

    @Test
    void shouldThrowExceptionWhenCancellingCancelledTask() {
        String taskId = "task-already-cancelled";
        String contextId = "ctx-cancelled";
        when(requestContext.getTaskId()).thenReturn(taskId);
        when(requestContext.getContextId()).thenReturn(contextId);

        TaskStatus cancelledStatus = new TaskStatus(TaskState.CANCELED, null, null);
        Task cancelledTask = new Task.Builder()
                .id(taskId)
                .contextId(contextId)
                .status(cancelledStatus)
                .build();
        when(requestContext.getTask()).thenReturn(cancelledTask);

        assertThatThrownBy(() -> executor.cancel(requestContext, eventQueue))
                .isInstanceOf(io.a2a.spec.TaskNotCancelableError.class);
    }

    @Test
    void shouldThrowExceptionWhenCancellingCompletedTask() {
        String taskId = "task-completed";
        String contextId = "ctx-completed";
        when(requestContext.getTaskId()).thenReturn(taskId);
        when(requestContext.getContextId()).thenReturn(contextId);

        TaskStatus completedStatus = new TaskStatus(TaskState.COMPLETED, null, null);
        Task completedTask = new Task.Builder()
                .id(taskId)
                .contextId(contextId)
                .status(completedStatus)
                .build();
        when(requestContext.getTask()).thenReturn(completedTask);

        assertThatThrownBy(() -> executor.cancel(requestContext, eventQueue))
                .isInstanceOf(io.a2a.spec.TaskNotCancelableError.class);
    }

    @Test
    void shouldHandleCancelException() {
        String taskId = "task-cancel-error";
        String contextId = "ctx-cancel-error";
        when(requestContext.getTaskId()).thenReturn(taskId);
        when(requestContext.getContextId()).thenReturn(contextId);
        when(requestContext.getTask()).thenReturn(null);

        when(messageDispatcher.sendTo(any(Message.class)))
                .thenThrow(new RuntimeException("Cancel failed"));

        assertThatThrownBy(() -> executor.cancel(requestContext, eventQueue))
                .isInstanceOf(io.a2a.spec.TaskNotCancelableError.class);
    }

    @Test
    void shouldExtractMultipleTextParts() throws Exception {
        String taskId = "task-multipart";
        String contextId = "ctx-multipart";
        when(requestContext.getTaskId()).thenReturn(taskId);
        when(requestContext.getContextId()).thenReturn(contextId);
        when(requestContext.getTask()).thenReturn(null);

        io.a2a.spec.Message a2aMessage = new io.a2a.spec.Message.Builder()
                .messageId("msg-6")
                .role(io.a2a.spec.Message.Role.USER)
                .parts(List.of(
                        new TextPart("Part 1 ", null),
                        new TextPart("Part 2", null)
                ))
                .build();
        when(requestContext.getMessage()).thenReturn(a2aMessage);

        Message responseMsg = DialogueMessage.builder()
                .conversationId(taskId)
                .senderId("test-agent")
                .receiverId("a2a-client-" + taskId)
                .performative(Performative.INFORM)
                .content("Response")
                .build()
                .toMessage();

        stubDispatcherToReplyImmediately(responseMsg);

        executor.execute(requestContext, eventQueue);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageDispatcher).sendTo(messageCaptor.capture());

        Message sentMessage = messageCaptor.getValue();
        DialogueMessage sentDialogue = DialogueMessage.fromMessage(sentMessage);
        assertThat(sentDialogue.content()).isEqualTo("Part 1 Part 2");
    }

    @Test
    void shouldHandleNullContentInResponse() throws Exception {
        String taskId = "task-null-content";
        String contextId = "ctx-null-content";
        when(requestContext.getTaskId()).thenReturn(taskId);
        when(requestContext.getContextId()).thenReturn(contextId);
        when(requestContext.getTask()).thenReturn(null);

        io.a2a.spec.Message a2aMessage = new io.a2a.spec.Message.Builder()
                .messageId("msg-7")
                .role(io.a2a.spec.Message.Role.USER)
                .parts(List.of(new TextPart("Request", null)))
                .build();
        when(requestContext.getMessage()).thenReturn(a2aMessage);

        Message responseMsg = DialogueMessage.builder()
                .conversationId(taskId)
                .senderId("test-agent")
                .receiverId("a2a-client-" + taskId)
                .performative(Performative.INFORM)
                .content(null)
                .build()
                .toMessage();

        stubDispatcherToReplyImmediately(responseMsg);

        executor.execute(requestContext, eventQueue);

        verify(messageDispatcher).sendTo(argThat(msg -> "test-agent".equals(msg.receiverId())));
    }

    @Test
    void shouldUseConfiguredTimeout() {
        Duration customTimeout = Duration.ofSeconds(30);
        var customExecutor = new JenticAgentExecutor("agent-2", messageDispatcher, customTimeout);

        assertThat(customExecutor.getInternalAgentId()).isEqualTo("agent-2");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void stubDispatcherToReplyImmediately(Message replyMsg) throws Exception {
        Subscription mockSub = mock(Subscription.class);
        Answer<Subscription> triggerHandler = invocation -> {
            MessageHandler handler = invocation.getArgument(1);
            handler.handle(replyMsg);
            return mockSub;
        };
        when(messageDispatcher.subscribeRecipient(anyString(), any(MessageHandler.class)))
                .thenAnswer(triggerHandler);
        when(messageDispatcher.sendTo(any(Message.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
    }
}
