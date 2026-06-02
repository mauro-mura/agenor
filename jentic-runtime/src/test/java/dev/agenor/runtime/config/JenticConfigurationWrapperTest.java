package dev.agenor.runtime.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.agenor.core.JenticConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for JenticConfigurationWrapper
 */
class JenticConfigurationWrapperTest {

    @Test
    @DisplayName("Should create wrapper with non-null configuration")
    void shouldCreateWrapperWithNonNullConfiguration() {
        JenticConfiguration config = JenticConfiguration.defaults();
        JenticConfigurationWrapper wrapper = new JenticConfigurationWrapper(config);

        assertThat(wrapper).isNotNull();
        assertThat(wrapper.getConfiguration()).isEqualTo(config);
        assertThat(wrapper.jentic()).isEqualTo(config);
    }

    @Test
    @DisplayName("Should create wrapper with null configuration using defaults")
    void shouldCreateWrapperWithNullConfigurationUsingDefaults() {
        JenticConfigurationWrapper wrapper = new JenticConfigurationWrapper(null);

        assertThat(wrapper).isNotNull();
        assertThat(wrapper.getConfiguration()).isNotNull();
        assertThat(wrapper.getConfiguration().runtime().name()).isEqualTo("agenor-runtime");
    }

    @Test
    @DisplayName("Should deserialize from YAML correctly")
    void shouldDeserializeFromYamlCorrectly() throws Exception {
        String yamlContent = """
            agenor:
              runtime:
                name: "yaml-test"
                environment: "staging"
            """;

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        JenticConfigurationWrapper wrapper = mapper.readValue(
            yamlContent,
            JenticConfigurationWrapper.class
        );

        assertThat(wrapper).isNotNull();
        assertThat(wrapper.getConfiguration().runtime().name()).isEqualTo("yaml-test");
        assertThat(wrapper.getConfiguration().runtime().environment()).isEqualTo("staging");
    }

    @Test
    @DisplayName("Should deserialize from JSON correctly")
    void shouldDeserializeFromJsonCorrectly() throws Exception {
        String jsonContent = """
            {
              "agenor": {
                "runtime": {
                  "name": "json-test",
                  "environment": "production"
                }
              }
            }
            """;

        ObjectMapper mapper = new ObjectMapper();
        JenticConfigurationWrapper wrapper = mapper.readValue(
            jsonContent,
            JenticConfigurationWrapper.class
        );

        assertThat(wrapper).isNotNull();
        assertThat(wrapper.getConfiguration().runtime().name()).isEqualTo("json-test");
        assertThat(wrapper.getConfiguration().runtime().environment()).isEqualTo("production");
    }

    @Test
    @DisplayName("Should use defaults when agenor field is missing")
    void shouldUseDefaultsWhenJenticFieldIsMissing() throws Exception {
        String jsonContent = "{}";

        ObjectMapper mapper = new ObjectMapper();
        JenticConfigurationWrapper wrapper = mapper.readValue(
            jsonContent,
            JenticConfigurationWrapper.class
        );

        assertThat(wrapper).isNotNull();
        assertThat(wrapper.getConfiguration()).isNotNull();
        assertThat(wrapper.getConfiguration().runtime().name()).isEqualTo("agenor-runtime");
    }

    @Test
    @DisplayName("Should preserve complete configuration through wrapper")
    void shouldPreserveCompleteConfigurationThroughWrapper() {
        JenticConfiguration original = new JenticConfiguration(
            new JenticConfiguration.RuntimeConfig(
                "test-runtime",
                "production",
                java.util.Map.of("key1", "value1", "key2", "value2")
            ),
            new JenticConfiguration.AgentsConfig(
                true,
                null,
                null,
                java.util.List.of("com.example.agents"),
                java.util.Map.of("timeout", "30s")
            ),
            null,
            null,
            null
        );

        JenticConfigurationWrapper wrapper = new JenticConfigurationWrapper(original);
        JenticConfiguration retrieved = wrapper.getConfiguration();

        assertThat(retrieved.runtime().name()).isEqualTo("test-runtime");
        assertThat(retrieved.runtime().environment()).isEqualTo("production");
        assertThat(retrieved.runtime().properties())
            .containsEntry("key1", "value1")
            .containsEntry("key2", "value2");

        assertThat(retrieved.agents().autoDiscovery()).isTrue();
        assertThat(retrieved.agents().getAllScanPackages())
            .containsExactly("com.example.agents");
        assertThat(retrieved.agents().properties())
            .containsEntry("timeout", "30s");
    }

    @Test
    @DisplayName("Should handle minimal configuration")
    void shouldHandleMinimalConfiguration() throws Exception {
        String yamlContent = """
            agenor:
              runtime:
                name: "minimal"
            """;

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        JenticConfigurationWrapper wrapper = mapper.readValue(
            yamlContent,
            JenticConfigurationWrapper.class
        );

        assertThat(wrapper.getConfiguration().runtime().name()).isEqualTo("minimal");
        assertThat(wrapper.getConfiguration().runtime().environment()).isEqualTo("development");
        assertThat(wrapper.getConfiguration().agents()).isNotNull();
    }
}
