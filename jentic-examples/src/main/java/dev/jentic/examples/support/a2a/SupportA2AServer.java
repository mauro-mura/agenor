package dev.jentic.examples.support.a2a;

import dev.jentic.adapters.a2a.A2AAdapterConfig;
import dev.jentic.adapters.a2a.JenticAgentExecutor;
import dev.jentic.core.messaging.MessageDispatcher;
import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Factory for A2A components for FinanceCloud Support Chatbot.
 * 
 * <p>Provides the AgentCard and AgentExecutor needed to expose the support
 * chatbot via A2A protocol. The actual HTTP server must be provided by
 * a Quarkus application or the existing JettyWebConsole pattern.
 * 
 * <h2>Usage with Quarkus (recommended for A2A)</h2>
 * <pre>
 * // In a Quarkus CDI producer
 * {@literal @}ApplicationScoped
 * public class SupportAgentProducer {
 *     
 *     {@literal @}Inject
 *     MessageDispatcher messageService;
 *     
 *     {@literal @}Produces
 *     {@literal @}PublicAgentCard
 *     public AgentCard produceCard() {
 *         return SupportA2AServer.createAgentCard("http://localhost:8080");
 *     }
 *     
 *     {@literal @}Produces
 *     public AgentExecutor produceExecutor() {
 *         return SupportA2AServer.createExecutor(messageService);
 *     }
 * }
 * </pre>
 * 
 * <h2>Usage with JenticRuntime</h2>
 * <pre>
 * // Get executor for custom server integration
 * AgentExecutor executor = SupportA2AServer.createExecutor(
 *     runtime.getMessageDispatcher(),
 *     Duration.ofMinutes(2)
 * );
 * 
 * // Get AgentCard for registration
 * AgentCard card = SupportA2AServer.createAgentCard("http://myserver:8080");
 * </pre>
 * 
 * <h2>Integration with JettyWebConsole</h2>
 * <pre>
 * // The AgentCard can be served via existing WebConsole
 * JettyWebConsole console = JettyWebConsole.builder()
 *     .port(8080)
 *     .runtime(runtime)
 *     .build();
 * 
 * // Add custom endpoint for /.well-known/agent.json
 * // (requires extending JettyWebConsole or using custom servlet)
 * </pre>
 */
public final class SupportA2AServer {
    
    private static final Logger log = LoggerFactory.getLogger(SupportA2AServer.class);
    
    private static final String DEFAULT_ROUTER_AGENT = "router-agent";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);
    
    private SupportA2AServer() {
        // Static factory only
    }
    
    // ========== EXECUTOR FACTORY ==========
    
    /**
     * Creates the AgentExecutor that routes A2A requests to internal agents.
     * Uses default timeout of 2 minutes.
     * 
     * @param messageService the runtime's message service
     * @return executor ready for A2A server registration
     */
    public static AgentExecutor createExecutor(MessageDispatcher messageService) {
        return createExecutor(messageService, DEFAULT_TIMEOUT);
    }
    
    /**
     * Creates the AgentExecutor with custom timeout.
     * 
     * @param messageService the runtime's message service
     * @param timeout request timeout
     * @return executor ready for A2A server registration
     */
    public static AgentExecutor createExecutor(MessageDispatcher messageService, Duration timeout) {
        log.info("Creating A2A executor routing to: {}", DEFAULT_ROUTER_AGENT);
        return new JenticAgentExecutor(DEFAULT_ROUTER_AGENT, messageService, timeout);
    }
    
    /**
     * Creates the AgentExecutor for a specific internal agent.
     * 
     * @param messageService the runtime's message service
     * @param targetAgentId the internal agent to route to
     * @param timeout request timeout
     * @return executor ready for A2A server registration
     */
    public static AgentExecutor createExecutor(MessageDispatcher messageService, 
                                                String targetAgentId, 
                                                Duration timeout) {
        log.info("Creating A2A executor routing to: {}", targetAgentId);
        return new JenticAgentExecutor(targetAgentId, messageService, timeout);
    }
    
    // ========== AGENT CARD FACTORY ==========
    
    /**
     * Creates the AgentCard for this support chatbot.
     * 
     * @param baseUrl the public URL where this agent will be accessible
     * @return AgentCard with all support skills
     */
    public static AgentCard createAgentCard(String baseUrl) {
        A2AAdapterConfig config = SupportAgentCardConfig.create(baseUrl);
        AgentCard card = config.toAgentCard();
        
        log.info("Created AgentCard: name={}, skills={}, url={}", 
            card.name(), card.skills().size(), baseUrl);
        
        return card;
    }
    
    /**
     * Creates AgentCard with streaming option.
     */
    public static AgentCard createAgentCard(String baseUrl, boolean streaming) {
        A2AAdapterConfig config = SupportAgentCardConfig.create(baseUrl)
            .streamingEnabled(streaming);
        return config.toAgentCard();
    }
    
    // ========== CONFIGURATION FACTORY ==========
    
    /**
     * Creates the full A2A configuration.
     * 
     * @param baseUrl the public URL
     * @return configuration object
     */
    public static A2AAdapterConfig createConfig(String baseUrl) {
        return SupportAgentCardConfig.create(baseUrl);
    }
    
    /**
     * Creates configuration for production deployment.
     * 
     * @param baseUrl the public URL
     * @param streaming enable streaming responses
     * @param timeout request timeout
     * @return configuration object
     */
    public static A2AAdapterConfig createConfig(String baseUrl, boolean streaming, Duration timeout) {
        return SupportAgentCardConfig.create(baseUrl)
            .streamingEnabled(streaming)
            .timeout(timeout);
    }
    
    // ========== UTILITIES ==========
    
    /**
     * Serializes AgentCard to JSON string.
     * Useful for custom HTTP endpoints.
     */
    public static String serializeAgentCard(AgentCard card) {
        StringBuilder skills = new StringBuilder("[");
        var skillList = card.skills();
        for (int i = 0; i < skillList.size(); i++) {
            var skill = skillList.get(i);
            if (i > 0) skills.append(",");
            skills.append(String.format(
                "{\"id\":\"%s\",\"name\":\"%s\",\"description\":\"%s\",\"tags\":%s,\"examples\":%s}",
                skill.id(), 
                skill.name(), 
                skill.description() != null ? escapeJson(skill.description()) : "",
                toJsonArray(skill.tags()),
                toJsonArray(skill.examples())
            ));
        }
        skills.append("]");
        
        return String.format("""
            {
                "name": "%s",
                "description": "%s",
                "url": "%s",
                "version": "%s",
                "protocolVersion": "%s",
                "capabilities": {
                    "streaming": %s,
                    "pushNotifications": %s,
                    "stateTransitionHistory": false
                },
                "defaultInputModes": ["text"],
                "defaultOutputModes": ["text"],
                "skills": %s
            }""",
            card.name(),
            card.description() != null ? escapeJson(card.description()) : "",
            card.url(),
            card.version() != null ? card.version() : "1.0.0",
            card.protocolVersion() != null ? card.protocolVersion() : "0.3.0",
            card.capabilities().streaming(),
            card.capabilities().pushNotifications(),
            skills.toString()
        );
    }
    
    /**
     * Prints A2A configuration summary to log.
     */
    public static void logConfiguration(String baseUrl) {
        AgentCard card = createAgentCard(baseUrl);
        
        log.info("=== A2A Support Agent Configuration ===");
        log.info("Agent: {}", card.name());
        log.info("Description: {}", card.description());
        log.info("URL: {}", card.url());
        log.info("Version: {}", card.version());
        log.info("Streaming: {}", card.capabilities().streaming());
        log.info("Skills ({}):", card.skills().size());
        card.skills().forEach(skill -> 
            log.info("  - {} ({}): {}", skill.id(), skill.name(), skill.description()));
    }
    
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
    
    private static String toJsonArray(java.util.List<String> list) {
        if (list == null || list.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escapeJson(list.get(i))).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }
}
