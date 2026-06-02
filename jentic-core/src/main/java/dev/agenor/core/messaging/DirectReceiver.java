package dev.agenor.core.messaging;

import dev.agenor.core.MessageHandler;

/**
 * Capability for receiving direct (point-to-point) messages addressed to a local agent.
 *
 * @since 0.20.0
 * @see DirectMessenger
 * @see MessageDispatcher
 */
public interface DirectReceiver {

    /**
     * Registers a handler for all direct messages addressed to the given agent ID.
     *
     * @param localAgentId the agent ID to receive messages for, must not be null or empty
     * @param handler      the handler to invoke on each message, must not be null
     * @return a {@link Subscription} that can be used to cancel the subscription
     * @throws NullPointerException     if localAgentId or handler is null
     * @throws IllegalArgumentException if localAgentId is empty
     */
    Subscription subscribeRecipient(String localAgentId, MessageHandler handler);
}
