package dev.agenor.runtime.config;

import dev.agenor.core.JenticConfiguration;
import dev.agenor.core.config.ConfigurationLoader;
import dev.agenor.core.config.ConfigurationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for ConfigurationLoader
 */
class ConfigurationLoaderTest {

    private ConfigurationLoader loader;

    @BeforeEach
    void setUp() {
        loader = new DefaultConfigurationLoader();
    }

    // =========================================================================
    // DEFAULT CONFIGURATION TESTS
    // =========================================================================

    @Test
    @DisplayName("Should create default configuration")
    void shouldCreateDefaultConfiguration() {
        JenticConfiguration config = JenticConfiguration.defaults();

        assertThat(config).isNotNull();
        assertThat(config.runtime().name()).isEqualTo("agenor-runtime");
        assertThat(config.runtime().environment()).isEqualTo("development");
        assertThat(config.agents().autoDiscovery()).isTrue();
        assertThat(config.agents().scanPackages()).isEmpty();
    }

    // =========================================================================
    // YAML FILE LOADING TESTS
    // =========================================================================

    @Test
    @DisplayName("Should load configuration from YAML file")
    void shouldLoadConfigurationFromYamlFile(@TempDir Path tempDir) throws Exception {
        // Create test YAML file
        String yamlContent = """
            agenor:
              runtime:
                name: "test-runtime"
                environment: "staging"
                properties:
                  key1: "value1"
                  key2: "value2"

              agents:
                autoDiscovery: true
                scanPackages:
                  - "com.example.agents"
                  - "com.example.workers"
                properties:
                  timeout: "30s"
            """;

        Path configFile = tempDir.resolve("test-config.yml");
        Files.writeString(configFile, yamlContent);

        // Load configuration
        JenticConfiguration config = loader.loadFromFile(configFile.toString());

        // Verify runtime config
        assertThat(config.runtime().name()).isEqualTo("test-runtime");
        assertThat(config.runtime().environment()).isEqualTo("staging");
        assertThat(config.runtime().properties())
            .containsEntry("key1", "value1")
            .containsEntry("key2", "value2");

        // Verify agents config
        assertThat(config.agents().autoDiscovery()).isTrue();
        assertThat(config.agents().scanPackages())
            .containsExactly("com.example.agents", "com.example.workers");
        assertThat(config.agents().properties())
            .containsEntry("timeout", "30s");
    }

    @Test
    @DisplayName("Should load minimal YAML configuration")
    void shouldLoadMinimalYamlConfiguration(@TempDir Path tempDir) throws Exception {
        String yamlContent = """
            agenor:
              runtime:
                name: "minimal-runtime"

              agents:
                scanPackages:
                  - "com.example"
            """;

        Path configFile = tempDir.resolve("minimal.yml");
        Files.writeString(configFile, yamlContent);

        JenticConfiguration config = loader.loadFromFile(configFile.toString());

        assertThat(config.runtime().name()).isEqualTo("minimal-runtime");
        assertThat(config.runtime().environment()).isEqualTo("development"); // Default
        assertThat(config.agents().scanPackages()).containsExactly("com.example");
    }

    // =========================================================================
    // JSON FILE LOADING TESTS
    // =========================================================================

    @Test
    @DisplayName("Should load configuration from JSON file")
    void shouldLoadConfigurationFromJsonFile(@TempDir Path tempDir) throws Exception {
        String jsonContent = """
            {
              "agenor": {
                "runtime": {
                  "name": "json-runtime",
                  "environment": "production"
                },
                "agents": {
                  "autoDiscovery": false,
                  "scanPackages": ["com.production.agents"]
                }
              }
            }
            """;

        Path configFile = tempDir.resolve("config.json");
        Files.writeString(configFile, jsonContent);

        JenticConfiguration config = loader.loadFromFile(configFile.toString());

        assertThat(config.runtime().name()).isEqualTo("json-runtime");
        assertThat(config.runtime().environment()).isEqualTo("production");
        assertThat(config.agents().autoDiscovery()).isFalse();
        assertThat(config.agents().scanPackages()).containsExactly("com.production.agents");
    }

    // =========================================================================
    // ENVIRONMENT VARIABLE SUBSTITUTION TESTS
    // =========================================================================

    @Test
    @DisplayName("Should substitute environment variables")
    void shouldSubstituteEnvironmentVariables(@TempDir Path tempDir) throws Exception {
        // Set system property for testing
        System.setProperty("TEST_RUNTIME_NAME", "env-runtime");
        System.setProperty("TEST_ENV", "test");

        try {
            String yamlContent = """
                agenor:
                  runtime:
                    name: "${TEST_RUNTIME_NAME}"
                    environment: "${TEST_ENV}"

                  agents:
                    scanPackages:
                      - "com.example"
                """;

            Path configFile = tempDir.resolve("env-config.yml");
            Files.writeString(configFile, yamlContent);

            JenticConfiguration config = loader.loadFromFile(configFile.toString());

            assertThat(config.runtime().name()).isEqualTo("env-runtime");
            assertThat(config.runtime().environment()).isEqualTo("test");

        } finally {
            System.clearProperty("TEST_RUNTIME_NAME");
            System.clearProperty("TEST_ENV");
        }
    }

    @Test
    @DisplayName("Should use default value when environment variable not found")
    void shouldUseDefaultValueWhenEnvVarNotFound(@TempDir Path tempDir) throws Exception {
        String yamlContent = """
            agenor:
              runtime:
                name: "${NONEXISTENT_VAR:default-runtime}"
                environment: "development"

              agents:
                scanPackages:
                  - "com.example"
            """;

        Path configFile = tempDir.resolve("default-env.yml");
        Files.writeString(configFile, yamlContent);

        JenticConfiguration config = loader.loadFromFile(configFile.toString());

        assertThat(config.runtime().name()).isEqualTo("default-runtime");
    }

    @Test
    @DisplayName("Should keep placeholder when env var not found and no default")
    void shouldKeepPlaceholderWhenNoDefault(@TempDir Path tempDir) throws Exception {
        String yamlContent = """
            agenor:
              runtime:
                name: "${MISSING_VAR}"
                environment: "development"

              agents:
                scanPackages:
                  - "com.example"
            """;

        Path configFile = tempDir.resolve("missing-env.yml");
        Files.writeString(configFile, yamlContent);

        JenticConfiguration config = loader.loadFromFile(configFile.toString());

        assertThat(config.runtime().name()).isEqualTo("${MISSING_VAR}");
    }

    // =========================================================================
    // CLASSPATH LOADING TESTS
    // =========================================================================

    @Test
    @DisplayName("Should load configuration from classpath")
    void shouldLoadConfigurationFromClasspath() throws Exception {
        // This test assumes a test resource exists
        // For now, we test the error case
        assertThatThrownBy(() -> loader.loadFromClasspath("nonexistent.yml"))
            .isInstanceOf(ConfigurationException.class)
            .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("Should handle leading slash in classpath resource")
    void shouldHandleLeadingSlashInClasspath() throws Exception {
        JenticConfiguration config = loader.loadFromClasspath("/jentic-test.yml");

        assertThat(config).isNotNull();
    }

    @Test
    @DisplayName("Should throw exception for non-existent classpath resource")
    void shouldThrowExceptionForNonExistentClasspathResource() {
        assertThatThrownBy(() -> loader.loadFromClasspath("nonexistent-resource.yml"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("Should throw exception for null classpath resource")
    void shouldThrowExceptionForNullClasspathResource() {
        assertThatThrownBy(() -> loader.loadFromClasspath(null))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("cannot be null");
    }

    @Test
    @DisplayName("Should throw exception for empty classpath resource")
    void shouldThrowExceptionForEmptyClasspathResource() {
        assertThatThrownBy(() -> loader.loadFromClasspath(""))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("cannot be null or empty");
    }

    // =========================================================================
    // STREAM LOADING TESTS
    // =========================================================================

    @Test
    @DisplayName("Should load configuration from input stream (YAML)")
    void shouldLoadConfigurationFromStreamYaml() throws Exception {
        String yamlContent = """
            agenor:
              runtime:
                name: "stream-runtime"
              agents:
                scanPackages:
                  - "com.stream"
            """;

        ByteArrayInputStream stream = new ByteArrayInputStream(yamlContent.getBytes());

        JenticConfiguration config = loader.loadFromStream(stream, "yaml");

        assertThat(config.runtime().name()).isEqualTo("stream-runtime");
        assertThat(config.agents().scanPackages()).containsExactly("com.stream");
    }

    @Test
    @DisplayName("Should load configuration from input stream (JSON)")
    void shouldLoadConfigurationFromStreamJson() throws Exception {
        String jsonContent = """
            {
              "agenor": {
                "runtime": {
                  "name": "stream-json-runtime"
                },
                "agents": {
                  "scanPackages": ["com.json.stream"]
                }
              }
            }
            """;

        ByteArrayInputStream stream = new ByteArrayInputStream(jsonContent.getBytes());

        JenticConfiguration config = loader.loadFromStream(stream, "json");

        assertThat(config.runtime().name()).isEqualTo("stream-json-runtime");
        assertThat(config.agents().scanPackages()).containsExactly("com.json.stream");
    }

    @Test
    @DisplayName("Should default to YAML when format is unknown")
    void shouldDefaultToYamlForUnknownFormat() throws Exception {
        String yamlContent = """
            agenor:
              runtime:
                name: "unknown-format"
            """;

        InputStream stream = new ByteArrayInputStream(yamlContent.getBytes(StandardCharsets.UTF_8));
        JenticConfiguration config = loader.loadFromStream(stream, "unknown");

        assertThat(config.runtime().name()).isEqualTo("unknown-format");
    }

    // =========================================================================
    // VALIDATION TESTS
    // =========================================================================

    @Test
    @DisplayName("Should validate valid configuration")
    void shouldValidateValidConfiguration() {
        JenticConfiguration config = new JenticConfiguration(
            new JenticConfiguration.RuntimeConfig("valid-runtime", "production", null),
            new JenticConfiguration.AgentsConfig(true, "com.example", null, null, null),
            null, null,null
        );

        assertThatCode(() -> loader.validate(config))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should reject null configuration")
    void shouldRejectNullConfiguration() {
        assertThatThrownBy(() -> loader.validate(null))
            .isInstanceOf(ConfigurationException.class)
            .hasMessageContaining("cannot be null");
    }

    @Test
    @DisplayName("Should reject empty runtime name")
    void shouldRejectEmptyRuntimeName() {
        JenticConfiguration config = new JenticConfiguration(
            new JenticConfiguration.RuntimeConfig("", "development", null),
            new JenticConfiguration.AgentsConfig(true, "com.example", null, null, null),
                null, null,null
        );

        assertThatThrownBy(() -> loader.validate(config))
            .isInstanceOf(ConfigurationException.class)
            .hasMessageContaining("runtime.name");
    }

    @Test
    @DisplayName("Should reject invalid package names")
    void shouldRejectInvalidPackageNames() {
        JenticConfiguration config = new JenticConfiguration(
            new JenticConfiguration.RuntimeConfig("runtime", "development", null),
            new JenticConfiguration.AgentsConfig(true, "invalid..package", null, null, null),
                null, null,null
        );

        assertThatThrownBy(() -> loader.validate(config))
            .isInstanceOf(ConfigurationException.class)
            .hasMessageContaining("Invalid package name");
    }

    @Test
    @DisplayName("Should reject null package names")
    void shouldRejectNullPackageNames() {
        List<String> packagesWithNull = new ArrayList<>();
        packagesWithNull.add("com.valid");
        packagesWithNull.add(null);

        // Il constructor stesso dovrebbe lanciare IllegalArgumentException
        assertThatThrownBy(() ->
                new JenticConfiguration.AgentsConfig(true, "", null , packagesWithNull, null)
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    @DisplayName("Should reject empty package names during validation")
    void shouldRejectEmptyPackageNames() {
        // Questo test verifica stringhe vuote, non null
        JenticConfiguration config = new JenticConfiguration(
                new JenticConfiguration.RuntimeConfig("runtime", "development", null),
                new JenticConfiguration.AgentsConfig(true, null, null, Arrays.asList("com.valid", ""), null),
                null, null,null
        );

        assertThatThrownBy(() -> loader.validate(config))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("null or empty");
    }

    // =========================================================================
    // ERROR HANDLING TESTS
    // =========================================================================

    @Test
    @DisplayName("Should throw exception for non-existent file")
    void shouldThrowExceptionForNonExistentFile() {
        assertThatThrownBy(() -> loader.loadFromFile("nonexistent.yml"))
            .isInstanceOf(ConfigurationException.class)
            .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("Should throw exception for null path")
    void shouldThrowExceptionForNullPath() {
        assertThatThrownBy(() -> loader.loadFromFile(null))
            .isInstanceOf(ConfigurationException.class)
            .hasMessageContaining("cannot be null");
    }

    @Test
    @DisplayName("Should throw exception for empty path")
    void shouldThrowExceptionForEmptyPath() {
        assertThatThrownBy(() -> loader.loadFromFile(""))
            .isInstanceOf(ConfigurationException.class)
            .hasMessageContaining("cannot be null or empty");
    }

    @Test
    @DisplayName("Should throw exception for malformed YAML")
    void shouldThrowExceptionForMalformedYaml(@TempDir Path tempDir) throws Exception {
        String malformedYaml = """
            agenor:
              runtime:
                name: "test
                environment: invalid
              invalid yaml structure
            """;

        Path configFile = tempDir.resolve("malformed.yml");
        Files.writeString(configFile, malformedYaml);

        assertThatThrownBy(() -> loader.loadFromFile(configFile.toString()))
            .isInstanceOf(ConfigurationException.class);
    }

    @Test
    @DisplayName("Should throw exception for null stream")
    void shouldThrowExceptionForNullStream() {
        assertThatThrownBy(() -> loader.loadFromStream(null, "yaml"))
            .isInstanceOf(ConfigurationException.class)
            .hasMessageContaining("cannot be null");
    }

    // =========================================================================
    // DEFAULT LOCATION TESTS
    // =========================================================================

    @Test
    @DisplayName("Should return defaults when no configuration file found")
    void shouldReturnDefaultsWhenNoFileFound() {
        JenticConfiguration config = loader.loadDefault();

        assertThat(config).isNotNull();
        assertThat(config.runtime().name()).isEqualTo("agenor-runtime");
        assertThat(config.runtime().environment()).isEqualTo("development");
    }

    // =========================================================================
    // EDGE CASES
    // =========================================================================

    @Test
    @DisplayName("Should handle empty YAML configuration")
    void shouldHandleEmptyYamlConfiguration(@TempDir Path tempDir) throws Exception {
        String emptyYaml = """
            agenor:
            """;

        Path configFile = tempDir.resolve("empty.yml");
        Files.writeString(configFile, emptyYaml);

        JenticConfiguration config = loader.loadFromFile(configFile.toString());

        // Should use defaults for missing sections
        assertThat(config.runtime()).isNotNull();
        assertThat(config.agents()).isNotNull();
    }

    @Test
    @DisplayName("Should handle configuration with only runtime section")
    void shouldHandlePartialConfiguration(@TempDir Path tempDir) throws Exception {
        String partialYaml = """
            agenor:
              runtime:
                name: "partial-runtime"
                environment: "staging"
            """;

        Path configFile = tempDir.resolve("partial.yml");
        Files.writeString(configFile, partialYaml);

        JenticConfiguration config = loader.loadFromFile(configFile.toString());

        assertThat(config.runtime().name()).isEqualTo("partial-runtime");
        assertThat(config.runtime().environment()).isEqualTo("staging");
        assertThat(config.agents()).isNotNull(); // Should have defaults
        assertThat(config.agents().autoDiscovery()).isTrue();
    }

    @Test
    @DisplayName("Should handle multiple environment variables")
    void shouldHandleMultipleEnvironmentVariables(@TempDir Path tempDir) throws Exception {
        System.setProperty("APP_NAME", "multi-env-app");
        System.setProperty("APP_ENV", "production");
        System.setProperty("BASE_PACKAGE", "com.production");

        try {
            String yamlContent = """
                agenor:
                  runtime:
                    name: "${APP_NAME}"
                    environment: "${APP_ENV}"
                  agents:
                    scanPackages:
                      - "${BASE_PACKAGE}.agents"
                      - "${BASE_PACKAGE}.workers"
                """;

            Path configFile = tempDir.resolve("multi-env.yml");
            Files.writeString(configFile, yamlContent);

            JenticConfiguration config = loader.loadFromFile(configFile.toString());

            assertThat(config.runtime().name()).isEqualTo("multi-env-app");
            assertThat(config.runtime().environment()).isEqualTo("production");
            assertThat(config.agents().getAllScanPackages())
                .containsExactly("com.production.agents", "com.production.workers");

        } finally {
            System.clearProperty("APP_NAME");
            System.clearProperty("APP_ENV");
            System.clearProperty("BASE_PACKAGE");
        }
    }

    @Test
    @DisplayName("Should detect file is not readable")
    void shouldDetectFileNotReadable(@TempDir Path tempDir) throws Exception {
        String yamlContent = """
            agenor:
              runtime:
                name: "test"
            """;

        Path configFile = tempDir.resolve("unreadable.yml");
        Files.writeString(configFile, yamlContent);

        try {
            Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("---------");
            Files.setPosixFilePermissions(configFile, permissions);

            assertThatThrownBy(() -> loader.loadFromFile(configFile.toString()))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("not readable");

        } catch (UnsupportedOperationException e) {
            // Skip on Windows
        } finally {
            try {
                Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-r--r--");
                Files.setPosixFilePermissions(configFile, permissions);
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    @DisplayName("Should resolve env var from System.getProperty when not in getenv")
    void shouldResolveFromSystemProperty(@TempDir Path tempDir) throws Exception {
        System.setProperty("PROP_ONLY_VAR", "property-value");

        try {
            String yamlContent = """
                agenor:
                  runtime:
                    name: "${PROP_ONLY_VAR}"
                """;

            Path configFile = tempDir.resolve("prop.yml");
            Files.writeString(configFile, yamlContent);

            JenticConfiguration config = loader.loadFromFile(configFile.toString());

            assertThat(config.runtime().name()).isEqualTo("property-value");

        } finally {
            System.clearProperty("PROP_ONLY_VAR");
        }
    }

    @Test
    @DisplayName("Should handle .yaml extension")
    void shouldHandleYamlExtension(@TempDir Path tempDir) throws Exception {
        String yamlContent = """
            agenor:
              runtime:
                name: "yaml-ext"
            """;

        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, yamlContent);

        JenticConfiguration config = loader.loadFromFile(configFile.toString());

        assertThat(config.runtime().name()).isEqualTo("yaml-ext");
    }

    @Test
    @DisplayName("Should default to YAML for unknown file extension")
    void shouldDefaultToYamlForUnknownExtension(@TempDir Path tempDir) throws Exception {
        String yamlContent = """
            agenor:
              runtime:
                name: "unknown-ext"
            """;

        Path configFile = tempDir.resolve("config.conf");
        Files.writeString(configFile, yamlContent);

        JenticConfiguration config = loader.loadFromFile(configFile.toString());

        assertThat(config.runtime().name()).isEqualTo("unknown-ext");
    }

    @Test
    @DisplayName("Should warn for unknown environment")
    void shouldWarnForUnknownEnvironment() {
        JenticConfiguration config = new JenticConfiguration(
                new JenticConfiguration.RuntimeConfig("test", "custom-env", null),
                new JenticConfiguration.AgentsConfig(false, "com.test", null, null, null),
                null, null, null
        );

        // Should not throw but log warning
        assertThat(config).satisfies(c -> {
            try {
                loader.validate(c);
            } catch (ConfigurationException e) {
                throw new AssertionError("Should not throw exception", e);
            }
        });
    }

    @Test
    @DisplayName("Should warn when autoDiscovery enabled but no packages")
    void shouldWarnWhenAutoDiscoveryWithoutPackages() {
        JenticConfiguration config = new JenticConfiguration(
                new JenticConfiguration.RuntimeConfig("test", "development", null),
                new JenticConfiguration.AgentsConfig(true, null, null, null, null),
                null, null, null
        );

        // Should not throw but log warning
        assertThat(config).satisfies(c -> {
            try {
                loader.validate(c);
            } catch (ConfigurationException e) {
                throw new AssertionError("Should not throw exception", e);
            }
        });
    }

    @Test
    @DisplayName("Should validate package names starting with letter")
    void shouldValidatePackageNamesStartWithLetter() {
        JenticConfiguration config = new JenticConfiguration(
                new JenticConfiguration.RuntimeConfig("test", "development", null),
                new JenticConfiguration.AgentsConfig(true, "com.example.valid123", null, null, null),
                null, null, null
        );

        assertThat(config).satisfies(c -> {
            try {
                loader.validate(c);
            } catch (ConfigurationException e) {
                throw new AssertionError("Should not throw exception", e);
            }
        });
    }

    @Test
    @DisplayName("Should reject package name starting with number")
    void shouldRejectPackageNameStartingWithNumber() {
        JenticConfiguration config = new JenticConfiguration(
                new JenticConfiguration.RuntimeConfig("test", "development", null),
                new JenticConfiguration.AgentsConfig(true, "123invalid", null, null, null),
                null, null, null
        );

        assertThatThrownBy(() -> loader.validate(config))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("Invalid package name");
    }

    @Test
    @DisplayName("Should handle loadDefault when file exists but is invalid")
    void shouldHandleLoadDefaultWithInvalidFile(@TempDir Path tempDir) throws Exception {
        Path originalDir = Path.of(System.getProperty("user.dir"));

        try {
            System.setProperty("user.dir", tempDir.toString());

            String malformedYaml = """
                agenor:
                  invalid: [unclosed
                """;

            Path configFile = tempDir.resolve("agenor.yml");
            Files.writeString(configFile, malformedYaml);

            JenticConfiguration config = loader.loadDefault();

            assertThat(config).isNotNull();
            assertThat(config.runtime().name()).isEqualTo("agenor-runtime");

        } finally {
            System.setProperty("user.dir", originalDir.toString());
        }
    }

    @Test
    @DisplayName("Should substitute null content to null")
    void shouldHandleNullContentInSubstitution(@TempDir Path tempDir) throws Exception {
        // This tests the null check in substituteEnvironmentVariables
        Path configFile = tempDir.resolve("test.yml");

        // Create empty file - reading it will give empty string not null
        // but this tests defensive null check
        Files.writeString(configFile, "");

        assertThatThrownBy(() -> loader.loadFromFile(configFile.toString()))
                .isInstanceOf(ConfigurationException.class);
    }
}
