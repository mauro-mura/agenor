package dev.jentic.autoconfigure;

import dev.jentic.core.JenticConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JenticPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesTestConfig.class);

    // --- defaults ---

    @Test
    void defaultsAreAppliedWhenNoPropertiesSet() {
        runner.run(ctx -> {
            JenticProperties props = ctx.getBean(JenticProperties.class);
            assertThat(props.runtime().name()).isEqualTo("jentic-runtime");
            assertThat(props.runtime().environment()).isEqualTo("development");
            assertThat(props.agents().autoDiscovery()).isTrue();
            assertThat(props.agents().basePackage()).isNull();
            assertThat(props.agents().scanPackages()).isEmpty();
            assertThat(props.scheduler().threadPoolSize()).isEqualTo(10);
            assertThat(props.llm().provider()).isEqualTo("none");
            assertThat(props.llm().apiKey()).isNull();
        });
    }

    // --- relaxed binding ---

    @Test
    void kebabCaseBindsCorrectly() {
        runner
            .withPropertyValues(
                "jentic.runtime.name=my-system",
                "jentic.agents.auto-discovery=false",
                "jentic.agents.base-package=com.example",
                "jentic.scheduler.thread-pool-size=20",
                "jentic.llm.provider=openai",
                "jentic.llm.api-key=sk-test"
            )
            .run(ctx -> {
                JenticProperties props = ctx.getBean(JenticProperties.class);
                assertThat(props.runtime().name()).isEqualTo("my-system");
                assertThat(props.agents().autoDiscovery()).isFalse();
                assertThat(props.agents().basePackage()).isEqualTo("com.example");
                assertThat(props.scheduler().threadPoolSize()).isEqualTo(20);
                assertThat(props.llm().provider()).isEqualTo("openai");
                assertThat(props.llm().apiKey()).isEqualTo("sk-test");
            });
    }

    // --- package merging ---

    @Test
    void basePackageAndScanPackagesAreMergedInJenticConfiguration() {
        runner
            .withPropertyValues(
                "jentic.agents.base-package=com.example",
                "jentic.agents.scan-packages[0]=com.example.agents",
                "jentic.agents.scan-packages[1]=com.example.tasks"
            )
            .run(ctx -> {
                JenticProperties props = ctx.getBean(JenticProperties.class);
                JenticConfiguration config = props.toJenticConfiguration();

                // AgentsConfig constructor merges scanPackages first, then basePackage
                List<String> allPackages = config.agents().getAllScanPackages();
                assertThat(allPackages)
                    .containsExactlyInAnyOrder("com.example", "com.example.agents", "com.example.tasks");
            });
    }

    @Test
    void onlyBasePackageProducesNonEmptyPackageList() {
        runner
            .withPropertyValues("jentic.agents.base-package=com.example")
            .run(ctx -> {
                JenticConfiguration config = ctx.getBean(JenticProperties.class).toJenticConfiguration();
                assertThat(config.agents().getAllScanPackages()).containsExactly("com.example");
            });
    }

    @Test
    void onlyScanPackagesProducesNonEmptyPackageList() {
        runner
            .withPropertyValues(
                "jentic.agents.scan-packages[0]=com.example.a",
                "jentic.agents.scan-packages[1]=com.example.b"
            )
            .run(ctx -> {
                JenticConfiguration config = ctx.getBean(JenticProperties.class).toJenticConfiguration();
                assertThat(config.agents().getAllScanPackages())
                    .containsExactlyInAnyOrder("com.example.a", "com.example.b");
            });
    }

    // --- toJenticConfiguration mapping ---

    @Test
    void toJenticConfigurationMapsRuntimeCorrectly() {
        runner
            .withPropertyValues(
                "jentic.runtime.name=prod-system",
                "jentic.runtime.environment=production"
            )
            .run(ctx -> {
                JenticConfiguration config = ctx.getBean(JenticProperties.class).toJenticConfiguration();
                assertThat(config.runtime().name()).isEqualTo("prod-system");
                assertThat(config.runtime().environment()).isEqualTo("production");
            });
    }

    @Test
    void toJenticConfigurationMapsSchedulerCorrectly() {
        runner
            .withPropertyValues("jentic.scheduler.thread-pool-size=16")
            .run(ctx -> {
                JenticConfiguration config = ctx.getBean(JenticProperties.class).toJenticConfiguration();
                assertThat(config.scheduler().threadPoolSize()).isEqualTo(16);
            });
    }

    // --- test support config ---

    @EnableConfigurationProperties(JenticProperties.class)
    static class PropertiesTestConfig {}
}