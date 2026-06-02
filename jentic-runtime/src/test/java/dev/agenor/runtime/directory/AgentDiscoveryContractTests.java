package dev.agenor.runtime.directory;

import dev.agenor.core.AgentDescriptor;
import dev.agenor.core.AgentQuery;
import dev.agenor.core.AgentStatus;
import dev.agenor.core.PageRequest;
import dev.agenor.core.directory.AgentDirectory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reusable contract tests for {@link dev.agenor.core.directory.AgentDiscovery}.
 *
 * @since 0.20.0
 */
public interface AgentDiscoveryContractTests {

    AgentDirectory createSubject();

    @Test
    @DisplayName("[Discovery] findById returns empty for unknown agent")
    default void findById_unknownReturnsEmpty() {
        assertThat(createSubject().findById("x").join()).isEmpty();
    }

    @Test
    @DisplayName("[Discovery] findByCapability returns only agents with that capability")
    default void findByCapability_returnsMatching() {
        var dir = createSubject();
        dir.register(AgentDescriptor.builder("d1").agentName("D1")
                .capabilities(Set.of("compute")).build()).join();
        dir.register(AgentDescriptor.builder("d2").agentName("D2")
                .capabilities(Set.of("storage")).build()).join();

        var result = dir.findByCapability("compute").join();
        assertThat(result).extracting(AgentDescriptor::agentId).containsExactly("d1");
    }

    @Test
    @DisplayName("[Discovery] findByCapability returns empty list when nothing matches")
    default void findByCapability_noMatch_empty() {
        assertThat(createSubject().findByCapability("nonexistent").join()).isEmpty();
    }

    @Test
    @DisplayName("[Discovery] findByType returns only agents with that type")
    default void findByType_returnsMatching() {
        var dir = createSubject();
        dir.register(AgentDescriptor.builder("d3").agentName("D3").agentType("worker").build()).join();
        dir.register(AgentDescriptor.builder("d4").agentName("D4").agentType("coordinator").build()).join();

        var result = dir.findByType("worker").join();
        assertThat(result).extracting(AgentDescriptor::agentId).containsExactly("d3");
    }

    @Test
    @DisplayName("[Discovery] findAgents(all) includes all registered agents")
    default void findAgents_allQuery_returnsAll() {
        var dir = createSubject();
        dir.register(AgentDescriptor.builder("d5").agentName("D5").build()).join();
        dir.register(AgentDescriptor.builder("d6").agentName("D6").build()).join();

        var page = dir.findAgents(AgentQuery.all(), PageRequest.first(100)).join();
        assertThat(page.content()).extracting(AgentDescriptor::agentId).contains("d5", "d6");
        assertThat(page.totalElements()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("[Discovery] findAgents filters by status")
    default void findAgents_filterByStatus() {
        var dir = createSubject();
        dir.register(AgentDescriptor.builder("d7").agentName("D7")
                .status(AgentStatus.RUNNING).build()).join();
        dir.register(AgentDescriptor.builder("d8").agentName("D8")
                .status(AgentStatus.STOPPED).build()).join();

        var q = AgentQuery.builder().status(AgentStatus.STOPPED).build();
        var page = dir.findAgents(q, PageRequest.first(10)).join();
        assertThat(page.content()).extracting(AgentDescriptor::agentId).containsExactly("d8");
    }

    @Test
    @DisplayName("[Discovery] findAgents returns empty page when nothing matches")
    default void findAgents_noMatch_emptyPage() {
        var dir = createSubject();
        var q = AgentQuery.builder().agentType("nonexistent-type").build();
        var page = dir.findAgents(q, PageRequest.first(10)).join();
        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
    }
}
