package dev.agenor.runtime;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.agenor.core.AgentDescriptor;
import dev.agenor.core.AgentQuery;
import dev.agenor.core.Page;
import dev.agenor.core.PageRequest;
import dev.agenor.core.annotations.Agent;
import dev.agenor.core.directory.AgentDiscovery;
import dev.agenor.runtime.agent.BaseAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the agent summary {@code AgenorRuntime} logs once started: it reports what each agent
 * advertises in the directory, which is not always what its {@code @Agent} annotation says.
 */
@DisplayName("Agent summary at startup")
class AgentSummaryLogTest {

    private AgenorRuntime runtime;

    @AfterEach
    void stopRuntime() {
        if (runtime != null && runtime.isRunning()) {
            runtime.stop().join();
        }
    }

    @Test
    @DisplayName("capabilities declared at runtime are reported, not the annotation's")
    void runtimeCapabilities_reported() {
        runtime = AgenorRuntime.builder().build();
        runtime.registerAgent(new RuntimeCapabilitiesAgent("worker-1", Set.of("pdf-to-text", "ocr")));

        assertThat(summaryLine("worker-1"))
                .contains("Type: worker")
                .contains("Capabilities: [ocr, pdf-to-text]");
    }

    @Test
    @DisplayName("annotation capabilities are reported for an agent that declares nothing else")
    void annotationCapabilities_reported() {
        runtime = AgenorRuntime.builder().build();
        runtime.registerAgent(new AnnotatedAgent("annotated-1"));

        assertThat(summaryLine("annotated-1"))
                .contains("Type: annotated")
                .contains("Capabilities: [alpha, beta]");
    }

    @Test
    @DisplayName("a directory that cannot be read falls back to the annotation")
    void unreadableDirectory_fallsBackToAnnotation() {
        runtime = AgenorRuntime.builder().agentDiscovery(new FailingDiscovery()).build();
        runtime.registerAgent(new AnnotatedAgent("annotated-1"));

        assertThat(summaryLine("annotated-1"))
                .contains("Type: annotated")
                .contains("Capabilities: [alpha, beta]");
    }

    /** Starts the runtime and returns the summary line naming {@code agentId}. */
    private String summaryLine(String agentId) {
        var logger = (Logger) LoggerFactory.getLogger(AgenorRuntime.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            runtime.start().join();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        var lines = appender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(line -> line.startsWith("  - ") && line.contains("(" + agentId + ")"))
                .toList();
        assertThat(lines).hasSize(1);
        return lines.get(0);
    }

    /** Advertises capabilities it is given at construction, the way an application worker does. */
    @Agent(type = "worker")
    static class RuntimeCapabilitiesAgent extends BaseAgent {
        private final Set<String> capabilities;

        RuntimeCapabilitiesAgent(String id, Set<String> capabilities) {
            super(id, id);
            this.capabilities = capabilities;
        }

        @Override
        public void setAgentDescriptor(AgentDescriptor d) {
            super.setAgentDescriptor(new AgentDescriptor(d.agentId(), d.agentName(), d.agentType(),
                    d.status(), capabilities, d.metadata(), d.endpoint(), d.registeredAt(),
                    d.lastSeen()));
        }
    }

    @Agent(type = "annotated", capabilities = {"beta", "alpha"})
    static class AnnotatedAgent extends BaseAgent {
        AnnotatedAgent(String id) {
            super(id, id);
        }
    }

    static class FailingDiscovery implements AgentDiscovery {
        @Override
        public CompletableFuture<Optional<AgentDescriptor>> findById(String agentId) {
            return CompletableFuture.failedFuture(new IllegalStateException("directory down"));
        }

        @Override
        public CompletableFuture<List<AgentDescriptor>> findByCapability(String capability) {
            return CompletableFuture.failedFuture(new IllegalStateException("directory down"));
        }

        @Override
        public CompletableFuture<List<AgentDescriptor>> findByType(String agentType) {
            return CompletableFuture.failedFuture(new IllegalStateException("directory down"));
        }

        @Override
        public CompletableFuture<Page<AgentDescriptor>> findAgents(AgentQuery query,
                                                                   PageRequest request) {
            return CompletableFuture.failedFuture(new IllegalStateException("directory down"));
        }
    }
}
