package dev.jentic.runtime.dialogue;

import dev.jentic.core.Agent;
import dev.jentic.core.Message;
import dev.jentic.core.MessageHandler;
import dev.jentic.core.messaging.MessageDispatcher;
import dev.jentic.core.messaging.Subscription;
import dev.jentic.core.dialogue.Conversation;
import dev.jentic.core.dialogue.ConversationManager;
import dev.jentic.core.dialogue.DialogueMessage;
import dev.jentic.core.dialogue.Performative;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Provides dialogue capabilities to agents via composition.
 * 
 * <p>Usage:
 * <pre>{@code
 * public class MyAgent extends BaseAgent {
 *     private final DialogueCapability dialogue;
 *     
 *     public MyAgent() {
 *         this.dialogue = new DialogueCapability(this);
 *     }
 *     
 *     @Override
 *     protected void onStart() {
 *         dialogue.initialize(getMessageService());
 *     }
 *     
 *     @DialogueHandler(performatives = REQUEST)
 *     public void handleRequest(DialogueMessage msg) {
 *         dialogue.reply(msg, Performative.AGREE, "OK");
 *     }
 * }
 * }</pre>
 * 
 * @since 0.5.0
 */
public class DialogueCapability {
    
    private static final Logger log = LoggerFactory.getLogger(DialogueCapability.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    
    private final Agent agent;
    private final DialogueHandlerRegistry handlerRegistry;
    
    private DefaultConversationManager conversationManager;
    private Subscription subscription;
    
    public DialogueCapability(Agent agent) {
        this.agent = agent;
        this.handlerRegistry = new DialogueHandlerRegistry();
    }
    
    /**
     * Initializes dialogue capabilities with the message dispatcher.
     * Should be called during agent startup (e.g., in onStart()).
     *
     * @param dispatcher the message dispatcher to use
     * @since 0.20.0
     */
    public void initialize(MessageDispatcher dispatcher) {
        this.conversationManager = new DefaultConversationManager(
            agent.getAgentId(),
            dispatcher
        );
        handlerRegistry.scan(agent);
        subscription = dispatcher.subscribeRecipient(
            agent.getAgentId(),
            MessageHandler.sync(this::handleIncomingMessage)
        );
        log.info("Dialogue capability initialized for agent {} with {} handlers",
            agent.getAgentId(), handlerRegistry.size());
    }

    /**
     * Shuts down dialogue capabilities, cancelling the recipient subscription.
     * Should be called during agent shutdown (e.g., in onStop()).
     *
     * @since 0.20.0
     */
    public void shutdown() {
        if (subscription != null) {
            subscription.unsubscribe();
            subscription = null;
        }
        log.debug("Dialogue capability shut down for agent {}", agent.getAgentId());
    }
    
    /**
     * Handles an incoming message, converting to DialogueMessage and dispatching.
     */
    private void handleIncomingMessage(Message message) {
        try {
            DialogueMessage dialogueMessage = DialogueMessage.fromMessage(message);
            
            // Let conversation manager handle state tracking
            conversationManager.handleIncoming(dialogueMessage);
            
            // Dispatch to registered handlers
            boolean handled = handlerRegistry.dispatch(dialogueMessage);
            
            if (!handled) {
                log.debug("No handler found for message: performative={}, protocol={}", 
                    dialogueMessage.performative(), dialogueMessage.protocol());
            }
        } catch (Exception e) {
            log.error("Error handling dialogue message: {}", e.getMessage(), e);
        }
    }
    
    // =========================================================================
    // HIGH-LEVEL API
    // =========================================================================
    
    /**
     * Sends a REQUEST to another agent and waits for response.
     */
    public CompletableFuture<DialogueMessage> request(String targetAgentId, Object content) {
        return request(targetAgentId, content, DEFAULT_TIMEOUT);
    }
    
    /**
     * Sends a REQUEST to another agent and waits for response.
     */
    public CompletableFuture<DialogueMessage> request(String targetAgentId, Object content, Duration timeout) {
        return conversationManager.request(targetAgentId, content, timeout);
    }
    
    /**
     * Sends a QUERY to another agent and waits for response.
     */
    public CompletableFuture<DialogueMessage> query(String targetAgentId, Object query) {
        return query(targetAgentId, query, DEFAULT_TIMEOUT);
    }
    
    /**
     * Sends a QUERY to another agent and waits for response.
     */
    public CompletableFuture<DialogueMessage> query(String targetAgentId, Object query, Duration timeout) {
        return conversationManager.query(targetAgentId, query, timeout);
    }
    
    /**
     * Initiates a call for proposals.
     */
    public CompletableFuture<List<DialogueMessage>> callForProposals(
            List<String> participants, Object taskSpec, Duration deadline) {
        return conversationManager.callForProposals(participants, taskSpec, deadline);
    }
    
    /**
     * Replies to a received message.
     */
    public CompletableFuture<Void> reply(DialogueMessage original, Performative performative, Object content) {
        return conversationManager.reply(original, performative, content);
    }
    
    /**
     * Convenience: reply with AGREE.
     */
    public CompletableFuture<Void> agree(DialogueMessage original) {
        return reply(original, Performative.AGREE, null);
    }
    
    /**
     * Convenience: reply with AGREE and content.
     */
    public CompletableFuture<Void> agree(DialogueMessage original, Object content) {
        return reply(original, Performative.AGREE, content);
    }
    
    /**
     * Convenience: reply with REFUSE.
     */
    public CompletableFuture<Void> refuse(DialogueMessage original, String reason) {
        return reply(original, Performative.REFUSE, reason);
    }
    
    /**
     * Convenience: reply with INFORM (result).
     */
    public CompletableFuture<Void> inform(DialogueMessage original, Object result) {
        return reply(original, Performative.INFORM, result);
    }
    
    /**
     * Convenience: reply with FAILURE.
     */
    public CompletableFuture<Void> failure(DialogueMessage original, String reason) {
        return reply(original, Performative.FAILURE, reason);
    }
    
    /**
     * Convenience: reply with PROPOSE.
     */
    public CompletableFuture<Void> propose(DialogueMessage cfp, Object proposal) {
        return reply(cfp, Performative.PROPOSE, proposal);
    }
    
    // =========================================================================
    // CONVERSATION ACCESS
    // =========================================================================
    
    /**
     * Gets a conversation by ID.
     */
    public Optional<Conversation> getConversation(String conversationId) {
        return conversationManager.getConversation(conversationId);
    }
    
    /**
     * Gets all active conversations.
     */
    public List<Conversation> getActiveConversations() {
        return conversationManager.getActiveConversations();
    }
    
    /**
     * Gets the underlying conversation manager.
     */
    public ConversationManager getConversationManager() {
        return conversationManager;
    }
    
    /**
     * Gets the commitment tracker.
     */
    public DefaultCommitmentTracker getCommitmentTracker() {
        return conversationManager.getCommitmentTracker();
    }
}