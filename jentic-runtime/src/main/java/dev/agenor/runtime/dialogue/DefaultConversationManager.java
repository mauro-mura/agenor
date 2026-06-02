package dev.agenor.runtime.dialogue;

import dev.agenor.core.messaging.MessageDispatcher;
import dev.agenor.core.dialogue.Conversation;
import dev.agenor.core.dialogue.ConversationManager;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.core.dialogue.protocol.Protocol;
import dev.agenor.core.dialogue.protocol.ProtocolState;
import dev.agenor.runtime.dialogue.protocol.ProtocolRegistry;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Default implementation of {@link ConversationManager}.
 *
 * @since 0.5.0
 */
public class DefaultConversationManager implements ConversationManager {

    private final String localAgentId;
    private final MessageDispatcher messageDispatcher;
    private final ProtocolRegistry protocolRegistry;
    private final DefaultCommitmentTracker commitmentTracker;

    private final Map<String, DefaultConversation> conversations = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<DialogueMessage>> pendingResponses = new ConcurrentHashMap<>();
    private final Map<String, Consumer<DialogueMessage>> messageHandlers = new ConcurrentHashMap<>();

    public DefaultConversationManager(
            String localAgentId,
            MessageDispatcher messageDispatcher) {
        this(localAgentId, messageDispatcher, new ProtocolRegistry(), new DefaultCommitmentTracker());
    }

    public DefaultConversationManager(
            String localAgentId,
            MessageDispatcher messageDispatcher,
            ProtocolRegistry protocolRegistry,
            DefaultCommitmentTracker commitmentTracker) {
        this.localAgentId = localAgentId;
        this.messageDispatcher = messageDispatcher;
        this.protocolRegistry = protocolRegistry;
        this.commitmentTracker = commitmentTracker;
    }

    @Override
    public CompletableFuture<DialogueMessage> request(String targetAgentId, Object content, Duration timeout) {
        return initiateConversation(
            targetAgentId,
            Performative.REQUEST,
            content,
            "request",
            timeout
        );
    }

    @Override
    public CompletableFuture<DialogueMessage> query(String targetAgentId, Object query, Duration timeout) {
        return initiateConversation(
            targetAgentId,
            Performative.QUERY,
            query,
            "query",
            timeout
        );
    }

    @Override
    public CompletableFuture<List<DialogueMessage>> callForProposals(
            List<String> participants,
            Object taskSpec,
            Duration deadline) {

        var conversationId = UUID.randomUUID().toString();
        var protocol = protocolRegistry.get("contract-net").orElse(null);
        var responses = new ConcurrentHashMap<String, DialogueMessage>();
        var responseFutures = new java.util.ArrayList<CompletableFuture<DialogueMessage>>();

        for (String participant : participants) {
            var conversation = new DefaultConversation(
                conversationId + "-" + participant,
                protocol,
                localAgentId,
                participant,
                true
            );
            conversations.put(conversation.getId(), conversation);

            var message = DialogueMessage.builder()
                .conversationId(conversation.getId())
                .senderId(localAgentId)
                .receiverId(participant)
                .performative(Performative.CFP)
                .content(taskSpec)
                .protocol("contract-net")
                .build();

            conversation.addMessage(message);

            // Store ROOT future in pendingResponses - handleIncoming will complete it
            var responseFuture = new CompletableFuture<DialogueMessage>();
            pendingResponses.put(message.id(), responseFuture);

            // Chain to collect responses
            responseFutures.add(responseFuture.thenApply(response -> {
                responses.put(participant, response);
                return response;
            }));

            sendMessage(message);
        }

        return CompletableFuture.allOf(responseFutures.toArray(new CompletableFuture[0]))
            .orTimeout(deadline.toMillis(), TimeUnit.MILLISECONDS)
            .handle((v, ex) -> List.copyOf(responses.values()));
    }

    @Override
    public void handleIncoming(DialogueMessage message) {
        var conversationId = message.conversationId();
        var conversation = conversations.get(conversationId);

        if (conversation == null) {
            // New incoming conversation
            var protocol = message.getProtocol()
                .flatMap(protocolRegistry::get)
                .orElse(null);

            conversation = new DefaultConversation(
                conversationId,
                protocol,
                message.senderId(),
                localAgentId,
                false
            );
            conversations.put(conversationId, conversation);
        }

        conversation.addMessage(message);

        // Update commitment if exists (only if this is a reply)
        if (message.inReplyTo() != null) {
            commitmentTracker.getByMessageId(message.inReplyTo())
                .ifPresent(commitmentId ->
                    commitmentTracker.updateFromResponse(commitmentId, message));

            // Complete pending future if waiting for response
            var pending = pendingResponses.remove(message.inReplyTo());
            if (pending != null) {
                pending.complete(message);
            }
        }

        // Notify registered handlers
        var handler = messageHandlers.get(conversationId);
        if (handler != null) {
            handler.accept(message);
        }
    }

    @Override
    public CompletableFuture<Void> reply(DialogueMessage original, Performative performative, Object content) {
        var reply = original.reply(performative, content, localAgentId);

        var conversation = conversations.get(original.conversationId());
        if (conversation != null) {
            conversation.addMessage(reply);
        }

        // Create commitment if AGREE
        if (performative == Performative.AGREE) {
            commitmentTracker.createFromMessage(reply);
        }

        return sendMessage(reply);
    }

    @Override
    public Optional<Conversation> getConversation(String conversationId) {
        return Optional.ofNullable(conversations.get(conversationId));
    }

    @Override
    public List<Conversation> getActiveConversations() {
        return conversations.values().stream()
            .filter(c -> !c.isComplete())
            .map(c -> (Conversation) c)
            .toList();
    }

    @Override
    public List<Conversation> getConversationsWith(String agentId) {
        return conversations.values().stream()
            .filter(c -> c.getParticipantId().equals(agentId) || c.getInitiatorId().equals(agentId))
            .map(c -> (Conversation) c)
            .toList();
    }

    @Override
    public void cancel(String conversationId) {
        var conversation = conversations.get(conversationId);
        if (conversation != null && !conversation.isComplete()) {
            conversation.setState(ProtocolState.CANCELLED);

            // Send CANCEL message
            var cancelMsg = DialogueMessage.builder()
                .conversationId(conversationId)
                .senderId(localAgentId)
                .receiverId(conversation.isInitiator()
                    ? conversation.getParticipantId()
                    : conversation.getInitiatorId())
                .performative(Performative.CANCEL)
                .protocol(conversation.getProtocol().map(Protocol::getId).orElse(null))
                .build();

            sendMessage(cancelMsg);
        }
    }

    /**
     * Registers a handler for messages in a specific conversation.
     */
    public void onMessage(String conversationId, Consumer<DialogueMessage> handler) {
        messageHandlers.put(conversationId, handler);
    }

    /**
     * @return the commitment tracker
     */
    public DefaultCommitmentTracker getCommitmentTracker() {
        return commitmentTracker;
    }

    private CompletableFuture<DialogueMessage> initiateConversation(
            String targetAgentId,
            Performative performative,
            Object content,
            String protocolId,
            Duration timeout) {

        var conversationId = UUID.randomUUID().toString();
        var protocol = protocolRegistry.get(protocolId).orElse(null);

        var conversation = new DefaultConversation(
            conversationId,
            protocol,
            localAgentId,
            targetAgentId,
            true
        );
        conversations.put(conversationId, conversation);

        var message = DialogueMessage.builder()
            .conversationId(conversationId)
            .senderId(localAgentId)
            .receiverId(targetAgentId)
            .performative(performative)
            .content(content)
            .protocol(protocolId)
            .build();

        conversation.addMessage(message);

        // Create commitment for REQUEST
        if (performative == Performative.REQUEST) {
            commitmentTracker.createFromMessage(message);
        }

        var responseFuture = new CompletableFuture<DialogueMessage>();
        pendingResponses.put(message.id(), responseFuture);

        sendMessage(message);

        return responseFuture
            .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
            .whenComplete((response, ex) -> {
                if (ex != null) {
                    conversation.setState(ProtocolState.TIMEOUT);
                }
            });
    }

    private CompletableFuture<Void> sendMessage(DialogueMessage dialogueMessage) {
        var message = dialogueMessage.toMessage();
        return messageDispatcher.sendTo(message);
    }
}
