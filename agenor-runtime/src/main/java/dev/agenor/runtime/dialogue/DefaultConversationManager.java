package dev.agenor.runtime.dialogue;

import dev.agenor.core.messaging.MessageDispatcher;
import dev.agenor.core.dialogue.CommitmentState;
import dev.agenor.core.dialogue.CommitmentTracker;
import dev.agenor.core.dialogue.Conversation;
import dev.agenor.core.dialogue.ConversationManager;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.core.dialogue.protocol.Protocol;
import dev.agenor.core.dialogue.protocol.ProtocolState;
import dev.agenor.runtime.dialogue.protocol.ProtocolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Default implementation of {@link ConversationManager}.
 *
 * @since 0.5.0
 */
public class DefaultConversationManager implements ConversationManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultConversationManager.class);

    private final String localAgentId;
    private final MessageDispatcher messageDispatcher;
    private final ProtocolRegistry protocolRegistry;
    private final CommitmentTracker commitmentTracker;

    private final Map<String, DefaultConversation> conversations = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<DialogueMessage>> pendingResponses = new ConcurrentHashMap<>();
    private final Map<String, Consumer<DialogueMessage>> messageHandlers = new ConcurrentHashMap<>();

    public DefaultConversationManager(
            String localAgentId,
            MessageDispatcher messageDispatcher) {
        this(localAgentId, messageDispatcher, new ProtocolRegistry());
    }

    /**
     * One registry, handed to both the manager and the tracker: the tracker needs it to read a
     * commitment's roles off the protocol the message was sent under, and two registries would
     * let those two answers drift.
     */
    private DefaultConversationManager(
            String localAgentId,
            MessageDispatcher messageDispatcher,
            ProtocolRegistry protocolRegistry) {
        this(localAgentId, messageDispatcher, protocolRegistry,
            new DefaultCommitmentTracker(protocolRegistry));
    }

    public DefaultConversationManager(
            String localAgentId,
            MessageDispatcher messageDispatcher,
            ProtocolRegistry protocolRegistry,
            CommitmentTracker commitmentTracker) {
        this.localAgentId = localAgentId;
        this.messageDispatcher = messageDispatcher;
        this.protocolRegistry = protocolRegistry;
        this.commitmentTracker = commitmentTracker;
    }

    /**
     * Sends a REQUEST and resolves on the final outcome ({@code INFORM}/{@code FAILURE}, or
     * an immediate {@code REFUSE}). An intermediate {@code AGREE} does not resolve the
     * returned future; observe it via {@link #onMessage(String, Consumer)} on the
     * conversation ID if needed. See ADR-026.
     */
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

    /**
     * Sends a QUERY and resolves on the first reply. QUERY expects a single
     * {@code INFORM} reply without a preceding AGREE, so the future typically
     * resolves with the result directly.
     */
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
        var conversationsByCfpId = new java.util.HashMap<String, DefaultConversation>();
        var sendFailures = new CopyOnWriteArrayList<Throwable>();

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
            conversationsByCfpId.put(message.id(), conversation);

            // Chain to collect responses; null stands for a participant that will never answer
            responseFutures.add(responseFuture.thenApply(response -> {
                if (response != null) {
                    responses.put(participant, response);
                }
                return response;
            }));

            // A participant the CFP could not reach will not answer, so it must not hold the
            // call to its deadline. Its future completes rather than fails: failing it would
            // close allOf before the reachable participants have answered.
            sendMessage(message).whenComplete((ignored, failure) -> {
                if (failure != null && !responseFuture.isDone()) {
                    var cause = unwrap(failure);
                    log.warn("CFP {} not delivered to participant '{}': {}",
                        conversation.getId(), participant, cause.getMessage());
                    conversation.setState(ProtocolState.FAILED);
                    pendingResponses.remove(message.id());
                    sendFailures.add(cause);
                    responseFuture.complete(null);
                }
            });
        }

        return CompletableFuture.allOf(responseFutures.toArray(new CompletableFuture[0]))
            .orTimeout(deadline.toMillis(), TimeUnit.MILLISECONDS)
            .handle((v, ex) -> {
                // An entry still here belongs to a participant that never answered: both a reply
                // and a failed send remove their own. Removing it decides that race, so a reply
                // cannot be collected for a conversation ended here. Without TIMEOUT the
                // conversation stays active, and the retention sweep never collects it. One that
                // did answer stays open: the initiator still has to reply to it.
                conversationsByCfpId.forEach((cfpId, conversation) -> {
                    if (pendingResponses.remove(cfpId) != null) {
                        conversation.setState(ProtocolState.TIMEOUT);
                    }
                });
                if (!participants.isEmpty() && sendFailures.size() == participants.size()) {
                    // Nobody received the CFP: an empty list would read as nobody proposing.
                    var first = sendFailures.getFirst();
                    sendFailures.stream().skip(1).forEach(first::addSuppressed);
                    throw new CompletionException(first);
                }
                return List.copyOf(responses.values());
            });
    }

    @Override
    public void handleIncoming(DialogueMessage message) {
        var conversationId = message.conversationId();

        var conversation = message.inReplyTo() == null
            ? conversations.computeIfAbsent(conversationId, id -> open(id, message))
            : conversations.get(conversationId);

        // A reply opens nothing. If this agent does not hold the conversation it answers, the
        // conversation was swept or lost with a restart, and a fresh one would start in the
        // protocol's initial state, where no reply ever moves it: active forever, never swept.
        // The rest still runs — a commitment or a pending future may outlive the conversation —
        // and the caller dispatches the message to the agent's handlers regardless, because a
        // late result may be exactly what the application is waiting for.
        if (conversation != null) {
            conversation.addMessage(message);
        } else {
            log.debug("Agent {} holds no conversation {}: {} from {} is delivered without one",
                localAgentId, conversationId, message.performative(), message.senderId());
        }

        // Update commitment if exists (only if this is a reply)
        if (message.inReplyTo() != null) {
            commitmentTracker.getByMessageId(message.inReplyTo())
                .ifPresent(commitmentId ->
                    commitmentTracker.updateFromResponse(commitmentId, message));

            // Complete pending future if waiting for response and this is the final reply
            if (shouldResolvePendingResponse(conversation, message)) {
                var pending = pendingResponses.remove(message.inReplyTo());
                if (pending != null) {
                    pending.complete(message);
                }
            }
        }

        // Notify registered handlers
        var handler = messageHandlers.get(conversationId);
        if (handler != null) {
            handler.accept(message);
        }
    }

    /**
     * The conversation a first message opens on the receiving side.
     *
     * <p>Called from {@code computeIfAbsent}, not get-then-put: the dispatcher delivers each
     * message on its own virtual thread, so two messages opening the same unknown conversation
     * arrive concurrently. A check-then-act builds two conversations and keeps whichever put ran
     * last — the message written to the discarded one is lost outright.
     */
    private DefaultConversation open(String conversationId, DialogueMessage message) {
        var protocol = message.getProtocol()
            .flatMap(protocolRegistry::get)
            .orElse(null);
        return new DefaultConversation(conversationId, protocol, message.senderId(), localAgentId,
            false);
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

            // Outcome deliberately ignored: the conversation is already CANCELLED here, and a
            // peer the CANCEL cannot reach has nothing to cancel.
            sendMessage(cancelMsg);
        }
    }

    @Override
    public void onMessage(String conversationId, Consumer<DialogueMessage> handler) {
        messageHandlers.put(conversationId, handler);
    }

    /**
     * Removes conversations whose protocol state is terminal and whose last activity is
     * older than {@code olderThan}, together with any per-conversation handler registered
     * via {@link #onMessage(String, Consumer)}.
     *
     * <p>Active conversations are never removed, however old: a slow participant must not
     * have its conversation swept out from under it. Slow stops at the deadline of a commitment
     * made in the conversation: one whose commitment the tracker has marked
     * {@link dev.agenor.core.dialogue.CommitmentState#VIOLATED} is first ended in
     * {@link ProtocolState#TIMEOUT}, and removed once it is past {@code olderThan} like any other.
     * Without that, a participant that died after {@code AGREE} — which sends neither
     * {@code INFORM} nor {@code FAILURE} — left its conversation {@code AGREED} for the lifetime
     * of the agent. A conversation still awaited by a {@code request()} is left to that
     * request's own timeout. Ending it does not refuse a late reply: the reply is recorded and
     * still reaches the agent's handlers.
     *
     * @param olderThan retention window measured from the conversation's last activity
     * @return the number of conversations removed
     * @since 0.26.0
     */
    @Override
    public int cleanup(Duration olderThan) {
        endConversationsWithViolatedCommitments();
        var cutoff = Instant.now().minus(olderThan);
        var toRemove = conversations.entrySet().stream()
            .filter(e -> e.getValue().getState().isTerminal())
            .filter(e -> !e.getValue().getLastActivity().isAfter(cutoff))
            .map(Map.Entry::getKey)
            .toList();

        toRemove.forEach(id -> {
            conversations.remove(id);
            messageHandlers.remove(id);
        });
        return toRemove.size();
    }

    /**
     * Ends each active conversation in which a commitment has been marked violated and no
     * future still waits for an answer.
     *
     * <p>Commitments are found through the messages that created them, by
     * {@link CommitmentTracker#getByMessageId}, so this works with any tracker. It runs at the
     * start of the sweep, before the tracker drops terminated commitments, so a commitment marked
     * violated by the previous sweep is still there to be read.
     */
    private void endConversationsWithViolatedCommitments() {
        for (var conversation : conversations.values()) {
            if (conversation.getState().isTerminal()) {
                continue;
            }
            var history = conversation.getHistory();
            if (history.stream().anyMatch(m -> pendingResponses.containsKey(m.id()))) {
                continue;
            }
            history.stream()
                .map(m -> commitmentTracker.getByMessageId(m.id()))
                .flatMap(Optional::stream)
                .map(commitmentTracker::get)
                .flatMap(Optional::stream)
                .filter(c -> c.getState() == CommitmentState.VIOLATED)
                .findFirst()
                .filter(c -> conversation.endIfActive(ProtocolState.TIMEOUT))
                .ifPresent(c -> log.debug(
                    "Agent {} ended conversation {}: commitment {} violated, {} never answered",
                    localAgentId, conversation.getId(), c.getId(), c.getPerformer()));
        }
    }

    /**
     * @return the commitment tracker
     */
    @Override
    public CommitmentTracker getCommitmentTracker() {
        return commitmentTracker;
    }

    /**
     * Number of replies this manager is still waiting for. Package-private: no public
     * surface observes {@code pendingResponses}, and its boundedness needs a test.
     */
    int pendingResponseCount() {
        return pendingResponses.size();
    }

    /**
     * Decides whether an incoming reply should resolve the pending-response future.
     *
     * <p>Resolves on any reply except an intermediate {@code AGREE} for which the
     * conversation's protocol still expects a subsequent {@code INFORM}/{@code FAILURE}
     * (e.g. the REQUEST protocol). See ADR-026.
     */
    private boolean shouldResolvePendingResponse(DefaultConversation conversation, DialogueMessage message) {
        if (message.performative() != Performative.AGREE) {
            return true;
        }
        if (conversation == null) {
            // No state to read the protocol's position from; an AGREE is intermediate in every
            // protocol that has one, so the future keeps waiting for the outcome or its timeout.
            return false;
        }
        return conversation.getProtocol()
            .map(protocol -> protocol.allowedPerformatives(conversation.getState(), false).stream()
                .noneMatch(p -> p == Performative.INFORM || p == Performative.FAILURE))
            .orElse(true);
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
        var commitment = performative == Performative.REQUEST
            ? commitmentTracker.createFromMessage(message)
            : null;

        var responseFuture = new CompletableFuture<DialogueMessage>();
        pendingResponses.put(message.id(), responseFuture);

        // A send the dispatcher reports as failed will never be answered: fail now with its
        // cause instead of waiting out the timeout. A no-op if a reply has already arrived.
        sendMessage(message).whenComplete((ignored, failure) -> {
            if (failure != null) {
                responseFuture.completeExceptionally(unwrap(failure));
            }
        });

        return responseFuture
            .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
            .whenComplete((response, ex) -> {
                // Unconditional: on the success path handleIncoming() has already removed it,
                // on the failure paths nothing else ever would.
                pendingResponses.remove(message.id());
                if (ex == null) {
                    return;
                }
                var cause = unwrap(ex);
                if (cause instanceof TimeoutException) {
                    conversation.setState(ProtocolState.TIMEOUT);
                    return;
                }
                // Never sent, so nobody is bound: a commitment left open would be declared
                // VIOLATED at its deadline, against an agent that never received the request.
                conversation.setState(ProtocolState.FAILED);
                if (commitment != null) {
                    commitmentTracker.cancel(commitment.getId(),
                        "REQUEST was never delivered: " + cause.getMessage());
                }
            });
    }

    private CompletableFuture<Void> sendMessage(DialogueMessage dialogueMessage) {
        var message = dialogueMessage.toMessage();
        try {
            return messageDispatcher.sendTo(message);
        } catch (RuntimeException e) {
            // One channel for failure: a dispatcher may reject a message before returning
            return CompletableFuture.failedFuture(e);
        }
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null
            ? failure.getCause()
            : failure;
    }
}
