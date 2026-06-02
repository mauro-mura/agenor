package dev.agenor.runtime.dialogue.protocol;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static dev.agenor.core.dialogue.Performative.*;
import static dev.agenor.core.dialogue.protocol.ProtocolState.*;
import static org.assertj.core.api.Assertions.assertThat;

class ContractNetProtocolTest {

    private ContractNetProtocol protocol;

    @BeforeEach
    void setUp() {
        protocol = new ContractNetProtocol();
    }

    @Test
    void shouldHaveCorrectId() {
        assertThat(protocol.getId()).isEqualTo("contract-net");
    }

    @Test
    void shouldTransitionToAwaitingOnCfp() {
        var next = protocol.nextState(INITIATED, CFP, true);
        assertThat(next).isEqualTo(AWAITING_RESPONSE);
    }

    @Test
    void shouldStayAwaitingOnPropose() {
        // Multiple proposals can come in
        var next = protocol.nextState(AWAITING_RESPONSE, PROPOSE, true);
        assertThat(next).isEqualTo(AWAITING_RESPONSE);
    }

    @Test
    void shouldStayAwaitingOnRefuse() {
        // Participants can refuse
        var next = protocol.nextState(AWAITING_RESPONSE, REFUSE, true);
        assertThat(next).isEqualTo(AWAITING_RESPONSE);
    }

    @Test
    void shouldTransitionToAgreedOnAgree() {
        var next = protocol.nextState(AWAITING_RESPONSE, AGREE, true);
        assertThat(next).isEqualTo(AGREED);
    }

    @Test
    void shouldTransitionToCompletedOnInform() {
        var next = protocol.nextState(AGREED, INFORM, true);
        assertThat(next).isEqualTo(COMPLETED);
    }

    @Test
    void shouldTransitionToFailedOnFailure() {
        var next = protocol.nextState(AGREED, FAILURE, true);
        assertThat(next).isEqualTo(FAILED);
    }

    @Test
    void shouldAllowCfpForInitiator() {
        var allowed = protocol.allowedPerformatives(INITIATED, true);
        assertThat(allowed).containsExactly(CFP);
    }

    @Test
    void shouldAllowAgreeRefuseCancelForInitiatorAwaiting() {
        var allowed = protocol.allowedPerformatives(AWAITING_RESPONSE, true);
        assertThat(allowed).containsExactlyInAnyOrder(AGREE, REFUSE, CANCEL);
    }

    @Test
    void shouldAllowProposeRefuseForParticipant() {
        var allowed = protocol.allowedPerformatives(AWAITING_RESPONSE, false);
        assertThat(allowed).containsExactlyInAnyOrder(PROPOSE, REFUSE);
    }

    @Test
    void shouldAllowInformFailureForAgreedParticipant() {
        var allowed = protocol.allowedPerformatives(AGREED, false);
        assertThat(allowed).containsExactlyInAnyOrder(INFORM, FAILURE);
    }
}
