package dev.jentic.autoconfigure;

import dev.jentic.core.JenticConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
            assertThat(props.runtime().properties()).isEmpty();
            assertThat(props.agents().autoDiscovery()).isTrue();
            assertThat(props.agents().basePackage()).isNull();
            assertThat(props.agents().scanPackages()).isEmpty();
            assertThat(props.agents().scanPaths()).isEmpty();
            assertThat(props.scheduler().provider()).isEqualTo("simple");
            assertThat(props.scheduler().threadPoolSize()).isEqualTo(10);
            assertThat(props.messaging().provider()).isEqualTo("inmemory");
            assertThat(props.messaging().redis()).isNull();
            assertThat(props.directory().provider()).isEqualTo("local");
            assertThat(props.directory().jdbc()).isNull();
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
                "jentic.scheduler.provider=simple",
                "jentic.messaging.provider=inmemory",
                "jentic.directory.provider=local",
                "jentic.llm.provider=openai",
                "jentic.llm.api-key=sk-test"
            )
            .run(ctx -> {
                JenticProperties props = ctx.getBean(JenticProperties.class);
                assertThat(props.runtime().name()).isEqualTo("my-system");
                assertThat(props.agents().autoDiscovery()).isFalse();
                assertThat(props.agents().basePackage()).isEqualTo("com.example");
                assertThat(props.scheduler().threadPoolSize()).isEqualTo(20);
                assertThat(props.messaging().provider()).isEqualTo("inmemory");
                assertThat(props.directory().provider()).isEqualTo("local");
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
                JenticConfiguration config = ctx.getBean(JenticProperties.class)
                        .toJenticConfiguration();
                List<String> all = config.agents().getAllScanPackages();
                assertThat(all).containsExactlyInAnyOrder(
                        "com.example", "com.example.agents", "com.example.tasks");
            });
    }

    @Test
    void scanPathsLegacyAliasIsMerged() {
        runner
            .withPropertyValues(
                "jentic.agents.scan-paths[0]=com.legacy.agents"
            )
            .run(ctx -> {
                JenticConfiguration config = ctx.getBean(JenticProperties.class)
                        .toJenticConfiguration();
                assertThat(config.agents().getAllScanPackages())
                        .contains("com.legacy.agents");
            });
    }

    // --- messaging and directory ---

    @Test
    void toJenticConfigurationMapsMessagingAndDirectoryCorrectly() {
        runner
            .withPropertyValues(
                "jentic.messaging.provider=inmemory",
                "jentic.directory.provider=local"
            )
            .run(ctx -> {
                JenticConfiguration config = ctx.getBean(JenticProperties.class)
                        .toJenticConfiguration();
                assertThat(config.messaging().provider()).isEqualTo("inmemory");
                assertThat(config.directory().provider()).isEqualTo("local");
            });
    }

    // --- scheduler provider ---

    @Test
    void toJenticConfigurationMapsSchedulerProviderCorrectly() {
        runner
            .withPropertyValues(
                "jentic.scheduler.provider=simple",
                "jentic.scheduler.thread-pool-size=16"
            )
            .run(ctx -> {
                JenticConfiguration config = ctx.getBean(JenticProperties.class)
                        .toJenticConfiguration();
                assertThat(config.scheduler().provider()).isEqualTo("simple");
                assertThat(config.scheduler().threadPoolSize()).isEqualTo(16);
            });
    }

    // --- runtime properties map ---

    @Test
    void runtimePropertiesMapIsMapped() {
        runner
            .withPropertyValues(
                "jentic.runtime.properties.custom-key=custom-value"
            )
            .run(ctx -> {
                JenticConfiguration config = ctx.getBean(JenticProperties.class)
                        .toJenticConfiguration();
                assertThat(config.runtime().properties())
                        .containsEntry("custom-key", "custom-value");
            });
    }

    // --- support config ---

    @EnableConfigurationProperties(JenticProperties.class)
    static class PropertiesTestConfig {}
}