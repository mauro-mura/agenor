package dev.agenor.core.dialogue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Manages dialogue conversations for an agent.
 *
 * <p>Provides high-level methods for initiating common interaction
 * patterns and tracking ongoing conversations.
 *
 * @since 0.5.0
 */
public interface ConversationManager {

    /**
     * Sends a request to another agent and waits for response.
     *
     * @param targetAgentId the agent to request from
     * @param content the request content
     * @param timeout maximum time to wait
     * @return future containing the response
     */
    CompletableFuture<DialogueMessage> request(
        String targetAgentId,
        Object content,
        Duration timeout
    );

    /**
     * Sends a query to another agent and waits for information.
     *
     * @param targetAgentId the agent to query
     * @param query the query content
     * @param timeout maximum time to wait
     * @return future containing the response
     */
    CompletableFuture<DialogueMessage> query(
        String targetAgentId,
        Object query,
        Duration timeout
    );

    /**
     * Initiates a call for proposals to multiple agents.
     *
     * @param participants list of agent IDs to send CFP to
     * @param taskSpec the task specification
     * @param deadline time limit for proposals
     * @return future containing all received proposals
     */
    CompletableFuture<List<DialogueMessage>> callForProposals(
        List<String> participants,
        Object taskSpec,
        Duration deadline
    );

    /**
     * Handles an incoming dialogue message.
     *
     * @param message the incoming message
     */
    void handleIncoming(DialogueMessage message);

    /**
     * Sends a reply to a received message.
     *
     * @param original the message being replied to
     * @param performative the reply performative
     * @param content the reply content
     * @return future completing when sent
     */
    CompletableFuture<Void> reply(
        DialogueMessage original,
        Performative performative,
        Object content
    );

    /**
     * Gets a conversation by ID.
     *
     * @param conversationId the conversation ID
     * @return the conversation if found
     */
    Optional<Conversation> getConversation(String conversationId);

    /**
     * Gets all active (non-terminal) conversations.
     *
     * @return list of active conversations
     */
    List<Conversation> getActiveConversations();

    /**
     * Gets all conversations with a specific agent.
     *
     * @param agentId the agent ID
     * @return list of conversations with that agent
     */
    List<Conversation> getConversationsWith(String agentId);

    /**
     * Cancels an ongoing conversation.
     *
     * @param conversationId the conversation to cancel
     */
    void cancel(String conversationId);

    /**
     * Registers a handler invoked for every message received in a specific conversation,
     * including intermediate replies (e.g. {@code AGREE}) that do not resolve the future
     * returned by {@link #request(String, Object, Duration)}.
     *
     * @param conversationId the conversation to observe
     * @param handler callback invoked for each incoming message in that conversation
     */
    void onMessage(String conversationId, Consumer<DialogueMessage> handler);
}
