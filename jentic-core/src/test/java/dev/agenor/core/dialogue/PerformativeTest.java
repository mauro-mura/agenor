package dev.agenor.core.dialogue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class PerformativeTest {

    @Test
    void shouldHave10Performatives() {
        assertThat(Performative.values()).hasSize(10);
    }

    @ParameterizedTest
    @EnumSource(value = Performative.class, names = {"REQUEST", "PROPOSE", "CFP", "AGREE"})
    void shouldIdentifyCommitmentCreatingPerformatives(Performative performative) {
        assertThat(performative.createsCommitment()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = Performative.class, names = {"INFORM", "REFUSE", "FAILURE", "CANCEL"})
    void shouldIdentifyCommitmentDischargingPerformatives(Performative performative) {
        assertThat(performative.dischargesCommitment()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = Performative.class, names = {"REQUEST", "QUERY", "CFP", "PROPOSE"})
    void shouldIdentifyResponseExpectingPerformatives(Performative performative) {
        assertThat(performative.expectsResponse()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = Performative.class, names = {"INFORM", "NOTIFY", "AGREE", "REFUSE", "FAILURE", "CANCEL"})
    void shouldNotExpectResponseForNonInitiatingPerformatives(Performative performative) {
        assertThat(performative.expectsResponse()).isFalse();
    }
}
