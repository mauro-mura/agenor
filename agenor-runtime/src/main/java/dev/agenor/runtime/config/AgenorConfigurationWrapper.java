package dev.agenor.runtime.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.agenor.core.AgenorConfiguration;

/**
 * Wrapper for YAML/JSON configuration files.
 * Represents the root structure with "agenor:" element.
 */
public record AgenorConfigurationWrapper(
    @JsonProperty("agenor") AgenorConfiguration jentic
) {

    @JsonCreator
    public AgenorConfigurationWrapper(
        @JsonProperty("agenor") AgenorConfiguration jentic
    ) {
        this.jentic = jentic != null ? jentic : AgenorConfiguration.defaults();
    }

    public AgenorConfiguration getConfiguration() {
        return jentic;
    }
}
