package dev.jentic.runtime.directory;

import dev.jentic.core.AgentDescriptor;
import dev.jentic.core.directory.AgentDirectory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reusable contract tests for {@link dev.jentic.core.directory.AgentResolver}.
 *
 * @since 0.20.0
 */
public interface AgentResolverContractTests {

    AgentDirectory createSubject();

    @Test
    @DisplayName("[Resolver] resolveEndpoint returns present for registered agent")
    default void resolveEndpoint_registeredAgent_present() {
        var dir = createSubject();
        dir.register(AgentDescriptor.builder("res-1").agentName("R1").build()).join();

        assertThat(dir.resolveEndpoint("res-1").join()).isPresent();
    }

    @Test
    @DisplayName("[Resolver] resolveEndpoint returns empty for unknown agent")
    default void resolveEndpoint_unknownAgent_empty() {
        assertThat(createSubject().resolveEndpoint("no-such-agent").join()).isEmpty();
    }

    @Test
    @DisplayName("[Resolver] resolveEndpoint returns empty after the agent is unregistered")
    default void resolveEndpoint_afterUnregister_empty() {
        var dir = createSubject();
        dir.register(AgentDescriptor.builder("res-2").agentName("R2").build()).join();
        dir.unregister("res-2").join();

        assertThat(dir.resolveEndpoint("res-2").join()).isEmpty();
    }
}
