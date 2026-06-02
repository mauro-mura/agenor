package dev.agenor.runtime.dialogue.protocol;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static dev.agenor.core.dialogue.Performative.*;
import static dev.agenor.core.dialogue.protocol.ProtocolState.*;
import static org.assertj.core.api.Assertions.assertThat;

class RequestProtocolTest {

    private RequestProtocol protocol;

    @BeforeEach
    void setUp() {
        protocol = new RequestProtocol();
    }

    @Test
    void shouldHaveCorrectId() {
        assertThat(protocol.getId()).isEqualTo("request");
    }

    @Test
    void shouldStartInInitiatedState() {
        assertThat(protocol.getInitialState()).isEqualTo(INITIATED);
    }

    @Test
    void shouldTransitionToAwaitingOnRequest() {
        var next = protocol.nextState(INITIATED, REQUEST, true);
        assertThat(next).isEqualTo(AWAITING_RESPONSE);
    }

    @Test
    void shouldTransitionToAgreedOnAgree() {
        var next = protocol.nextState(AWAITING_RESPONSE, AGREE, true);
        assertThat(next).isEqualTo(AGREED);
    }

    @Test
    void shouldTransitionToRefusedOnRefuse() {
        var next = protocol.nextState(AWAITING_RESPONSE, REFUSE, true);
        assertThat(next).isEqualTo(REFUSED);
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
    void shouldAllowDirectInformWithoutAgree() {
        var next = protocol.nextState(AWAITING_RESPONSE, INFORM, true);
        assertThat(next).isEqualTo(COMPLETED);
    }

    @Test
    void shouldAllowRequestForInitiator() {
        var allowed = protocol.allowedPerformatives(INITIATED, true);
        assertThat(allowed).containsExactly(REQUEST);
    }

    @Test
    void shouldAllowAgreeRefuseInformForParticipant() {
        var allowed = protocol.allowedPerformatives(AWAITING_RESPONSE, false);
        assertThat(allowed).containsExactlyInAnyOrder(AGREE, REFUSE, INFORM);
    }

    @Test
    void shouldValidateCorrectPerformative() {
        assertThat(protocol.isValid(INITIATED, REQUEST, true)).isTrue();
        assertThat(protocol.isValid(AWAITING_RESPONSE, AGREE, false)).isTrue();
    }

    @Test
    void shouldRejectInvalidPerformative() {
        assertThat(protocol.isValid(INITIATED, INFORM, true)).isFalse();
        assertThat(protocol.isValid(COMPLETED, REQUEST, true)).isFalse();
    }
}
