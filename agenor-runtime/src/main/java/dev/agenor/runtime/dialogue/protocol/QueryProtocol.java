package dev.agenor.runtime.dialogue.protocol;

import dev.agenor.core.dialogue.Performative;
import dev.agenor.core.dialogue.protocol.Protocol;
import dev.agenor.core.dialogue.protocol.ProtocolState;

import java.util.Set;

import static dev.agenor.core.dialogue.Performative.*;
import static dev.agenor.core.dialogue.protocol.ProtocolState.*;

/**
 * Query protocol for information retrieval.
 *
 * <p>Flow:
 * <pre>{@code
 * Initiator                    Participant
 *     |                             |
 *     |-------- QUERY ------------->|
 *     |                             |
 *     |<------- INFORM/REFUSE ------|
 *     |                             |
 * }</pre>
 *
 * @since 0.5.0
 */
public class QueryProtocol implements Protocol {

    public static final String ID = "query";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Query Protocol";
    }

    @Override
    public ProtocolState getInitialState() {
        return INITIATED;
    }

    @Override
    public ProtocolState nextState(ProtocolState current, Performative received, boolean isInitiator) {
        return switch (current) {
            case INITIATED -> received == QUERY ? AWAITING_RESPONSE : current;
            case AWAITING_RESPONSE -> switch (received) {
                case INFORM -> COMPLETED;
                case REFUSE -> REFUSED;
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
                case INITIATED -> Set.of(QUERY);
                case AWAITING_RESPONSE -> Set.of(CANCEL);
                default -> Set.of();
            };
        } else {
            return switch (state) {
                case AWAITING_RESPONSE -> Set.of(INFORM, REFUSE, FAILURE);
                default -> Set.of();
            };
        }
    }
}
