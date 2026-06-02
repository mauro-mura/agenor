package dev.agenor.runtime.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.agenor.core.AgenorConfiguration;
import dev.agenor.core.config.ConfigurationLoader;
import dev.agenor.core.config.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default implementation of {@link ConfigurationLoader}.
 * Loads Agenor configuration from YAML, JSON, or classpath resources.
 * Supports environment variable substitution via ${VAR_NAME} and ${VAR_NAME:default} syntax.
 */
public class DefaultConfigurationLoader implements ConfigurationLoader {

    private static final Logger log = LoggerFactory.getLogger(DefaultConfigurationLoader.class);
    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private final ObjectMapper yamlMapper;
    private final ObjectMapper jsonMapper;

    public DefaultConfigurationLoader() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.jsonMapper = new ObjectMapper();
    }

    @Override
    public AgenorConfiguration loadFromFile(String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new ConfigurationException("Configuration path cannot be null or empty");
        }

        Path filePath = Paths.get(path);

        if (!Files.exists(filePath)) {
            throw new ConfigurationException("Configuration file not found: " + path);
        }

        if (!Files.isReadable(filePath)) {
            throw new ConfigurationException("Configuration file not readable: " + path);
        }

        try {
            String content = Files.readString(filePath);
            content = substituteEnvironmentVariables(content);

            ObjectMapper mapper = selectMapper(path);
            AgenorConfigurationWrapper wrapper = mapper.readValue(content, AgenorConfigurationWrapper.class);

            log.info("Loaded configuration from: {}", path);
            return wrapper.getConfiguration();

        } catch (IOException e) {
            log.error("Failed to load configuration from: {}", path, e);
            throw new ConfigurationException("Failed to load configuration from: " + path, e);
        }
    }

    @Override
    public AgenorConfiguration loadFromClasspath(String resourcePath) {
        if (resourcePath == null || resourcePath.trim().isEmpty()) {
            throw new ConfigurationException("Resource path cannot be null or empty");
        }

        if (resourcePath.startsWith("/")) {
            resourcePath = resourcePath.substring(1);
        }

        InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath);

        if (inputStream == null) {
            throw new ConfigurationException("Configuration resource not found: " + resourcePath);
        }

        try (inputStream) {
            String content = new String(inputStream.readAllBytes());
            content = substituteEnvironmentVariables(content);

            ObjectMapper mapper = selectMapper(resourcePath);
            AgenorConfigurationWrapper wrapper = mapper.readValue(content, AgenorConfigurationWrapper.class);

            log.info("Loaded configuration from classpath: {}", resourcePath);
            return wrapper.getConfiguration();

        } catch (IOException e) {
            log.error("Failed to load configuration from classpath: {}", resourcePath, e);
            throw new ConfigurationException("Failed to load configuration from classpath: " + resourcePath, e);
        }
    }

    @Override
    public AgenorConfiguration loadFromStream(InputStream inputStream, String format) {
        if (inputStream == null) {
            throw new ConfigurationException("Input stream cannot be null");
        }

        try {
            String content = new String(inputStream.readAllBytes());
            content = substituteEnvironmentVariables(content);

            ObjectMapper mapper = "json".equalsIgnoreCase(format) ? jsonMapper : yamlMapper;
            AgenorConfigurationWrapper wrapper = mapper.readValue(content, AgenorConfigurationWrapper.class);

            log.info("Loaded configuration from stream (format: {})", format);
            return wrapper.getConfiguration();

        } catch (IOException e) {
            log.error("Failed to load configuration from stream", e);
            throw new ConfigurationException("Failed to load configuration from stream", e);
        }
    }

    @Override
    public AgenorConfiguration loadDefault() {
        // 1. Try filesystem: agenor.yml in the working directory
        Path fsPath = Paths.get(System.getProperty("user.dir"), "agenor.yml");
        if (Files.exists(fsPath)) {
            try {
                return loadFromFile(fsPath.toString());
            } catch (ConfigurationException e) {
                log.warn("Failed to load default config from filesystem, trying classpath: {}", e.getMessage());
            }
        }

        // 2. Try classpath: agenor.yml
        try {
            return loadFromClasspath("agenor.yml");
        } catch (ConfigurationException e) {
            log.debug("No agenor.yml found on classpath, using built-in defaults");
        }

        // 3. Fall back to built-in defaults
        return AgenorConfiguration.defaults();
    }

    @Override
    public void validate(AgenorConfiguration config) {
        if (config == null) {
            throw new ConfigurationException("Configuration cannot be null");
        }

        AgenorConfiguration.RuntimeConfig runtime = config.runtime();
        if (runtime.name() == null || runtime.name().trim().isEmpty()) {
            throw new ConfigurationException("runtime.name cannot be null or empty");
        }

        String env = runtime.environment();
        if (!env.matches("development|staging|production|test")) {
            log.warn("Unknown environment '{}', expected: development, staging, production, or test", env);
        }

        AgenorConfiguration.AgentsConfig agents = config.agents();
        if (agents.autoDiscovery() && agents.getAllScanPackages().isEmpty()) {
            log.warn("autoDiscovery is enabled but no scanPackages configured");
        }

        for (String packageName : agents.getAllScanPackages()) {
            if (packageName == null || packageName.trim().isEmpty()) {
                throw new ConfigurationException("scanPackages cannot contain null or empty entries");
            }
            if (!packageName.matches("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)*$")) {
                throw new ConfigurationException("Invalid package name: " + packageName);
            }
        }

        log.debug("Configuration validation passed");
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private ObjectMapper selectMapper(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".json")) {
            return jsonMapper;
        } else if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
            return yamlMapper;
        } else {
            log.debug("Unknown file extension, defaulting to YAML parser");
            return yamlMapper;
        }
    }

    private String substituteEnvironmentVariables(String content) {
        if (content == null) {
            return null;
        }
        Matcher matcher = ENV_VAR_PATTERN.matcher(content);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String expression = matcher.group(1);
            String replacement = resolveVariable(expression);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String resolveVariable(String expression) {
        String[] parts = expression.split(":", 2);
        String varName = parts[0].trim();
        String defaultValue = parts.length > 1 ? parts[1].trim() : "";

        String value = System.getenv(varName);
        if (value == null) {
            value = System.getProperty(varName);
        }

        if (value == null) {
            if (!defaultValue.isEmpty()) {
                log.debug("Environment variable '{}' not found, using default: {}", varName, defaultValue);
                return defaultValue;
            } else {
                log.warn("Environment variable '{}' not found and no default provided", varName);
                return "${" + expression + "}";
            }
        }

        return value;
    }
}
