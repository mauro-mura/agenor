package dev.agenor.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@code ScanningMissingGuard}, the fail-fast bean that fires when
 * {@code agenor-runtime-scanning} is absent from the classpath — which the starter's
 * {@code pom.xml} declares as a mandatory (non-optional) dependency, so absence only
 * happens when a consumer adds an explicit {@code <exclusion>} (e.g. for GraalVM
 * native-image).
 *
 * <p>Simulating "module absent" for a {@code ServiceLoader}-based SPI needs more than
 * hiding the provider class: {@link FilteredClassLoader} filters classes and resources
 * independently, and the JDK's {@code ServiceLoader} throws
 * {@link java.util.ServiceConfigurationError} — not a clean empty result — when a
 * {@code META-INF/services} entry names a class it then cannot load. A faithful
 * "absent module" simulation has to hide both the class (so
 * {@code @ConditionalOnMissingClass} sees it as missing, matching what an exclusion
 * really does) and the {@code META-INF/services/dev.agenor.core.spi.AgentDiscoveryEngine}
 * resource (so {@code AgenorRuntime}'s own {@code ServiceLoader.load(...).findFirst()}
 * — called unconditionally in its constructor — resolves to an empty {@code Optional}
 * exactly as it would with the jar genuinely missing, instead of throwing an unrelated
 * {@code ServiceConfigurationError}).
 */
class AgenorScanningAutoConfigurationTest {

    private static final String DISCOVERY_ENGINE_CLASS =
            "dev.agenor.runtime.discovery.DefaultAgentDiscoveryEngine";
    private static final String DISCOVERY_ENGINE_SERVICE_FILE =
            "META-INF/services/dev.agenor.core.spi.AgentDiscoveryEngine";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgenorAutoConfiguration.class));

    private static FilteredClassLoader scanningModuleAbsent() {
        return new FilteredClassLoader(
                (String name) -> name.equals(DISCOVERY_ENGINE_CLASS),
                (String name) -> name.equals(DISCOVERY_ENGINE_SERVICE_FILE));
    }

    @Test
    void guardBeanAbsentWhenScanningIsOnTheClasspath() {
        runner.run(ctx ->
                assertThat(ctx.containsBean("AgenorScanningMissingGuard")).isFalse());
    }

    @Test
    void excludedWithoutDiscoveryConfigured_startsCleanly() {
        runner
            .withClassLoader(scanningModuleAbsent())
            .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void excludedWithBasePackageSet_failsWithActionableMessage() {
        runner
            .withClassLoader(scanningModuleAbsent())
            .withPropertyValues("agenor.agents.base-package=com.example.agents")
            .run(ctx -> assertThat(ctx).getFailure()
                    .hasRootCauseMessage("agenor.agents.base-package / scan-packages / "
                            + "scan-paths is set but 'agenor-runtime-scanning' was excluded "
                            + "from the classpath. Remove the exclusion, or add "
                            + "<dependency><groupId>dev.agenor</groupId>"
                            + "<artifactId>agenor-runtime-scanning</artifactId></dependency> "
                            + "to your pom.xml."));
    }

    @Test
    void excludedWithScanPackagesSet_failsWithActionableMessage() {
        runner
            .withClassLoader(scanningModuleAbsent())
            .withPropertyValues("agenor.agents.scan-packages[0]=com.example.agents")
            .run(ctx -> assertThat(ctx).hasFailed());
    }
}
