package dev.agenor.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class JenticConfigurationTest {

    // =========================================================================
    // DEFAULTS
    // =========================================================================

    @Test
    @DisplayName("defaults() should build a valid configuration with all sub-configs")
    void defaultsShouldBuildValidConfiguration() {
        JenticConfiguration config = JenticConfiguration.defaults();

        assertThat(config).isNotNull();
        assertThat(config.runtime()).isNotNull();
        assertThat(config.agents()).isNotNull();
        assertThat(config.messaging()).isNotNull();
        assertThat(config.directory()).isNotNull();
        assertThat(config.scheduler()).isNotNull();
    }

    // =========================================================================
    // RUNTIME CONFIG
    // =========================================================================

    @Nested
    @DisplayName("RuntimeConfig")
    class RuntimeConfigTests {

        @Test
        @DisplayName("defaults() should set expected default values")
        void defaultsShouldSetExpectedValues() {
            JenticConfiguration.RuntimeConfig rc = JenticConfiguration.RuntimeConfig.defaults();

            assertThat(rc.name()).isEqualTo("agenor-runtime");
            assertThat(rc.environment()).isEqualTo("development");
            assertThat(rc.properties()).isEmpty();
        }

        @Test
        @DisplayName("null name should fall back to default")
        void nullNameShouldFallBack() {
            var rc = new JenticConfiguration.RuntimeConfig(null, null, null);

            assertThat(rc.name()).isEqualTo("agenor-runtime");
            assertThat(rc.environment()).isEqualTo("development");
        }

        @Test
        @DisplayName("explicit name and environment should be preserved")
        void explicitValuesShouldBePreserved() {
            var rc = new JenticConfiguration.RuntimeConfig("my-runtime", "production", null);

            assertThat(rc.name()).isEqualTo("my-runtime");
            assertThat(rc.environment()).isEqualTo("production");
        }

        @Test
        @DisplayName("properties map should be immutable")
        void propertiesMapShouldBeImmutable() {
            var rc = new JenticConfiguration.RuntimeConfig("n", "e", Map.of("k", "v"));

            assertThat(rc.properties()).containsEntry("k", "v");
            assertThatThrownBy(() -> rc.properties().put("x", "y"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // =========================================================================
    // AGENTS CONFIG – core merge logic
    // =========================================================================

    @Nested
    @DisplayName("AgentsConfig")
    class AgentsConfigTests {

        @Test
        @DisplayName("defaults() should enable auto-discovery with empty scan packages")
        void defaultsShouldEnableAutoDiscovery() {
            JenticConfiguration.AgentsConfig ac = JenticConfiguration.AgentsConfig.defaults();

            assertThat(ac.autoDiscovery()).isTrue();
            assertThat(ac.getAllScanPackages()).isEmpty();
        }

        @Test
        @DisplayName("basePackage alone should appear in getAllScanPackages")
        void basePackageShouldBeIncludedInScanPackages() {
            var ac = new JenticConfiguration.AgentsConfig(true, "com.example", null, null, null);

            assertThat(ac.getAllScanPackages()).containsExactly("com.example");
        }

        @Test
        @DisplayName("scanPackages list should be included in getAllScanPackages")
        void scanPackagesShouldBeIncluded() {
            var ac = new JenticConfiguration.AgentsConfig(true, null, null,
                    List.of("com.a", "com.b"), null);

            assertThat(ac.getAllScanPackages()).containsExactlyInAnyOrder("com.a", "com.b");
        }

        @Test
        @DisplayName("scanPaths should be merged into getAllScanPackages")
        void scanPathsShouldBeMerged() {
            var ac = new JenticConfiguration.AgentsConfig(true, null,
                    new String[]{"com.path1", "com.path2"}, null, null);

            assertThat(ac.getAllScanPackages()).containsExactlyInAnyOrder("com.path1", "com.path2");
        }

        @Test
        @DisplayName("all sources merged: basePackage + scanPackages + scanPaths")
        void allSourcesShouldBeMergedWithoutDuplicates() {
            var ac = new JenticConfiguration.AgentsConfig(
                    true,
                    "com.base",
                    new String[]{"com.path"},
                    List.of("com.pkg"),
                    null
            );

            assertThat(ac.getAllScanPackages())
                    .containsExactlyInAnyOrder("com.base", "com.path", "com.pkg");
        }

        @Test
        @DisplayName("null element in scanPackages should throw IllegalArgumentException")
        void nullElementInScanPackagesShouldThrow() {
            List<String> packagesWithNull = new java.util.ArrayList<>();
            packagesWithNull.add("com.valid");
            packagesWithNull.add(null);

            assertThatThrownBy(() -> new JenticConfiguration.AgentsConfig(
                    true, null, null, packagesWithNull, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("scanPackages[1] cannot be null");
        }

        @Test
        @DisplayName("empty basePackage (blank) should not be added to scan packages")
        void blankBasePackageShouldBeIgnored() {
            var ac = new JenticConfiguration.AgentsConfig(true, "   ", null, null, null);

            assertThat(ac.getAllScanPackages()).isEmpty();
        }

        @Test
        @DisplayName("resulting scanPackages list should be immutable")
        void scanPackagesListShouldBeImmutable() {
            var ac = new JenticConfiguration.AgentsConfig(true, "com.base", null, null, null);

            assertThatThrownBy(() -> ac.getAllScanPackages().add("com.extra"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("properties map should be immutable")
        void propertiesMapShouldBeImmutable() {
            var ac = new JenticConfiguration.AgentsConfig(true, null, null, null, Map.of("k", "v"));

            assertThatThrownBy(() -> ac.properties().put("x", "y"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // =========================================================================
    // MESSAGING CONFIG
    // =========================================================================

    @Nested
    @DisplayName("MessagingConfig")
    class MessagingConfigTests {

        @Test
        @DisplayName("defaults() should set inmemory provider")
        void defaultsShouldSetInmemoryProvider() {
            var mc = JenticConfiguration.MessagingConfig.defaults();

            assertThat(mc.provider()).isEqualTo("inmemory");
            assertThat(mc.properties()).isEmpty();
        }

        @Test
        @DisplayName("null properties should default to empty map")
        void nullPropertiesShouldDefaultToEmpty() {
            var mc = new JenticConfiguration.MessagingConfig("kafka", null);

            assertThat(mc.properties()).isEmpty();
        }
    }

    // =========================================================================
    // DIRECTORY CONFIG
    // =========================================================================

    @Nested
    @DisplayName("DirectoryConfig")
    class DirectoryConfigTests {

        @Test
        @DisplayName("defaults() should set local provider")
        void defaultsShouldSetLocalProvider() {
            var dc = JenticConfiguration.DirectoryConfig.defaults();

            assertThat(dc.provider()).isEqualTo("local");
        }
    }

    // =========================================================================
    // SCHEDULER CONFIG
    // =========================================================================

    @Nested
    @DisplayName("SchedulerConfig")
    class SchedulerConfigTests {

        @Test
        @DisplayName("defaults() should configure simple scheduler with 10 threads")
        void defaultsShouldConfigureSimpleScheduler() {
            var sc = JenticConfiguration.SchedulerConfig.defaults();

            assertThat(sc.provider()).isEqualTo("simple");
            assertThat(sc.threadPoolSize()).isEqualTo(10);
            assertThat(sc.properties()).isEmpty();
        }
    }

    // =========================================================================
    // NULL SUB-CONFIG FALLBACK IN MAIN CONSTRUCTOR
    // =========================================================================

    @Test
    @DisplayName("null sub-configs in main constructor should be replaced by defaults")
    void nullSubConfigsShouldFallBackToDefaults() {
        var config = new JenticConfiguration(null, null, null, null, null);

        assertThat(config.runtime().name()).isEqualTo("agenor-runtime");
        assertThat(config.agents().autoDiscovery()).isTrue();
        assertThat(config.messaging().provider()).isEqualTo("inmemory");
        assertThat(config.directory().provider()).isEqualTo("local");
        assertThat(config.scheduler().provider()).isEqualTo("simple");
    }
}
