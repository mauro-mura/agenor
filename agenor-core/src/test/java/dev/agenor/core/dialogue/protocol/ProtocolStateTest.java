package dev.agenor.core.dialogue.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class ProtocolStateTest {

    @Test
    void shouldHave8States() {
        assertThat(ProtocolState.values()).hasSize(8);
    }

    @ParameterizedTest
    @EnumSource(value = ProtocolState.class, names = {"INITIATED", "AWAITING_RESPONSE", "AGREED"})
    void shouldIdentifyNonTerminalStates(ProtocolState state) {
        assertThat(state.isTerminal()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = ProtocolState.class, names = {"COMPLETED", "REFUSED", "FAILED", "CANCELLED", "TIMEOUT"})
    void shouldIdentifyTerminalStates(ProtocolState state) {
        assertThat(state.isTerminal()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = ProtocolState.class, names = {"COMPLETED", "AGREED"})
    void shouldIdentifySuccessStates(ProtocolState state) {
        assertThat(state.isSuccess()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = ProtocolState.class, names = {"INITIATED", "AWAITING_RESPONSE", "REFUSED", "FAILED", "CANCELLED", "TIMEOUT"})
    void shouldIdentifyNonSuccessStates(ProtocolState state) {
        assertThat(state.isSuccess()).isFalse();
    }
}
