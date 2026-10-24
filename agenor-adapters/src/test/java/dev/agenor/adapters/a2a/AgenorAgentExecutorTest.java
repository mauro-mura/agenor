package dev.agenor.adapters.a2a;

import dev.agenor.core.AgentDescriptor;
import dev.agenor.core.AgentEndpoint;
import dev.agenor.core.Message;
import dev.agenor.core.MessageHandler;
import dev.agenor.core.directory.AgentRegistry;
import dev.agenor.core.messaging.LocalEndpointProvider;
import dev.agenor.core.messaging.MessageDispatcher;
import dev.agenor.core.messaging.Subscription;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.spec.Event;
import io.a2a.spec.Task;
import io.a2a.spec.TaskArtifactUpdateEvent;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatus;
import io.a2a.spec.TaskStatusUpdateEvent;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgenorAgentExecutorTest {

    @Mock
    private MessageDispatcher messageDispatcher;

    @Mock
    private RequestContext requestContext;

    @Mock
    private EventQueue eventQueue;

    private AgenorAgentExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new AgenorAgentExecutor("test-agent", messageDispatcher, Duration.ofSeconds(5));
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
        executor = new AgenorAgentExecutor("test-agent", messageDispatcher, Duration.ofMillis(50));

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
        var customExecutor = new AgenorAgentExecutor("agent-2", messageDispatcher, customTimeout);

        assertThat(customExecutor.getInternalAgentId()).isEqualTo("agent-2");
    }

    // -----------------------------------------------------------------------
    // The task ends on the final reply, not the first one (ADR-026)
    // -----------------------------------------------------------------------

    @Test
    void agreeThenInformCompletesOnceWithTheInformText() {
        givenRequest("task-agree-inform");
        stubDispatcherToReply(
                reply("task-agree-inform", Performative.AGREE, "on it"),
                reply("task-agree-inform", Performative.INFORM, "the answer"));

        executor.execute(requestContext, eventQueue);

        List<TaskStatus> statuses = publishedStatuses();
        assertThat(statuses).extracting(TaskStatus::state)
                .containsExactly(TaskState.SUBMITTED, TaskState.WORKING, TaskState.WORKING,
                        TaskState.COMPLETED);
        assertThat(text(statuses.get(2).message())).isEqualTo("on it");
        assertThat(publishedArtifactTexts()).containsExactly("the answer");
    }

    @Test
    void aStructuredInformReachesTheClientAsJson() {
        // A Map used to arrive as Java's toString(), {risposta=..., note=[...]}, which no A2A
        // client can parse. Found by agenor-research, whose coordinator answers with a Map.
        givenRequest("task-structured");
        var content = new java.util.LinkedHashMap<String, Object>();
        content.put("risposta", "FARO-7731");
        content.put("note", List.of("a", "b"));
        stubDispatcherToReply(reply("task-structured", Performative.INFORM, content));

        executor.execute(requestContext, eventQueue);

        assertThat(publishedArtifactTexts()).containsExactly("{\"risposta\":\"FARO-7731\",\"note\":[\"a\",\"b\"]}");
    }

    @Test
    void failureAfterAgreeFailsTheTaskWithItsText() {
        givenRequest("task-agree-failure");
        stubDispatcherToReply(
                reply("task-agree-failure", Performative.AGREE, null),
                reply("task-agree-failure", Performative.FAILURE, "disk full"));

        executor.execute(requestContext, eventQueue);

        TaskStatus last = lastStatus();
        assertThat(last.state()).isEqualTo(TaskState.FAILED);
        assertThat(text(last.message())).isEqualTo("disk full");
        assertThat(publishedArtifactTexts()).isEmpty();
    }

    @Test
    void refuseRejectsTheTask() {
        givenRequest("task-refuse");
        stubDispatcherToReply(reply("task-refuse", Performative.REFUSE, "not allowed"));

        executor.execute(requestContext, eventQueue);

        TaskStatus last = lastStatus();
        assertThat(last.state()).isEqualTo(TaskState.REJECTED);
        assertThat(text(last.message())).isEqualTo("not allowed");
    }

    @Test
    void aPerformativeThatDoesNotEndARequestFailsTheTaskAndNamesIt() {
        givenRequest("task-propose");
        stubDispatcherToReply(reply("task-propose", Performative.PROPOSE, "how about"));

        executor.execute(requestContext, eventQueue);

        TaskStatus last = lastStatus();
        assertThat(last.state()).isEqualTo(TaskState.FAILED);
        assertThat(text(last.message())).contains("PROPOSE");
    }

    @Test
    void timeoutFailsTheTaskSayingWhoDidNotReplyAndAfterHowLong() {
        executor = new AgenorAgentExecutor("test-agent", messageDispatcher, Duration.ofMillis(50));
        givenRequest("task-silent");
        stubDispatcherToReply(reply("task-silent", Performative.AGREE, null));

        executor.execute(requestContext, eventQueue);

        TaskStatus last = lastStatus();
        assertThat(last.state()).isEqualTo(TaskState.FAILED);
        assertThat(text(last.message()))
                .isEqualTo("No final reply from 'test-agent' within PT0.05S");
    }

    @Test
    void undeliverableRequestFailsAtOnceWithTheCause() {
        givenRequest("task-undeliverable");
        when(messageDispatcher.subscribeRecipient(anyString(), any(MessageHandler.class)))
                .thenReturn(mock(Subscription.class));
        when(messageDispatcher.sendTo(any(Message.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("no route")));

        long start = System.nanoTime();
        executor.execute(requestContext, eventQueue);
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        // The configured timeout is 5 s: waiting it out is the defect
        assertThat(took).isLessThan(Duration.ofSeconds(1));
        TaskStatus last = lastStatus();
        assertThat(last.state()).isEqualTo(TaskState.FAILED);
        assertThat(text(last.message())).contains("test-agent").contains("no route");
    }

    @Test
    void cancelAfterAgreeTellsTheAgentAsTheRequesterAndEndsTheWait() throws Exception {
        String taskId = "task-cancel-agreed";
        givenRequest(taskId);
        CountDownLatch agreed = new CountDownLatch(1);
        AtomicReference<MessageHandler> handler = new AtomicReference<>();
        when(messageDispatcher.subscribeRecipient(anyString(), any(MessageHandler.class)))
                .thenAnswer(inv -> {
                    handler.set(inv.getArgument(1));
                    return mock(Subscription.class);
                });
        when(messageDispatcher.sendTo(any(Message.class))).thenAnswer(inv -> {
            Message sent = inv.getArgument(0);
            if (DialogueMessage.fromMessage(sent).performative() == Performative.REQUEST) {
                handler.get().handle(reply(taskId, Performative.AGREE, null));
                agreed.countDown();
            }
            return CompletableFuture.completedFuture(null);
        });

        var running = CompletableFuture.runAsync(() -> executor.execute(requestContext, eventQueue));
        assertThat(agreed.await(2, TimeUnit.SECONDS)).isTrue();

        RequestContext cancelContext = mock(RequestContext.class);
        EventQueue cancelQueue = mock(EventQueue.class);
        when(cancelContext.getTaskId()).thenReturn(taskId);
        when(cancelContext.getContextId()).thenReturn("ctx-" + taskId);
        executor.cancel(cancelContext, cancelQueue);

        // execute returns well inside its 5 s timeout, and reports nothing of its own
        running.get(1, TimeUnit.SECONDS);
        assertThat(publishedStatuses()).extracting(TaskStatus::state)
                .containsExactly(TaskState.SUBMITTED, TaskState.WORKING);

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(messageDispatcher, times(2)).sendTo(sent.capture());
        DialogueMessage cancel = DialogueMessage.fromMessage(sent.getAllValues().get(1));
        assertThat(cancel.performative()).isEqualTo(Performative.CANCEL);
        assertThat(cancel.senderId()).isEqualTo("a2a-client-" + taskId);

        ArgumentCaptor<Event> cancelEvents = ArgumentCaptor.forClass(Event.class);
        verify(cancelQueue, atLeastOnce()).enqueueEvent(cancelEvents.capture());
        assertThat(cancelEvents.getAllValues()).filteredOn(TaskStatusUpdateEvent.class::isInstance)
                .extracting(e -> ((TaskStatusUpdateEvent) e).getStatus().state())
                .containsExactly(TaskState.CANCELED);
    }

    @Test
    void shouldThrowExceptionWhenCancellingRejectedTask() {
        // Lenient: a cancel that wrongly goes ahead must be able to succeed, so that only the
        // state check can make this test pass
        lenient().when(requestContext.getContextId()).thenReturn("ctx-rejected");
        lenient().when(messageDispatcher.sendTo(any(Message.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(requestContext.getTaskId()).thenReturn("task-rejected");
        when(requestContext.getTask()).thenReturn(new Task.Builder()
                .id("task-rejected")
                .contextId("ctx-rejected")
                .status(new TaskStatus(TaskState.REJECTED, null, null))
                .build());

        assertThatThrownBy(() -> executor.cancel(requestContext, eventQueue))
                .isInstanceOf(io.a2a.spec.TaskNotCancelableError.class);
    }

    // -----------------------------------------------------------------------
    // The requester is resolvable from other nodes
    // -----------------------------------------------------------------------

    @Test
    void registersTheRequesterAtTheDispatchersEndpointBeforeSendingAndUnregistersAfter() {
        var endpoint = new AgentEndpoint("node-1", "redis", Map.of());
        MessageDispatcher networked = networkedDispatcher(endpoint);
        AgentRegistry registry = mock(AgentRegistry.class);
        when(registry.register(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(registry.unregister(anyString())).thenReturn(CompletableFuture.completedFuture(null));
        messageDispatcher = networked;
        executor = new AgenorAgentExecutor("test-agent", networked, registry, Duration.ofSeconds(5));
        givenRequest("task-cross-node");
        stubDispatcherToReply(reply("task-cross-node", Performative.INFORM, "from node 2"));

        executor.execute(requestContext, eventQueue);

        var order = inOrder(registry, networked);
        ArgumentCaptor<AgentDescriptor> registered = ArgumentCaptor.forClass(AgentDescriptor.class);
        order.verify(registry).register(registered.capture());
        order.verify(networked).sendTo(any(Message.class));
        order.verify(registry).unregister("a2a-client-task-cross-node");
        assertThat(registered.getValue().agentId()).isEqualTo("a2a-client-task-cross-node");
        assertThat(registered.getValue().endpoint()).isEqualTo(endpoint);
        assertThat(lastStatus().state()).isEqualTo(TaskState.COMPLETED);
    }

    @Test
    void aRequesterThatCannotBeRegisteredFailsTheTaskWithoutSending() {
        MessageDispatcher networked = networkedDispatcher(new AgentEndpoint("node-1", "redis", Map.of()));
        AgentRegistry registry = mock(AgentRegistry.class);
        when(registry.register(any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("directory down")));
        when(registry.unregister(anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(networked.subscribeRecipient(anyString(), any(MessageHandler.class)))
                .thenReturn(mock(Subscription.class));
        executor = new AgenorAgentExecutor("test-agent", networked, registry, Duration.ofSeconds(5));
        givenRequest("task-unregistered");

        executor.execute(requestContext, eventQueue);

        verify(networked, never()).sendTo(any(Message.class));
        TaskStatus last = lastStatus();
        assertThat(last.state()).isEqualTo(TaskState.FAILED);
        assertThat(text(last.message())).contains("directory down");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** A dispatcher that, like Redis, tells the directory how this node is reached. */
    private static MessageDispatcher networkedDispatcher(AgentEndpoint endpoint) {
        MessageDispatcher dispatcher = mock(MessageDispatcher.class,
                withSettings().extraInterfaces(LocalEndpointProvider.class));
        when(((LocalEndpointProvider) dispatcher).localEndpoint()).thenReturn(Optional.of(endpoint));
        return dispatcher;
    }

    private void givenRequest(String taskId) {
        when(requestContext.getTaskId()).thenReturn(taskId);
        when(requestContext.getContextId()).thenReturn("ctx-" + taskId);
        when(requestContext.getTask()).thenReturn(null);
        when(requestContext.getMessage()).thenReturn(new io.a2a.spec.Message.Builder()
                .messageId("msg-" + taskId)
                .role(io.a2a.spec.Message.Role.USER)
                .parts(List.of(new TextPart("Request", null)))
                .build());
    }

    private static Message reply(String taskId, Performative performative, Object content) {
        return DialogueMessage.builder()
                .conversationId(taskId)
                .senderId("test-agent")
                .receiverId("a2a-client-" + taskId)
                .performative(performative)
                .content(content)
                .build()
                .toMessage();
    }

    /** Delivers {@code replies}, in order, to the reply subscription once the request is sent. */
    private void stubDispatcherToReply(Message... replies) {
        AtomicReference<MessageHandler> handler = new AtomicReference<>();
        when(messageDispatcher.subscribeRecipient(anyString(), any(MessageHandler.class)))
                .thenAnswer(inv -> {
                    handler.set(inv.getArgument(1));
                    return mock(Subscription.class);
                });
        when(messageDispatcher.sendTo(any(Message.class))).thenAnswer(inv -> {
            for (Message reply : replies) {
                handler.get().handle(reply);
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    private List<TaskStatus> publishedStatuses() {
        ArgumentCaptor<Event> events = ArgumentCaptor.forClass(Event.class);
        verify(eventQueue, atLeastOnce()).enqueueEvent(events.capture());
        return events.getAllValues().stream()
                .filter(TaskStatusUpdateEvent.class::isInstance)
                .map(e -> ((TaskStatusUpdateEvent) e).getStatus())
                .toList();
    }

    private TaskStatus lastStatus() {
        List<TaskStatus> statuses = publishedStatuses();
        return statuses.get(statuses.size() - 1);
    }

    private List<String> publishedArtifactTexts() {
        ArgumentCaptor<Event> events = ArgumentCaptor.forClass(Event.class);
        verify(eventQueue, atLeastOnce()).enqueueEvent(events.capture());
        return events.getAllValues().stream()
                .filter(TaskArtifactUpdateEvent.class::isInstance)
                .flatMap(e -> ((TaskArtifactUpdateEvent) e).getArtifact().parts().stream())
                .map(p -> ((TextPart) p).getText())
                .toList();
    }

    private static String text(io.a2a.spec.Message message) {
        assertThat(message).as("status message").isNotNull();
        return message.getParts().stream()
                .map(p -> ((TextPart) p).getText())
                .reduce("", String::concat);
    }

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
