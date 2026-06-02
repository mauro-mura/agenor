package dev.agenor.runtime.dialogue.protocol;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static dev.agenor.core.dialogue.Performative.*;
import static dev.agenor.core.dialogue.protocol.ProtocolState.*;
import static org.assertj.core.api.Assertions.assertThat;

class QueryProtocolTest {

    private QueryProtocol protocol;

    @BeforeEach
    void setUp() {
        protocol = new QueryProtocol();
    }

    @Test
    void shouldHaveCorrectId() {
        assertThat(protocol.getId()).isEqualTo("query");
    }

    @Test
    void shouldTransitionToAwaitingOnQuery() {
        var next = protocol.nextState(INITIATED, QUERY, true);
        assertThat(next).isEqualTo(AWAITING_RESPONSE);
    }

    @Test
    void shouldTransitionToCompletedOnInform() {
        var next = protocol.nextState(AWAITING_RESPONSE, INFORM, true);
        assertThat(next).isEqualTo(COMPLETED);
    }

    @Test
    void shouldTransitionToRefusedOnRefuse() {
        var next = protocol.nextState(AWAITING_RESPONSE, REFUSE, true);
        assertThat(next).isEqualTo(REFUSED);
    }

    @Test
    void shouldNotHaveAgreedState() {
        // Query protocol goes directly from AWAITING to COMPLETED
        var next = protocol.nextState(AWAITING_RESPONSE, AGREE, true);
        assertThat(next).isEqualTo(AWAITING_RESPONSE); // unchanged
    }

    @Test
    void shouldAllowQueryForInitiator() {
        var allowed = protocol.allowedPerformatives(INITIATED, true);
        assertThat(allowed).containsExactly(QUERY);
    }

    @Test
    void shouldAllowInformRefuseFailureForParticipant() {
        var allowed = protocol.allowedPerformatives(AWAITING_RESPONSE, false);
        assertThat(allowed).containsExactlyInAnyOrder(INFORM, REFUSE, FAILURE);
    }
}
