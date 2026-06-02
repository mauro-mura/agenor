package dev.agenor.core.dialogue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class CommitmentStateTest {

    @Test
    void shouldHave6States() {
        assertThat(CommitmentState.values()).hasSize(6);
    }

    @ParameterizedTest
    @EnumSource(value = CommitmentState.class, names = {"PENDING", "ACTIVE"})
    void shouldIdentifyNonTerminalStates(CommitmentState state) {
        assertThat(state.isTerminal()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = CommitmentState.class, names = {"FULFILLED", "VIOLATED", "CANCELLED", "RELEASED"})
    void shouldIdentifyTerminalStates(CommitmentState state) {
        assertThat(state.isTerminal()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = CommitmentState.class, names = {"FULFILLED", "RELEASED"})
    void shouldIdentifySuccessfulStates(CommitmentState state) {
        assertThat(state.isSuccessful()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = CommitmentState.class, names = {"PENDING", "ACTIVE", "VIOLATED", "CANCELLED"})
    void shouldIdentifyUnsuccessfulStates(CommitmentState state) {
        assertThat(state.isSuccessful()).isFalse();
    }
}
