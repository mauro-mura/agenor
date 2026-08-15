package dev.agenor.runtime.dialogue.protocol;

import dev.agenor.core.dialogue.Performative;
import dev.agenor.core.dialogue.protocol.ProtocolState;
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
    void shouldAllowAgreeRefuseInformFailureForParticipant() {
        var allowed = protocol.allowedPerformatives(AWAITING_RESPONSE, false);
        assertThat(allowed).containsExactlyInAnyOrder(AGREE, REFUSE, INFORM, FAILURE);
    }

    @Test
    void shouldAllowEveryPerformativeItsOwnTransitionAccepts() {
        // The two halves of the FSM must agree: anything nextState() acts on has to be allowed
        // for at least one of the two roles (nextState itself ignores isInitiator), or enforcing
        // isValid() flags a legitimate exchange as a violation. This is what D6 was about.
        for (var performative : Performative.values()) {
            for (var state : new ProtocolState[] {INITIATED, AWAITING_RESPONSE, AGREED}) {
                var next = protocol.nextState(state, performative, false);
                if (next == state) {
                    continue; // not a transition: nothing to be consistent with
                }
                assertThat(protocol.isValid(state, performative, true)
                        || protocol.isValid(state, performative, false))
                    .as("%s in %s transitions to %s but neither role is allowed to send it",
                        performative, state, next)
                    .isTrue();
            }
        }
    }

    @Test
    void shouldAcceptAnImmediateFailureWithoutAPrecedingAgree() {
        // dialogue.failure(...) on a REQUEST that was never agreed to — see ADR-029 D6
        assertThat(protocol.isValid(AWAITING_RESPONSE, FAILURE, false)).isTrue();
        assertThat(protocol.nextState(AWAITING_RESPONSE, FAILURE, false)).isEqualTo(FAILED);
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
