package dev.agenor.adapters.a2a;

import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentSkill;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for A2AAdapterConfig.
 */
class A2AAdapterConfigTest {

    @Test
    void shouldCreateConfigWithDefaults() {
        // Given / When
        var config = new A2AAdapterConfig();

        // Then
        assertThat(config.getVersion()).isEqualTo("1.0.0");
        assertThat(config.getProtocolVersion()).isEqualTo("0.3.0");
        assertThat(config.isStreamingEnabled()).isFalse();
        assertThat(config.isPushNotifications()).isFalse();
        assertThat(config.getTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(config.getSkills()).isEmpty();
        assertThat(config.getInputModes()).containsExactly("text");
        assertThat(config.getOutputModes()).containsExactly("text");
    }

    @Test
    void shouldBuildConfigWithFluentAPI() {
        // Given / When
        var config = A2AAdapterConfig.create()
            .agentName("test-agent")
            .agentDescription("A test agent")
            .baseUrl("http://localhost:8080")
            .version("2.0.0")
            .protocolVersion("0.4.0")
            .streamingEnabled(true)
            .pushNotifications(true)
            .timeout(Duration.ofMinutes(10))
            .inputModes(List.of("text", "audio"))
            .outputModes(List.of("text", "image"));

        // Then
        assertThat(config.getAgentName()).isEqualTo("test-agent");
        assertThat(config.getAgentDescription()).isEqualTo("A test agent");
        assertThat(config.getBaseUrl()).isEqualTo("http://localhost:8080");
        assertThat(config.getVersion()).isEqualTo("2.0.0");
        assertThat(config.getProtocolVersion()).isEqualTo("0.4.0");
        assertThat(config.isStreamingEnabled()).isTrue();
        assertThat(config.isPushNotifications()).isTrue();
        assertThat(config.getTimeout()).isEqualTo(Duration.ofMinutes(10));
        assertThat(config.getInputModes()).containsExactly("text", "audio");
        assertThat(config.getOutputModes()).containsExactly("text", "image");
    }

    @Test
    void shouldAddSkills() {
        // Given
        var skill1 = new A2AAdapterConfig.SkillConfig(
            "skill-1",
            "Weather Check",
            "Check weather conditions"
        );
        var skill2 = new A2AAdapterConfig.SkillConfig(
            "skill-2",
            "News Fetch",
            "Fetch latest news",
            List.of("news", "updates"),
            List.of("What's the latest news?")
        );

        // When
        var config = new A2AAdapterConfig()
            .addSkill(skill1)
            .addSkill(skill2);

        // Then
        assertThat(config.getSkills()).hasSize(2);
        assertThat(config.getSkills().get(0).id()).isEqualTo("skill-1");
        assertThat(config.getSkills().get(1).id()).isEqualTo("skill-2");
    }

    @Test
    void shouldCreateSkillConfigWithMinimalParams() {
        // Given / When
        var skill = new A2AAdapterConfig.SkillConfig(
            "calc",
            "Calculator",
            "Perform calculations"
        );

        // Then
        assertThat(skill.id()).isEqualTo("calc");
        assertThat(skill.name()).isEqualTo("Calculator");
        assertThat(skill.description()).isEqualTo("Perform calculations");
        assertThat(skill.tags()).isEmpty();
        assertThat(skill.examples()).isEmpty();
    }

    @Test
    void shouldCreateSkillConfigWithAllParams() {
        // Given / When
        var skill = new A2AAdapterConfig.SkillConfig(
            "translate",
            "Translator",
            "Translate text between languages",
            List.of("translation", "language", "i18n"),
            List.of("Translate 'hello' to Spanish", "How do you say 'goodbye' in French?")
        );

        // Then
        assertThat(skill.id()).isEqualTo("translate");
        assertThat(skill.name()).isEqualTo("Translator");
        assertThat(skill.description()).isEqualTo("Translate text between languages");
        assertThat(skill.tags()).containsExactly("translation", "language", "i18n");
        assertThat(skill.examples()).hasSize(2);
    }

    @Test
    void shouldConvertToAgentCardWithoutSkills() {
        // Given
        var config = new A2AAdapterConfig()
            .agentName("simple-agent")
            .agentDescription("A simple agent")
            .baseUrl("http://localhost:9000")
            .version("1.5.0")
            .streamingEnabled(true);

        // When
        AgentCard card = config.toAgentCard();

        // Then
        assertThat(card.name()).isEqualTo("simple-agent");
        assertThat(card.description()).isEqualTo("A simple agent");
        assertThat(card.url()).isEqualTo("http://localhost:9000");
        assertThat(card.version()).isEqualTo("1.5.0");
        assertThat(card.protocolVersion()).isEqualTo("0.3.0");
        assertThat(card.capabilities().streaming()).isTrue();
        assertThat(card.capabilities().pushNotifications()).isFalse();
        assertThat(card.defaultInputModes()).containsExactly("text");
        assertThat(card.defaultOutputModes()).containsExactly("text");
        assertThat(card.skills()).isEmpty();
    }

    @Test
    void shouldConvertToAgentCardWithSkills() {
        // Given
        var skill1 = new A2AAdapterConfig.SkillConfig(
            "weather",
            "Weather Service",
            "Check weather",
            List.of("weather", "forecast"),
            List.of("What's the weather?")
        );
        var skill2 = new A2AAdapterConfig.SkillConfig(
            "news",
            "News Service",
            "Get news updates"
        );

        var config = new A2AAdapterConfig()
            .agentName("multi-skill-agent")
            .agentDescription("Agent with multiple skills")
            .baseUrl("http://localhost:8080")
            .addSkill(skill1)
            .addSkill(skill2);

        // When
        AgentCard card = config.toAgentCard();

        // Then
        assertThat(card.skills()).hasSize(2);

        AgentSkill cardSkill1 = card.skills().get(0);
        assertThat(cardSkill1.id()).isEqualTo("weather");
        assertThat(cardSkill1.name()).isEqualTo("Weather Service");
        assertThat(cardSkill1.description()).isEqualTo("Check weather");
        assertThat(cardSkill1.tags()).containsExactly("weather", "forecast");
        assertThat(cardSkill1.examples()).containsExactly("What's the weather?");

        AgentSkill cardSkill2 = card.skills().get(1);
        assertThat(cardSkill2.id()).isEqualTo("news");
        assertThat(cardSkill2.name()).isEqualTo("News Service");
        assertThat(cardSkill2.description()).isEqualTo("Get news updates");
    }

    @Test
    void shouldConvertToAgentCardWithCustomCapabilities() {
        // Given
        var config = new A2AAdapterConfig()
            .agentName("advanced-agent")
            .agentDescription("Advanced capabilities")
            .baseUrl("http://localhost:8080")
            .streamingEnabled(true)
            .pushNotifications(true)
            .inputModes(List.of("text", "voice"))
            .outputModes(List.of("text", "audio", "image"));

        // When
        AgentCard card = config.toAgentCard();

        // Then
        assertThat(card.capabilities().streaming()).isTrue();
        assertThat(card.capabilities().pushNotifications()).isTrue();
        assertThat(card.capabilities().stateTransitionHistory()).isFalse();
        assertThat(card.defaultInputModes()).containsExactly("text", "voice");
        assertThat(card.defaultOutputModes()).containsExactly("text", "audio", "image");
    }

    @Test
    void shouldReturnUnmodifiableSkillsList() {
        // Given
        var skill = new A2AAdapterConfig.SkillConfig("s1", "Skill 1", "Description");
        var config = new A2AAdapterConfig().addSkill(skill);

        // When
        var skills = config.getSkills();

        // Then
        assertThat(skills).hasSize(1);
        // Should be unmodifiable
        try {
            skills.add(new A2AAdapterConfig.SkillConfig("s2", "Skill 2", "Desc"));
            assertThat(true).as("Should have thrown exception").isFalse();
        } catch (UnsupportedOperationException e) {
            // Expected
            assertThat(e).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    void shouldChainFluentSetters() {
        // Given / When
        var config = new A2AAdapterConfig()
            .agentName("chain-test")
            .agentDescription("Test chaining")
            .baseUrl("http://test")
            .version("1.0.0")
            .timeout(Duration.ofSeconds(30));

        // Then - verify all setters return this for chaining
        assertThat(config).isNotNull();
        assertThat(config.getAgentName()).isEqualTo("chain-test");
        assertThat(config.getAgentDescription()).isEqualTo("Test chaining");
        assertThat(config.getBaseUrl()).isEqualTo("http://test");
        assertThat(config.getVersion()).isEqualTo("1.0.0");
        assertThat(config.getTimeout()).isEqualTo(Duration.ofSeconds(30));
    }
}
