package dev.agenor.autoconfigure;

import dev.agenor.runtime.JenticRuntime;
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
 * and a {@link JenticRuntime} bean is present. Registered via
 * {@link JenticAutoConfiguration.ActuatorConfiguration}.
 */
public class JenticHealthIndicator implements HealthIndicator {

    private final JenticRuntime runtime;

    public JenticHealthIndicator(JenticRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Health health() {
        JenticRuntime.RuntimeStats stats = runtime.getStats();

        Health.Builder builder = runtime.isRunning() ? Health.up() : Health.down();

        return builder
                .withDetail("runtime.name", runtime.getConfiguration().runtime().name())
                .withDetail("agents.total", stats.totalAgents())
                .withDetail("agents.running", stats.runningAgents())
                .build();
    }
}
