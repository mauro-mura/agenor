package dev.agenor.autoconfigure;

import dev.agenor.runtime.AgenorRuntime;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Actuator {@link HealthIndicator} for the Jentic runtime.
 *
 * <p>Reports {@code UP} when the runtime is running, {@code DOWN} otherwise.
 * Details exposed:
 * <ul>
 *   <li>{@code runtime.name} — configured runtime name</li>
 *   <li>{@code agents.total} — total registered agents</li>
 *   <li>{@code agents.running} — agents currently running</li>
 * </ul>
 *
 * <p>Activated automatically when {@code spring-boot-starter-actuator} is on the classpath
 * and a {@link AgenorRuntime} bean is present. Registered via
 * {@link AgenorAutoConfiguration.ActuatorConfiguration}.
 */
public class AgenorHealthIndicator implements HealthIndicator {

    private final AgenorRuntime runtime;

    public AgenorHealthIndicator(AgenorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Health health() {
        AgenorRuntime.RuntimeStats stats = runtime.getStats();

        Health.Builder builder = runtime.isRunning() ? Health.up() : Health.down();

        return builder
                .withDetail("runtime.name", runtime.getConfiguration().runtime().name())
                .withDetail("agents.total", stats.totalAgents())
                .withDetail("agents.running", stats.runningAgents())
                .build();
    }
}
