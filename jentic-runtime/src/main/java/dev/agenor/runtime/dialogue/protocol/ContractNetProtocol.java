package dev.agenor.runtime.dialogue.protocol;

import dev.agenor.core.dialogue.Performative;
import dev.agenor.core.dialogue.protocol.Protocol;
import dev.agenor.core.dialogue.protocol.ProtocolState;

import java.util.Set;

import static dev.agenor.core.dialogue.Performative.*;
import static dev.agenor.core.dialogue.protocol.ProtocolState.*;

/**
 * Contract Net Protocol for task delegation and negotiation.
 *
 * <p>Flow:
 * <pre>{@code
 * Initiator                    Participants
 *     |                             |
 *     |-------- CFP --------------->| (broadcast)
 *     |                             |
 *     |<------- PROPOSE/REFUSE -----|
 *     |<------- PROPOSE/REFUSE -----|
 *     |                             |
 *     |-------- AGREE ------------->| (to winner)
 *     |-------- REFUSE ------------>| (to others)
 *     |                             |
 *     |<------- INFORM/FAILURE -----|
 *     |                             |
 * }</pre>
 *
 * @since 0.5.0
 */
public class ContractNetProtocol implements Protocol {

    public static final String ID = "contract-net";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Contract Net Protocol";
    }

    @Override
    public ProtocolState getInitialState() {
        return INITIATED;
    }

    @Override
    public ProtocolState nextState(ProtocolState current, Performative received, boolean isInitiator) {
        return switch (current) {
            case INITIATED -> received == CFP ? AWAITING_RESPONSE : current;
            case AWAITING_RESPONSE -> switch (received) {
                case PROPOSE, REFUSE -> AWAITING_RESPONSE; // collecting proposals
                case AGREE -> AGREED;
                case CANCEL -> CANCELLED;
                default -> current;
            };
            case AGREED -> switch (received) {
                case INFORM -> COMPLETED;
                case FAILURE -> FAILED;
                default -> current;
            };
            default -> current;
        };
    }

    @Override
    public Set<Performative> allowedPerformatives(ProtocolState state, boolean isInitiator) {
        if (isInitiator) {
            return switch (state) {
                case INITIATED -> Set.of(CFP);
                case AWAITING_RESPONSE -> Set.of(AGREE, REFUSE, CANCEL);
                default -> Set.of();
            };
        } else {
            return switch (state) {
                case AWAITING_RESPONSE -> Set.of(PROPOSE, REFUSE);
                case AGREED -> Set.of(INFORM, FAILURE);
                default -> Set.of();
            };
        }
    }
}
