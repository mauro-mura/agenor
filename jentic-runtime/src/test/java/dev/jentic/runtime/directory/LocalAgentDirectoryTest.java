package dev.jentic.runtime.directory;

import dev.jentic.core.AgentDescriptor;
import dev.jentic.core.AgentQuery;
import dev.jentic.core.AgentStatus;
import dev.jentic.core.Page;
import dev.jentic.core.PageRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Unit tests for LocalAgentDirectory
 */
class LocalAgentDirectoryTest {
    
    private LocalAgentDirectory directory;
    
    @BeforeEach
    void setUp() {
        directory = new LocalAgentDirectory();
    }
    
    @Test
    void shouldRegisterAndFindAgent() {
        // Given
        AgentDescriptor descriptor = AgentDescriptor.builder("agent-1")
            .agentName("Test Agent")
            .agentType("test")
            .status(AgentStatus.RUNNING)
            .build();
        
        // When
        directory.register(descriptor).join();
        Optional<AgentDescriptor> found = directory.findById("agent-1").join();
        
        // Then
        assertThat(found).isPresent();
        assertThat(found.get()).isEqualTo(descriptor);
    }
    
    @Test
    void shouldUnregisterAgent() {
        // Given
        AgentDescriptor descriptor = AgentDescriptor.builder("agent-1")
            .agentName("Test Agent")
            .build();
        
        directory.register(descriptor).join();
        
        // When
        directory.unregister("agent-1").join();
        Optional<AgentDescriptor> found = directory.findById("agent-1").join();
        
        // Then
        assertThat(found).isEmpty();
    }
    
    @Test
    void shouldListAllAgents() {
        // Given
        AgentDescriptor agent1 = AgentDescriptor.builder("agent-1")
            .agentName("Agent 1")
            .build();
        AgentDescriptor agent2 = AgentDescriptor.builder("agent-2")
            .agentName("Agent 2")
            .build();
        
        directory.register(agent1).join();
        directory.register(agent2).join();
        
        // When
        List<AgentDescriptor> all = directory.findAgents(AgentQuery.builder().build(), PageRequest.first(Integer.MAX_VALUE)).join().content();
        
        // Then
        assertThat(all).hasSize(2)
                      .containsExactlyInAnyOrder(agent1, agent2);
    }
    
    @Test
    void shouldFindAgentsByType() {
        // Given
        AgentDescriptor monitor1 = AgentDescriptor.builder("monitor-1")
            .agentType("monitor")
            .build();
        AgentDescriptor monitor2 = AgentDescriptor.builder("monitor-2")
            .agentType("monitor")
            .build();
        AgentDescriptor processor = AgentDescriptor.builder("processor-1")
            .agentType("processor")
            .build();
        
        directory.register(monitor1).join();
        directory.register(monitor2).join();
        directory.register(processor).join();
        
        // When
        AgentQuery query = AgentQuery.byType("monitor");
        List<AgentDescriptor> monitors = directory.findAgents(query, PageRequest.first(Integer.MAX_VALUE)).join().content();
        
        // Then
        assertThat(monitors).hasSize(2)
                           .containsExactlyInAnyOrder(monitor1, monitor2);
    }
    
    @Test
    void shouldFindAgentsByStatus() {
        // Given
        AgentDescriptor running = AgentDescriptor.builder("running-1")
            .status(AgentStatus.RUNNING)
            .build();
        AgentDescriptor stopped = AgentDescriptor.builder("stopped-1")
            .status(AgentStatus.STOPPED)
            .build();
        
        directory.register(running).join();
        directory.register(stopped).join();
        
        // When
        AgentQuery query = AgentQuery.byStatus(AgentStatus.RUNNING);
        List<AgentDescriptor> runningAgents = directory.findAgents(query, PageRequest.first(Integer.MAX_VALUE)).join().content();
        
        // Then
        assertThat(runningAgents).hasSize(1)
                                 .containsExactly(running);
    }
    
    @Test
    void shouldFindAgentsByCapabilities() {
        // Given
        AgentDescriptor agent1 = AgentDescriptor.builder("agent-1")
            .capabilities(Set.of("monitoring", "alerts"))
            .build();
        AgentDescriptor agent2 = AgentDescriptor.builder("agent-2")
            .capabilities(Set.of("processing", "alerts"))
            .build();
        AgentDescriptor agent3 = AgentDescriptor.builder("agent-3")
            .capabilities(Set.of("monitoring", "reporting"))
            .build();
        
        directory.register(agent1).join();
        directory.register(agent2).join();
        directory.register(agent3).join();
        
        // When
        AgentQuery query = AgentQuery.withCapabilities(Set.of("monitoring", "alerts"));
        List<AgentDescriptor> result = directory.findAgents(query, PageRequest.first(Integer.MAX_VALUE)).join().content();
        
        // Then
        assertThat(result).hasSize(1)
                         .containsExactly(agent1);
    }
    
    @Test
    void shouldFindAgentsWithComplexQuery() {
        // Given
        AgentDescriptor target = AgentDescriptor.builder("target")
            .agentName("Target Agent")
            .agentType("monitor")
            .status(AgentStatus.RUNNING)
            .capabilities(Set.of("monitoring", "alerts"))
            .build();
        
        AgentDescriptor nonMatch1 = AgentDescriptor.builder("non-match-1")
            .agentType("processor") // Wrong type
            .status(AgentStatus.RUNNING)
            .capabilities(Set.of("monitoring", "alerts"))
            .build();
        
        AgentDescriptor nonMatch2 = AgentDescriptor.builder("non-match-2")
            .agentType("monitor")
            .status(AgentStatus.STOPPED) // Wrong status
            .capabilities(Set.of("monitoring", "alerts"))
            .build();
        
        directory.register(target).join();
        directory.register(nonMatch1).join();
        directory.register(nonMatch2).join();
        
        // When
        AgentQuery query = AgentQuery.builder()
            .agentType("monitor")
            .status(AgentStatus.RUNNING)
            .requiredCapabilities(Set.of("monitoring"))
            .customFilter(desc -> desc.agentName().contains("Target"))
            .build();
        
        List<AgentDescriptor> result = directory.findAgents(query, PageRequest.first(Integer.MAX_VALUE)).join().content();
        
        // Then
        assertThat(result).hasSize(1)
                         .containsExactly(target);
    }
    
    @Test
    void shouldUpdateAgentStatus() {
        // Given
        AgentDescriptor descriptor = AgentDescriptor.builder("agent-1")
            .agentName("Test Agent")
            .status(AgentStatus.STARTING)
            .build();
        
        directory.register(descriptor).join();
        
        // When
        directory.updateStatus("agent-1", AgentStatus.RUNNING).join();
        Optional<AgentDescriptor> updated = directory.findById("agent-1").join();
        
        // Then
        assertThat(updated).isPresent();
        assertThat(updated.get().status()).isEqualTo(AgentStatus.RUNNING);
        assertThat(updated.get().lastSeen()).isAfterOrEqualTo(descriptor.lastSeen());
    }
    
    @Test
    void shouldHandleNonExistentAgent() {
        // When
        Optional<AgentDescriptor> notFound = directory.findById("non-existent").join();
        
        // Then
        assertThat(notFound).isEmpty();
    }
    
    @Test
    void shouldIgnoreUpdateForNonExistentAgent() {
        // When/Then - Should not throw exception
        assertThatCode(() -> directory.updateStatus("non-existent", AgentStatus.RUNNING).join())
            .doesNotThrowAnyException();
    }
}