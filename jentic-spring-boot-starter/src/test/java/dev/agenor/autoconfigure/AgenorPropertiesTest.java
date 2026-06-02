package dev.agenor.autoconfigure;

import dev.agenor.core.AgenorConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgenorPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesTestConfig.class);

    // --- defaults ---

    @Test
    void defaultsAreAppliedWhenNoPropertiesSet() {
        runner.run(ctx -> {
            AgenorProperties props = ctx.getBean(AgenorProperties.class);
            assertThat(props.runtime().name()).isEqualTo("agenor-runtime");
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
                "agenor.runtime.name=my-system",
                "agenor.agents.auto-discovery=false",
                "agenor.agents.base-package=com.example",
                "agenor.scheduler.thread-pool-size=20",
                "agenor.scheduler.provider=simple",
                "agenor.messaging.provider=inmemory",
                "agenor.directory.provider=local",
                "agenor.llm.provider=openai",
                "agenor.llm.api-key=sk-test"
            )
            .run(ctx -> {
                AgenorProperties props = ctx.getBean(AgenorProperties.class);
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
    void basePackageAndScanPackagesAreMergedInAgenorConfiguration() {
        runner
            .withPropertyValues(
                "agenor.agents.base-package=com.example",
                "agenor.agents.scan-packages[0]=com.example.agents",
                "agenor.agents.scan-packages[1]=com.example.tasks"
            )
            .run(ctx -> {
                AgenorConfiguration config = ctx.getBean(AgenorProperties.class)
                        .toAgenorConfiguration();
                List<String> all = config.agents().getAllScanPackages();
                assertThat(all).containsExactlyInAnyOrder(
                        "com.example", "com.example.agents", "com.example.tasks");
            });
    }

    @Test
    void scanPathsLegacyAliasIsMerged() {
        runner
            .withPropertyValues(
                "agenor.agents.scan-paths[0]=com.legacy.agents"
            )
            .run(ctx -> {
                AgenorConfiguration config = ctx.getBean(AgenorProperties.class)
                        .toAgenorConfiguration();
                assertThat(config.agents().getAllScanPackages())
                        .contains("com.legacy.agents");
            });
    }

    // --- messaging and directory ---

    @Test
    void toAgenorConfigurationMapsMessagingAndDirectoryCorrectly() {
        runner
            .withPropertyValues(
                "agenor.messaging.provider=inmemory",
                "agenor.directory.provider=local"
            )
            .run(ctx -> {
                AgenorConfiguration config = ctx.getBean(AgenorProperties.class)
                        .toAgenorConfiguration();
                assertThat(config.messaging().provider()).isEqualTo("inmemory");
                assertThat(config.directory().provider()).isEqualTo("local");
            });
    }

    // --- scheduler provider ---

    @Test
    void toAgenorConfigurationMapsSchedulerProviderCorrectly() {
        runner
            .withPropertyValues(
                "agenor.scheduler.provider=simple",
                "agenor.scheduler.thread-pool-size=16"
            )
            .run(ctx -> {
                AgenorConfiguration config = ctx.getBean(AgenorProperties.class)
                        .toAgenorConfiguration();
                assertThat(config.scheduler().provider()).isEqualTo("simple");
                assertThat(config.scheduler().threadPoolSize()).isEqualTo(16);
            });
    }

    // --- runtime properties map ---

    @Test
    void runtimePropertiesMapIsMapped() {
        runner
            .withPropertyValues(
                "agenor.runtime.properties.custom-key=custom-value"
            )
            .run(ctx -> {
                AgenorConfiguration config = ctx.getBean(AgenorProperties.class)
                        .toAgenorConfiguration();
                assertThat(config.runtime().properties())
                        .containsEntry("custom-key", "custom-value");
            });
    }

    // --- support config ---

    @EnableConfigurationProperties(AgenorProperties.class)
    static class PropertiesTestConfig {}
}
