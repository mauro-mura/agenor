package dev.agenor.core.config;

import dev.agenor.core.JenticConfiguration;

import java.io.InputStream;

/**
 * Contract for loading and validating Jentic configuration.
 * Implementations live in agenor-runtime or agenor-adapters.
 */
public interface ConfigurationLoader {

    /**
     * Loads configuration from a file on the filesystem.
     * Supports YAML ({@code .yml}, {@code .yaml}) and JSON ({@code .json}) formats.
     * Environment variable substitution ({@code ${VAR}} and {@code ${VAR:default}}) is applied.
     *
     * @param path absolute or relative path to the configuration file, not null or empty
     * @return the loaded configuration
     * @throws ConfigurationException if the file is not found, not readable, or cannot be parsed
     */
    JenticConfiguration loadFromFile(String path);

    /**
     * Loads configuration from a classpath resource.
     * Supports YAML ({@code .yml}, {@code .yaml}) and JSON ({@code .json}) formats.
     * Environment variable substitution ({@code ${VAR}} and {@code ${VAR:default}}) is applied.
     *
     * @param resourcePath classpath-relative path to the resource, not null or empty
     * @return the loaded configuration
     * @throws ConfigurationException if the resource is not found or cannot be parsed
     */
    JenticConfiguration loadFromClasspath(String resourcePath);

    /**
     * Loads configuration from an {@link InputStream}.
     * Environment variable substitution ({@code ${VAR}} and {@code ${VAR:default}}) is applied.
     *
     * @param inputStream the stream to read from, not null
     * @param format      the format hint: {@code "json"} for JSON, anything else defaults to YAML
     * @return the loaded configuration
     * @throws ConfigurationException if the stream is null or its content cannot be parsed
     */
    JenticConfiguration loadFromStream(InputStream inputStream, String format);

    /**
     * Loads configuration using the default lookup strategy:
     * <ol>
     *   <li>{@code agenor.yml} in the working directory</li>
     *   <li>{@code agenor.yml} on the classpath</li>
     *   <li>Built-in defaults via {@link dev.agenor.core.JenticConfiguration#defaults()}</li>
     * </ol>
     *
     * @return the loaded configuration, never null
     */
    JenticConfiguration loadDefault();

    /**
     * Validates the given configuration, checking for required fields and well-formed values.
     * Logs warnings for non-critical issues (e.g. unknown environment name, missing scan packages).
     *
     * @param configuration the configuration to validate, not null
     * @throws ConfigurationException if {@code configuration} is null, {@code runtime.name} is
     *                                empty, or any scan-package name is invalid
     */
    void validate(JenticConfiguration configuration);
}
