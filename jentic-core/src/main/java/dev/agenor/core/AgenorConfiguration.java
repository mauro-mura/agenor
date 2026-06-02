package dev.agenor.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.*;

/**
 * Configuration object for the Jentic framework.
 * Can be loaded from YAML, JSON, or properties files.
 *
 * @param runtime   runtime identity settings (name, environment, properties);
 *                  defaults to {@link RuntimeConfig#defaults()} if null
 * @param agents    agent discovery and scanning configuration;
 *                  defaults to {@link AgentsConfig#defaults()} if null
 * @param messaging messaging provider configuration; defaults applied if null
 * @param directory agent directory provider configuration; defaults applied if null
 * @param scheduler behavior scheduler configuration; defaults applied if null
 * @since 0.1.0
 */
public record AgenorConfiguration(
    @JsonProperty("runtime") RuntimeConfig runtime,
    @JsonProperty("agents") AgentsConfig agents,
    @JsonProperty("messaging") MessagingConfig messaging,
    @JsonProperty("directory") DirectoryConfig directory,
    @JsonProperty("scheduler") SchedulerConfig scheduler
) {

    @JsonCreator
    public AgenorConfiguration(
            @JsonProperty("runtime") RuntimeConfig runtime,
            @JsonProperty("agents") AgentsConfig agents,
            @JsonProperty("messaging") MessagingConfig messaging,
            @JsonProperty("directory") DirectoryConfig directory,
            @JsonProperty("scheduler") SchedulerConfig scheduler
    ) {
        this.runtime = runtime != null ? runtime : RuntimeConfig.defaults();
        this.agents = agents != null ? agents : AgentsConfig.defaults();
        this.messaging = messaging != null ? messaging : MessagingConfig.defaults();  // Optional in Phase 2
        this.directory = directory != null ? directory : DirectoryConfig.defaults();   // Optional in Phase 2
        this.scheduler = scheduler != null ? scheduler : SchedulerConfig.defaults();   // Optional in Phase 2
    }

    /**
     * Runtime identity settings.
     *
     * @param name        runtime instance name; default {@code agenor-runtime}
     * @param environment environment label ({@code development}, {@code staging},
     *                    {@code production}, {@code test}); default {@code development}
     * @param properties  arbitrary key/value pairs available to agents at runtime
     */
    public record RuntimeConfig(
            @JsonProperty("name") String name,
            @JsonProperty("environment") String environment,
            @JsonProperty("properties") Map<String, String> properties
    ) {
        @JsonCreator
        public RuntimeConfig(
                @JsonProperty("name") String name,
                @JsonProperty("environment") String environment,
                @JsonProperty("properties") Map<String, String> properties
        ) {
            this.name = name != null ? name : "agenor-runtime";
            this.environment = environment != null ? environment : "development";
            this.properties = properties != null ?
                    Map.copyOf(properties) : Collections.emptyMap();
        }

        public static RuntimeConfig defaults() {
            return new RuntimeConfig("agenor-runtime", "development", null);
        }
    }

    /**
     * Create default configuration for Phase 2 (minimal)
     */
    public static AgenorConfiguration defaults() {
        return new AgenorConfiguration(
                RuntimeConfig.defaults(),
                AgentsConfig.defaults(),
                null,  // messaging - Phase 3
                null,  // directory - Phase 3
                null   // scheduler - Phase 3
        );
    }

    /**
     * Agent discovery and scanning configuration.
     *
     * @param autoDiscovery whether to scan for {@code @Agent} classes at startup
     * @param basePackage   primary root package to scan; merged into {@code scanPackages}
     * @param scanPaths     legacy alias for additional packages (kept for YAML compatibility);
     *                      merged into {@code scanPackages} at construction time
     * @param scanPackages  merged, deduplicated list of all packages to scan
     *                      (computed from {@code basePackage}, {@code scanPaths}, and explicit entries)
     * @param properties    arbitrary key/value pairs forwarded to agent factories
     */
    public record AgentsConfig(
            @JsonProperty("autoDiscovery") boolean autoDiscovery,
            @JsonProperty("basePackage") String basePackage,
            @JsonProperty("scanPaths") String[] scanPaths,
            @JsonProperty("scanPackages") List<String> scanPackages,
            @JsonProperty("properties") Map<String, String> properties
    ) {
        @JsonCreator
        public AgentsConfig(
                @JsonProperty("autoDiscovery") boolean autoDiscovery,
                @JsonProperty("basePackage") String basePackage,
                @JsonProperty("scanPaths") String[] scanPaths,
                @JsonProperty("scanPackages") List<String> scanPackages,
                @JsonProperty("properties") Map<String, String> properties
        ) {
            this.autoDiscovery = autoDiscovery;
            this.basePackage = basePackage;
            this.scanPaths = scanPaths;

            // Merge scanPaths and scanPackages
            List<String> allPackages = new ArrayList<>();
            if (scanPackages != null) {
                // Validate no null elements
                for (int i = 0; i < scanPackages.size(); i++) {
                    if (scanPackages.get(i) == null) {
                        throw new IllegalArgumentException(
                                "scanPackages[" + i + "] cannot be null"
                        );
                    }
                }
                allPackages.addAll(scanPackages);
            }
            if (scanPaths != null) {
                allPackages.addAll(Arrays.asList(scanPaths));
            }
            if (basePackage != null && !basePackage.trim().isEmpty()) {
                allPackages.add(basePackage);
            }

            this.scanPackages = allPackages.isEmpty() ?
                    Collections.emptyList() : List.copyOf(allPackages);

            this.properties = properties != null ?
                    Map.copyOf(properties) : Collections.emptyMap();
        }

        public static AgentsConfig defaults() {
            return new AgentsConfig(true, null, null, null, null);
        }

        /**
         * Get all scan packages (merged from basePackage, scanPaths, scanPackages)
         */
        public List<String> getAllScanPackages() {
            return scanPackages;
        }
    }

    /**
     * Messaging provider configuration.
     *
     * @param provider   messaging implementation identifier (e.g. {@code inmemory});
     *                   default {@code inmemory}
     * @param properties provider-specific configuration key/value pairs
     */
    public record MessagingConfig(
            @JsonProperty("provider") String provider,
            @JsonProperty("properties") Map<String, String> properties
    ) {
        @JsonCreator
        public MessagingConfig(
                @JsonProperty("provider") String provider,
                @JsonProperty("properties") Map<String, String> properties
        ) {
            this.provider = provider;
            this.properties = properties != null ?
                    Map.copyOf(properties) : Collections.emptyMap();
        }

        public static MessagingConfig defaults() {
            return new MessagingConfig("inmemory", null);
        }
    }

    /**
     * Agent directory provider configuration.
     *
     * @param provider   directory implementation identifier (e.g. {@code local});
     *                   default {@code local}
     * @param properties provider-specific configuration key/value pairs
     */
    public record DirectoryConfig(
            @JsonProperty("provider") String provider,
            @JsonProperty("properties") Map<String, String> properties
    ) {
        @JsonCreator
        public DirectoryConfig(
                @JsonProperty("provider") String provider,
                @JsonProperty("properties") Map<String, String> properties
        ) {
            this.provider = provider;
            this.properties = properties != null ?
                    Map.copyOf(properties) : Collections.emptyMap();
        }

        public static DirectoryConfig defaults() {
            return new DirectoryConfig("local", null);
        }
    }

    /**
     * Behavior scheduler configuration.
     *
     * @param provider       scheduler implementation identifier (e.g. {@code simple});
     *                       default {@code simple}
     * @param threadPoolSize thread pool size for behavior execution; default {@code 10}
     * @param properties     provider-specific configuration key/value pairs
     */
    public record SchedulerConfig(
            @JsonProperty("provider") String provider,
            @JsonProperty("threadPoolSize") int threadPoolSize,
            @JsonProperty("properties") Map<String, String> properties
    ) {
        @JsonCreator
        public SchedulerConfig(
                @JsonProperty("provider") String provider,
                @JsonProperty("threadPoolSize") int threadPoolSize,
                @JsonProperty("properties") Map<String, String> properties
        ) {
            this.provider = provider;
            this.threadPoolSize = threadPoolSize;
            this.properties = properties != null ?
                    Map.copyOf(properties) : Collections.emptyMap();
        }

        public static SchedulerConfig defaults() {
            return new SchedulerConfig("simple", 10, null);
        }
    }
}
