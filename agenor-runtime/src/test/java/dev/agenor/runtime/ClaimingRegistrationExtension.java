package dev.agenor.runtime;

import dev.agenor.core.Agent;
import dev.agenor.core.hitl.RequiresApproval;
import dev.agenor.core.spi.AgentRegistrationExtension;
import dev.agenor.core.spi.RegistrationContext;

import java.lang.annotation.Annotation;
import java.util.Set;

/**
 * Test-only {@link AgentRegistrationExtension} that claims {@code @RequiresApproval}, registered
 * via {@code src/test/resources/META-INF/services/dev.agenor.core.spi.AgentRegistrationExtension}.
 *
 * <p>{@code agenor-runtime} depends on {@code agenor-core} alone, so neither shipped extension is
 * on its test classpath. That leaves both diagnostic branches reachable without mocking the
 * {@code ServiceLoader}: {@code @RequiresApproval} is claimed by this class, while
 * {@code @WithGuardrails} is claimed by nothing and must be reported.
 *
 * <p>Public no-arg constructor is required by {@link java.util.ServiceLoader}.
 *
 * @see CountingRegistrationExtension the counterpart that does <em>not</em> override
 *      {@code handledAnnotations()}, covering extensions written before it existed
 */
public final class ClaimingRegistrationExtension implements AgentRegistrationExtension {

    public ClaimingRegistrationExtension() {
    }

    @Override
    public void onAgentRegistered(Agent agent, RegistrationContext context) {
        // Claiming the annotation is all this fixture does; the runtime never consults
        // handledAnnotations() to decide whether to call this method.
    }

    @Override
    public Set<Class<? extends Annotation>> handledAnnotations() {
        return Set.of(RequiresApproval.class);
    }
}
