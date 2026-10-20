package dev.agenor.runtime.dialogue;

import dev.agenor.core.AgentEndpoint;
import dev.agenor.core.dialogue.ConversationManager;
import dev.agenor.core.dialogue.ConversationManagerSendFailureContractTests;
import dev.agenor.core.directory.AgentResolver;
import dev.agenor.runtime.messaging.InMemoryMessageDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Holds {@link DefaultConversationManager} over {@link InMemoryMessageDispatcher} to
 * {@link ConversationManagerSendFailureContractTests}. {@code RedisConversationManagerSendFailureIT}
 * in {@code agenor-examples} is the other implementor.
 *
 * <p>In memory there is no endpoint the transport cannot reach: an agent the directory knows but
 * that has no subscription is dead-lettered with the send reported as a success. So the
 * unreachable agent here is the one the resolver does not know, which is what in memory fails on.
 */
@DisplayName("DefaultConversationManager over InMemoryMessageDispatcher — send failures")
class InMemoryConversationManagerSendFailureTest implements ConversationManagerSendFailureContractTests {

    private static final AgentEndpoint LOCAL = new AgentEndpoint("node", "local", Map.of());
    private static final Map<String, AgentEndpoint> ENDPOINTS =
        Map.of("initiator", LOCAL, "responder", LOCAL);

    private DefaultConversationManager initiator;

    @BeforeEach
    void setUp() {
        AgentResolver resolver = agentId ->
            CompletableFuture.completedFuture(Optional.ofNullable(ENDPOINTS.get(agentId)));
        var dispatcher = new InMemoryMessageDispatcher(resolver);

        initiator = new DefaultConversationManager("initiator", dispatcher);
        var responder = new DefaultConversationManager("responder", dispatcher);
        dispatcher.subscribeRecipient("initiator",
            ConversationManagerSendFailureContractTests.initiatorInbox(initiator));
        dispatcher.subscribeRecipient("responder",
            ConversationManagerSendFailureContractTests.responderInbox(responder));
    }

    @Override
    public ConversationManager initiator() {
        return initiator;
    }

    @Override
    public String initiatorId() {
        return "initiator";
    }

    @Override
    public String responder() {
        return "responder";
    }

    @Override
    public String missingAgent() {
        return "ghost";
    }

    @Override
    public String unreachableAgent() {
        return missingAgent();
    }
}
