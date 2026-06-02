package dev.agenor.adapters.a2a;

import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentSkill;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Configuration for A2A adapter using official SDK types.
 *
 * @since 0.5.0
 */
public class A2AAdapterConfig {

    private String agentName;
    private String agentDescription;
    private String baseUrl;
    private String version = "1.0.0";
    private String protocolVersion = "0.3.0";
    private boolean streamingEnabled = false;
    private boolean pushNotifications = false;
    private Duration timeout = Duration.ofMinutes(5);
    private List<SkillConfig> skills = new ArrayList<>();
    private List<String> inputModes = List.of("text");
    private List<String> outputModes = List.of("text");

    public A2AAdapterConfig() {}

    // Fluent setters

    public A2AAdapterConfig agentName(String name) {
        this.agentName = name;
        return this;
    }

    public A2AAdapterConfig agentDescription(String description) {
        this.agentDescription = description;
        return this;
    }

    public A2AAdapterConfig baseUrl(String url) {
        this.baseUrl = url;
        return this;
    }

    public A2AAdapterConfig version(String version) {
        this.version = version;
        return this;
    }

    public A2AAdapterConfig protocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
        return this;
    }

    public A2AAdapterConfig streamingEnabled(boolean enabled) {
        this.streamingEnabled = enabled;
        return this;
    }

    public A2AAdapterConfig pushNotifications(boolean enabled) {
        this.pushNotifications = enabled;
        return this;
    }

    public A2AAdapterConfig timeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    public A2AAdapterConfig addSkill(SkillConfig skill) {
        this.skills.add(skill);
        return this;
    }

    public A2AAdapterConfig inputModes(List<String> modes) {
        this.inputModes = modes;
        return this;
    }

    public A2AAdapterConfig outputModes(List<String> modes) {
        this.outputModes = modes;
        return this;
    }

    // Getters

    public String getAgentName() { return agentName; }
    public String getAgentDescription() { return agentDescription; }
    public String getBaseUrl() { return baseUrl; }
    public String getVersion() { return version; }
    public String getProtocolVersion() { return protocolVersion; }
    public boolean isStreamingEnabled() { return streamingEnabled; }
    public boolean isPushNotifications() { return pushNotifications; }
    public Duration getTimeout() { return timeout; }
    public List<SkillConfig> getSkills() { return Collections.unmodifiableList(skills); }
    public List<String> getInputModes() { return inputModes; }
    public List<String> getOutputModes() { return outputModes; }

    /**
     * Builds an A2A SDK AgentCard from this configuration.
     */
    public AgentCard toAgentCard() {
        List<AgentSkill> agentSkills = skills.stream()
            .map(s -> new AgentSkill.Builder()
                .id(s.id())
                .name(s.name())
                .description(s.description())
                .tags(s.tags())
                .examples(s.examples())
                .build())
            .toList();

        AgentCapabilities capabilities = new AgentCapabilities.Builder()
            .streaming(streamingEnabled)
            .pushNotifications(pushNotifications)
            .stateTransitionHistory(false)
            .build();

        return new AgentCard.Builder()
            .name(agentName)
            .description(agentDescription)
            .url(baseUrl)
            .version(version)
            .protocolVersion(protocolVersion)
            .capabilities(capabilities)
            .defaultInputModes(inputModes)
            .defaultOutputModes(outputModes)
            .skills(agentSkills)
            .build();
    }

    /**
     * Skill configuration.
     */
    public record SkillConfig(
        String id,
        String name,
        String description,
        List<String> tags,
        List<String> examples
    ) {
        public SkillConfig(String id, String name, String description) {
            this(id, name, description, List.of(), List.of());
        }
    }

    /**
     * Creates a builder with defaults.
     */
    public static A2AAdapterConfig create() {
        return new A2AAdapterConfig();
    }
}
