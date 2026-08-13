package dev.agenor.runtime.dialogue;

import dev.agenor.core.dialogue.CommitmentTracker;
import dev.agenor.core.dialogue.ConversationManager;
import dev.agenor.core.messaging.MessageDispatcher;
import dev.agenor.runtime.dialogue.protocol.ProtocolRegistry;

/**
 * Creates the {@link ConversationManager} an agent's {@link DialogueCapability} runs on.
 *
 * <p>This is the injection seam for conversation state: {@code DialogueCapability} never
 * names a concrete manager, so an alternative implementation can be supplied without
 * forking it, in line with ADR-002 (Interface-First Architecture).
 *
 * <pre>{@code
 * DialogueCapability dialogue = DialogueCapability.builder(this)
 *     .conversationManagerFactory(MyConversationManager::new)
 *     .build();
 * }</pre>
 *
 * <p>The default is {@code DefaultConversationManager::new} — in-memory, node-local.
 *
 * @since 0.26.0
 */
@FunctionalInterface
public interface ConversationManagerFactory {

    /**
     * Creates a conversation manager for one agent.
     *
     * @param agentId           the ID of the agent that owns the conversations
     * @param dispatcher        the transport used to send dialogue messages
     * @param protocolRegistry  the protocols available to this agent's conversations
     * @param commitmentTracker the tracker recording the commitments made in them
     * @return a new conversation manager, never null
     */
    ConversationManager create(
        String agentId,
        MessageDispatcher dispatcher,
        ProtocolRegistry protocolRegistry,
        CommitmentTracker commitmentTracker
    );
}
