package dev.agenor.runtime.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.agenor.core.JenticConfiguration;

/**
 * Wrapper for YAML/JSON configuration files.
 * Represents the root structure with "agenor:" element.
 */
public record JenticConfigurationWrapper(
    @JsonProperty("agenor") JenticConfiguration jentic
) {

    @JsonCreator
    public JenticConfigurationWrapper(
        @JsonProperty("agenor") JenticConfiguration jentic
    ) {
        this.jentic = jentic != null ? jentic : JenticConfiguration.defaults();
    }

    public JenticConfiguration getConfiguration() {
        return jentic;
    }
}
