package dev.jentic.core.messaging;

import dev.jentic.core.Message;

import java.util.concurrent.CompletableFuture;

/**
 * Capability for sending a message directly to a specific agent by its ID.
 *
 * <p>Implementations use {@link dev.jentic.core.directory.AgentResolver} to translate
 * the logical agent ID to a transport endpoint, then route the message accordingly.
 * If the agent cannot be resolved, the returned future completes exceptionally with
 * {@link dev.jentic.core.exceptions.AgentNotFoundException}.
 *
 * @since 0.20.0
 * @see DirectReceiver
 * @see MessageDispatcher
 */
public interface DirectMessenger {

    /**
     * Sends a message directly to the agent with the given ID.
     *
     * @param recipientAgentId the target agent's unique identifier, must not be null
     * @param msg              the message to send, must not be null
     * @return a future that completes when the message has been dispatched, or
     *         completes exceptionally with {@link dev.jentic.core.exceptions.AgentNotFoundException}
     *         if the agent is unknown
     * @throws NullPointerException if recipientAgentId or msg is null
     */
    CompletableFuture<Void> sendTo(String recipientAgentId, Message msg);
}
