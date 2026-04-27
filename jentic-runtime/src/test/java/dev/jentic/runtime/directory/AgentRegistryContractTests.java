package dev.jentic.runtime.directory;

import dev.jentic.core.AgentDescriptor;
import dev.jentic.core.AgentStatus;
import dev.jentic.core.directory.AgentDirectory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reusable contract tests for {@link dev.jentic.core.directory.AgentRegistry}.
 *
 * <p>Implement this interface in any test class that exercises an {@code AgentRegistry}
 * backend. The concrete class provides a fresh subject via {@link #createSubject()}.
 * Future adapters (JDBC, Consul …) extend the same contract without duplicating test logic.
 *
 * @since 0.20.0
 */
public interface AgentRegistryContractTests {

    /** Creates a fresh {@link AgentDirectory} backed by the implementation under test. */
    AgentDirectory createSubject();

    @Test
    @DisplayName("[Registry] register makes agent discoverable by id")
    default void register_makesAgentDiscoverable() {
        var dir = createSubject();
        dir.register(AgentDescriptor.builder("reg-1").agentName("A1").build()).join();

        assertThat(dir.findById("reg-1").join()).isPresent();
    }

    @Test
    @DisplayName("[Registry] re-registering same id updates the entry (upsert)")
    default void register_upserts() {
        var dir = createSubject();
        dir.register(AgentDescriptor.builder("reg-2").agentName("First").build()).join();
        dir.register(AgentDescriptor.builder("reg-2").agentName("Second").build()).join();

        var found = dir.findById("reg-2").join().orElseThrow();
        assertThat(found.agentName()).isEqualTo("Second");
    }

    @Test
    @DisplayName("[Registry] unregister removes agent; findById returns empty")
    default void unregister_removesAgent() {
        var dir = createSubject();
        dir.register(AgentDescriptor.builder("reg-3").agentName("A3").build()).join();
        dir.unregister("reg-3").join();

        assertThat(dir.findById("reg-3").join()).isEmpty();
    }

    @Test
    @DisplayName("[Registry] unregister of unknown id is a no-op")
    default void unregister_unknownIdIsNoOp() {
        createSubject().unregister("does-not-exist").join(); // must not throw
    }

    @Test
    @DisplayName("[Registry] updateStatus is visible via findById")
    default void updateStatus_persistsChange() {
        var dir = createSubject();
        dir.register(AgentDescriptor.builder("reg-4").agentName("A4")
                .status(AgentStatus.STARTING).build()).join();
        dir.updateStatus("reg-4", AgentStatus.RUNNING).join();

        var found = dir.findById("reg-4").join().orElseThrow();
        assertThat(found.status()).isEqualTo(AgentStatus.RUNNING);
    }
}
