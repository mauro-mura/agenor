package dev.jentic.adapters.a2a;

import dev.jentic.core.AgentDirectory;
import dev.jentic.core.Message;
import dev.jentic.core.messaging.MessageDispatcher;
import dev.jentic.core.dialogue.DialogueMessage;
import io.a2a.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main A2A adapter coordinating internal and external agent communication.
 * 
 * <p>Features:
 * <ul>
 *   <li>Auto-routing: internal agents via MessageDispatcher, external via A2A HTTP</li>
 *   <li>Agent card caching for external agents</li>
 *   <li>Streaming support for long-running tasks</li>
 * </ul>
 * 
 * <p>Usage:
 * <pre>{@code
 * // Create adapter
 * var adapter = new JenticA2AAdapter(
 *     messageDispatcher,
 *     agentDirectory,
 *     "my-agent",
 *     Duration.ofMinutes(5)
 * );
 * 
 * // Send to internal agent (auto-detected)
 * adapter.send(DialogueMessage.builder()
 *     .receiverId("internal-agent")
 *     .performative(Performative.REQUEST)
 *     .content(data)
 *     .build());
 * 
 * // Send to external A2A agent (URL)
 * adapter.send(DialogueMessage.builder()
 *     .receiverId("https://external-agent.com")
 *     .performative(Performative.QUERY)
 *     .content("question")
 *     .build());
 * }</pre>
 * 
 * @since 0.5.0
 */
public class JenticA2AAdapter {
    
    private static final Logger log = LoggerFactory.getLogger(JenticA2AAdapter.class);
    
    private final MessageDispatcher messageDispatcher;
    private final AgentDirectory agentDirectory;
    private final JenticA2AClient externalClient;
    private final String localAgentId;
    private final Duration timeout;

    // Cache for external agent cards
    private final Map<String, CachedAgentCard> agentCardCache = new ConcurrentHashMap<>();
    private final Duration cacheExpiry = Duration.ofMinutes(10);

    public JenticA2AAdapter(
            MessageDispatcher messageDispatcher,
            AgentDirectory agentDirectory,
            String localAgentId,
            Duration timeout) {
        this.messageDispatcher = messageDispatcher;
        this.agentDirectory = agentDirectory;
        this.externalClient = new JenticA2AClient(timeout);
        this.localAgentId = localAgentId;
        this.timeout = timeout;
    }
    
    /**
     * Sends a message, auto-routing to internal or external agent.
     * 
     * @param message the DialogueMessage to send
     * @return CompletableFuture with the response
     */
    public CompletableFuture<DialogueMessage> send(DialogueMessage message) {
        String targetId = message.receiverId();
        
        // Check if internal agent
        if (isInternalAgent(targetId)) {
            log.debug("Routing to internal agent: {}", targetId);
            return sendInternal(message);
        }
        
        // Check if external A2A agent (URL format)
        if (isExternalA2AUrl(targetId)) {
            log.debug("Routing to external A2A agent: {}", targetId);
            return sendExternal(targetId, message);
        }
        
        // Unknown target
        return CompletableFuture.failedFuture(
            new IllegalArgumentException("Unknown agent: " + targetId + 
                ". Must be registered internally or be an A2A URL (http/https)")
        );
    }
    
    /**
     * Sends a message to an internal agent via MessageDispatcher.
     */
    public CompletableFuture<DialogueMessage> sendInternal(DialogueMessage message) {
        CompletableFuture<Message> replyFuture = new CompletableFuture<>();
        String replyTo = message.senderId() != null ? message.senderId() : localAgentId;
        var subscription = messageDispatcher.subscribeRecipient(replyTo,
                msg -> { replyFuture.complete(msg); return CompletableFuture.completedFuture(null); });
        messageDispatcher.sendTo(message.receiverId(), message.toMessage());
        return replyFuture
                .orTimeout(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .whenComplete((r, ex) -> subscription.unsubscribe())
                .thenApply(DialogueMessage::fromMessage);
    }
    
    /**
     * Sends a message to an external A2A agent.
     */
    public CompletableFuture<DialogueMessage> sendExternal(String agentUrl, DialogueMessage message) {
        return externalClient.send(agentUrl, message, localAgentId);
    }
    
    /**
     * Sends with streaming for long-running tasks.
     */
    public CompletableFuture<DialogueMessage> sendWithStreaming(
            DialogueMessage message,
            JenticA2AClient.StatusCallback statusCallback) {
        
        String targetId = message.receiverId();
        
        if (!isExternalA2AUrl(targetId)) {
            // Internal agents don't support streaming in this version
            return send(message);
        }
        
        return externalClient.sendWithStreaming(targetId, message, localAgentId, statusCallback);
    }
    
    /**
     * Gets the AgentCard for an external A2A agent (cached).
     */
    public CompletableFuture<AgentCard> getExternalAgentCard(String agentUrl) {
        CachedAgentCard cached = agentCardCache.get(agentUrl);
        
        if (cached != null && !cached.isExpired()) {
            return CompletableFuture.completedFuture(cached.card());
        }
        
        return externalClient.getAgentCard(agentUrl)
            .thenApply(card -> {
                agentCardCache.put(agentUrl, new CachedAgentCard(card, System.currentTimeMillis()));
                return card;
            });
    }
    
    /**
     * Checks if a target is an internal agent.
     */
    public boolean isInternalAgent(String targetId) {
        if (targetId == null) return false;
        return agentDirectory.findById(targetId).join().isPresent();
    }
    
    /**
     * Checks if a target is an external A2A URL.
     */
    public boolean isExternalA2AUrl(String targetId) {
        if (targetId == null) return false;
        return targetId.startsWith("http://") || targetId.startsWith("https://");
    }
    
    /**
     * Validates connectivity to an external A2A agent.
     */
    public CompletableFuture<Boolean> validateExternalAgent(String agentUrl) {
        return externalClient.isA2AAgent(agentUrl);
    }
    
    /**
     * Clears the agent card cache.
     */
    public void clearCache() {
        agentCardCache.clear();
    }
    
    /**
     * Gets the local agent ID.
     */
    public String getLocalAgentId() {
        return localAgentId;
    }
    
    /**
     * Gets the underlying A2A client.
     */
    public JenticA2AClient getExternalClient() {
        return externalClient;
    }
    
    // Cache entry
    private record CachedAgentCard(AgentCard card, long cachedAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > Duration.ofMinutes(10).toMillis();
        }
    }
}