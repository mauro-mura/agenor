package dev.agenor.runtime;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.agenor.core.guardrail.WithGuardrails;
import dev.agenor.core.hitl.RequiresApproval;
import dev.agenor.runtime.agent.BaseAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the optional-module diagnostics {@code AgenorRuntime} emits at startup (ADR-027).
 *
 * <p>The classpath does the setup work: {@code agenor-runtime} depends on {@code agenor-core}
 * alone, so neither shipped extension is present here. {@code @RequiresApproval} is claimed by
 * the test-registered {@link ClaimingRegistrationExtension} and {@code @WithGuardrails} by
 * nothing at all — which is exactly the "providing module is missing" situation the warning
 * exists for, reproduced without mocking the {@code ServiceLoader}.
 */
@DisplayName("Optional-module startup diagnostics")
class OptionalModuleDiagnosticsTest {

    private AgenorRuntime runtime;

    @AfterEach
    void stopRuntime() {
        if (runtime != null && runtime.isRunning()) {
            runtime.stop().join();
        }
    }

    @Test
    @DisplayName("an annotation no extension claims is reported once, naming the agent")
    void unhandledAnnotation_reported() {
        runtime = AgenorRuntime.builder().build();
        runtime.registerAgent(new GuardedAgent("guarded-1"));

        var warnings = captureStartupWarnings();

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .contains("@WithGuardrails")
                .contains("GuardedAgent")
                .contains("will have no effect");
    }

    @Test
    @DisplayName("an annotation an extension claims is not reported")
    void claimedAnnotation_notReported() {
        runtime = AgenorRuntime.builder().build();
        runtime.registerAgent(new ApprovalAgent("approval-1"));

        assertThat(captureStartupWarnings()).isEmpty();
    }

    @Test
    @DisplayName("two agents with the same unhandled annotation produce one aggregated warning")
    void unhandledAnnotation_aggregatedAcrossAgents() {
        runtime = AgenorRuntime.builder().build();
        runtime.registerAgent(new GuardedAgent("guarded-1"));
        runtime.registerAgent(new AlsoGuardedAgent("guarded-2"));

        var warnings = captureStartupWarnings();

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .contains("2 agent(s)")
                .contains("GuardedAgent")
                .contains("AlsoGuardedAgent");
    }

    @Test
    @DisplayName("an agent with no optional annotations starts without any warning")
    void noOptionalAnnotations_logClean() {
        runtime = AgenorRuntime.builder().build();
        runtime.registerAgent(new PlainAgent("plain-1"));

        assertThat(captureStartupWarnings()).isEmpty();
    }

    @Test
    @DisplayName("an extension that does not override handledAnnotations() is still invoked")
    void legacyExtension_stillInvokedAndClaimsNothing() {
        CountingRegistrationExtension.INVOCATION_COUNT.set(0);
        runtime = AgenorRuntime.builder().build();

        runtime.registerAgent(new PlainAgent("plain-1"));

        // The default empty set neither throws nor suppresses the invocation
        assertThat(CountingRegistrationExtension.INVOCATION_COUNT.get()).isEqualTo(1);
        assertThat(captureStartupWarnings()).isEmpty();
    }

    @Test
    @DisplayName("startup names the optional modules that resolved")
    void startup_reportsResolvedModules() {
        runtime = AgenorRuntime.builder().build();

        var infos = captureStartup(Level.INFO);

        assertThat(infos).anyMatch(line -> line.startsWith("Optional modules —"));
        // Nothing but the two test extensions is on this module's classpath
        assertThat(infos).anyMatch(line -> line.contains("agent discovery: absent"));
    }

    /** Starts the runtime and returns the WARN lines {@code AgenorRuntime} emitted while doing so. */
    private List<String> captureStartupWarnings() {
        return captureStartup(Level.WARN);
    }

    private List<String> captureStartup(Level level) {
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
        return appender.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @WithGuardrails
    static class GuardedAgent extends BaseAgent {
        GuardedAgent(String id) {
            super(id, id);
        }
    }

    @WithGuardrails
    static class AlsoGuardedAgent extends BaseAgent {
        AlsoGuardedAgent(String id) {
            super(id, id);
        }
    }

    @RequiresApproval
    static class ApprovalAgent extends BaseAgent {
        ApprovalAgent(String id) {
            super(id, id);
        }
    }

    static class PlainAgent extends BaseAgent {
        PlainAgent(String id) {
            super(id, id);
        }
    }
}
