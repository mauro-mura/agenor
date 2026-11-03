package dev.agenor.autoconfigure;

import dev.agenor.runtime.AgenorRuntime;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Actuator {@link HealthIndicator} for the Agenor runtime.
 *
 * <p>Reports {@code UP} when the runtime is running and, on a transport with a broker, the broker
 * answers; {@code DOWN} otherwise. An unreachable broker has to count on its own, because the
 * agents do not notice it: they stay {@code RUNNING} while nothing reaches them.
 * Details exposed:
 * <ul>
 *   <li>{@code runtime.name} — configured runtime name</li>
 *   <li>{@code agents.total} — total registered agents</li>
 *   <li>{@code agents.running} — agents currently running</li>
 *   <li>{@code transport.type}, {@code transport.reachable} — only on a transport with a broker</li>
 * </ul>
 *
 * <p>Activated automatically when {@code spring-boot-starter-actuator} is on the classpath
 * and a {@link AgenorRuntime} bean is present. Registered via
 * {@link AgenorAutoConfiguration.ActuatorConfiguration}.
 */
public class AgenorHealthIndicator implements HealthIndicator {

    private final AgenorRuntime runtime;
    private final BrokerProbe broker;

    public AgenorHealthIndicator(AgenorRuntime runtime) {
        this(runtime, null);
    }

    AgenorHealthIndicator(AgenorRuntime runtime, BrokerProbe broker) {
        this.runtime = runtime;
        this.broker = broker;
    }

    @Override
    public Health health() {
        AgenorRuntime.RuntimeStats stats = runtime.getStats();
        boolean brokerAnswers = broker == null || broker.reachable().getAsBoolean();

        Health.Builder builder = runtime.isRunning() && brokerAnswers ? Health.up() : Health.down();

        builder.withDetail("runtime.name", runtime.getConfiguration().runtime().name())
                .withDetail("agents.total", stats.totalAgents())
                .withDetail("agents.running", stats.runningAgents());
        if (broker != null) {
            builder.withDetail("transport.type", broker.transportType())
                    .withDetail("transport.reachable", brokerAnswers);
        }
        return builder.build();
    }
}
