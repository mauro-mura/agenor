package dev.agenor.examples.support.agents;

import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.examples.support.agents.CollaborativeRouterAgent.AgentConsultation;
import dev.agenor.examples.support.agents.CollaborativeRouterAgent.AgentContribution;
import dev.agenor.examples.support.model.SupportIntent;
import dev.agenor.examples.support.model.SupportResponse;
import dev.agenor.runtime.dialogue.DialogueCapability;

import java.util.List;

/**
 * Interface for agents that can participate in collaborative reasoning.
 *
 * <p>Agents implementing this interface can be consulted by the
 * CollaborativeRouterAgent to contribute to synthesized responses.
 *
 * <p>Usage:
 * <pre>
 * public class MyAgent extends BaseAgent implements ConsultableAgent {
 *
 *     {@literal @}DialogueHandler(performatives = QUERY)
 *     public void handleConsultation(DialogueMessage msg) {
 *         if (msg.content() instanceof AgentConsultation consultation) {
 *             AgentContribution contribution = consult(consultation);
 *             dialogue.reply(msg, Performative.INFORM, contribution);
 *         }
 *     }
 *
 *     {@literal @}Override
 *     public AgentContribution consult(AgentConsultation consultation) {
 *         // Evaluate query and return contribution
 *         return new AgentContribution(
 *             getAgentId(),
 *             "My response...",
 *             0.8,
 *             SupportIntent.FAQ,
 *             List.of("insight1", "insight2")
 *         );
 *     }
 * }
 * </pre>
 */
public interface ConsultableAgent {

    /**
     * Returns the agent's ID.
     */
    String getAgentId();

    /**
     * Evaluates a consultation request and returns a contribution.
     *
     * @param consultation the consultation request
     * @return contribution with response, confidence, and insights
     */
    AgentContribution consult(AgentConsultation consultation);

    /**
     * Returns the set of intents this agent specializes in.
     */
    default List<SupportIntent> getExpertise() {
        return List.of(SupportIntent.GENERAL);
    }

    /**
     * Returns true if this agent can handle the given intent.
     */
    default boolean canHandle(SupportIntent intent) {
        return getExpertise().contains(intent) ||
               getExpertise().contains(SupportIntent.GENERAL);
    }

    /**
     * Calculates confidence score for a query based on keyword matching.
     */
    default double calculateConfidence(String query, String... keywords) {
        String lower = query.toLowerCase();
        int matches = 0;
        for (String keyword : keywords) {
            if (lower.contains(keyword.toLowerCase())) {
                matches++;
            }
        }
        return Math.min(1.0, 0.3 + (matches * 0.2));
    }

    /**
     * Helper to create a contribution from a SupportResponse.
     */
    default AgentContribution toContribution(SupportResponse response, List<String> insights) {
        return new AgentContribution(
            getAgentId(),
            response.text(),
            response.confidence(),
            response.intent(),
            insights
        );
    }

    /**
     * Helper to create a low-confidence "can't help" contribution.
     */
    default AgentContribution cannotContribute(String reason) {
        return new AgentContribution(
            getAgentId(),
            reason,
            0.1,
            SupportIntent.GENERAL,
            List.of()
        );
    }

    /**
     * Helper to handle consultation in dialogue handler.
     *
     * @param msg the dialogue message
     * @param dialogue the dialogue capability
     */
    default void handleConsultationMessage(DialogueMessage msg, DialogueCapability dialogue) {
        if (msg.content() instanceof AgentConsultation consultation) {
            AgentContribution contribution = consult(consultation);
            dialogue.reply(msg, Performative.INFORM, contribution);
        } else {
            dialogue.reply(msg, Performative.REFUSE, "Expected AgentConsultation");
        }
    }
}
