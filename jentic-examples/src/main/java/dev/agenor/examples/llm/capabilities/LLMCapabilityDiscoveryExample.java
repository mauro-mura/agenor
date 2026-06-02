package dev.agenor.examples.llm.capabilities;

import dev.agenor.adapters.llm.LLMProviderFactory;
import dev.agenor.adapters.llm.openai.OpenAIProvider;
import dev.agenor.core.*;
import dev.agenor.core.annotations.*;
import dev.agenor.core.annotations.Agent;
import dev.agenor.core.annotations.Behavior;
import dev.agenor.core.llm.*;
import dev.agenor.runtime.AgenorRuntime;
import dev.agenor.runtime.agent.BaseAgent;

import java.util.*;
import java.util.concurrent.*;

/**
 * LLM multi-agent example — capability-based agent discovery.
 *
 * Demonstrates how a coordinator queries AgentDirectory by capability string
 * instead of hardcoded agent IDs. Agents are auto-discovered via scanPackages().
 * The coordinator selects specialists at runtime based on the required capability
 * set, making the system composable and easy to extend with new specialist types.
 *
 * Pattern: scanPackages auto-discovery, capability queries, flexible delegation.
 * See LLMDirectMessagingExample for the simpler point-to-point baseline.
 * See LLMFaultToleranceExample for handling agents going offline at runtime.
 */
public class LLMCapabilityDiscoveryExample {

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("ERROR: OPENAI_API_KEY environment variable not set");
            System.exit(1);
        }

        LLMProvider llmProvider = LLMProviderFactory.openai()
            .apiKey(apiKey)
            .modelName(OpenAIProvider.Models.GPT_4_1_MINI)
            .temperature(0.7)
            .maxTokens(1500)
            .logRequests(true)
            .logResponses(true)
            .build();

        AgenorRuntime runtime = AgenorRuntime.builder()
            .scanPackages("dev.agenor.examples.llm.capabilities")
            .service(LLMProvider.class, llmProvider)
            .build();

        runtime.start();

        System.out.println("\n" + "=".repeat(70));
        System.out.println("RESEARCH TEAM - CAPABILITY-BASED DISCOVERY");
        System.out.println("=".repeat(70));
        System.out.println("✓ Query by capabilities, not types");
        System.out.println("✓ More flexible and composable");
        System.out.println("✓ Agents can have multiple capabilities\n");

        Thread.sleep(3000);

        // Example 1: Standard research
        System.out.println("=== Example 1: Full Analysis ===");
        sendResearchRequest(runtime, "capability-coordinator", "Blockchain in Supply Chain Management", "comprehensive");
        Thread.sleep(30000);

        // Example 2: Only market analysis
        System.out.println("\n=== Example 2: Market Focus Only ===");
        sendResearchRequest(runtime, "capability-coordinator", "5G Network Infrastructure", "market-only");
        Thread.sleep(30000);

        System.out.println("\n=== Shutting Down ===");
        runtime.stop().join();
    }

    private static void sendResearchRequest(AgenorRuntime runtime, String coordinatorId,
                                            String topic, String analysisType) {
        Message request = Message.builder()
            .topic("research.request")
            .senderId("user")
            .receiverId(coordinatorId)
            .content(Map.of(
                "topic", topic,
                "analysisType", analysisType  // comprehensive, market-only, technical-only
            ))
            .build();

        runtime.getMessageDispatcher().sendTo(request);
    }
}

/**
 * Capability-Based Coordinator
 *
 * Key Pattern: Query by specific capabilities needed for the task
 */
@Agent(
    value = "capability-coordinator",
    type = "coordinator",
    capabilities = {"research-orchestration", "synthesis"},
    autoStart = true
)
class CapabilityCoordinator extends BaseAgent {

    private final LLMProvider llm;
    private final Map<String, ResearchContext> activeResearch = new ConcurrentHashMap<>();

    public CapabilityCoordinator(LLMProvider llm) {
        super("capability-coordinator", "Capability-Based Coordinator");
        this.llm = llm;
    }

    @AgenorMessageHandler(value = "research.request", autoSubscribe = true)
    public void handleResearchRequest(Message message) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) message.content();
        String topic = (String) data.get("topic");
        String analysisType = (String) data.getOrDefault("analysisType", "comprehensive");

        log.info("🎯 Research request: {} [type: {}]", topic, analysisType);

        String requestId = UUID.randomUUID().toString();
        ResearchContext ctx = new ResearchContext(requestId, topic, analysisType);
        activeResearch.put(requestId, ctx);

        createResearchPlan(ctx);
    }

    private void createResearchPlan(ResearchContext ctx) {
        String planningPrompt = String.format(
            "Create research plan for: '%s'\n" +
            "Analysis type: %s\n" +
            "Identify required capabilities.",
            ctx.topic, ctx.analysisType
        );

        LLMRequest request = LLMRequest.builder()
            .addMessage(LLMMessage.system("You are a research planner."))
            .addMessage(LLMMessage.user(planningPrompt))
            .temperature(0.7)
            .maxTokens(300)
            .build();

        llm.chat(request).thenAccept(response -> {
            ctx.plan = response.content();
            log.info("📋 Research plan created");

            // ✅ KEY: Determine required capabilities based on analysis type
            Set<String> requiredCapabilities = determineRequiredCapabilities(ctx.analysisType);
            discoverAgentsByCapabilities(ctx, requiredCapabilities);

        }).exceptionally(ex -> {
            log.error("❌ Error creating plan", ex);
            return null;
        });
    }

    /**
     * ✅ Determine which capabilities are needed for this research
     */
    private Set<String> determineRequiredCapabilities(String analysisType) {
        return switch (analysisType) {
            case "comprehensive" -> Set.of(
                "technical-analysis",
                "market-analysis",
                "competitive-intelligence"
            );
            case "market-only" -> Set.of(
                "market-analysis",
                "trend-forecasting"
            );
            case "technical-only" -> Set.of(
                "technical-analysis",
                "architecture-review"
            );
            case "competitive-only" -> Set.of(
                "competitive-intelligence",
                "swot-analysis"
            );
            default -> Set.of("technical-analysis", "market-analysis");
        };
    }

    /**
     * ✅ KEY PATTERN: Query agents by capabilities, not by type
     */
    private void discoverAgentsByCapabilities(ResearchContext ctx, Set<String> requiredCapabilities) {
        log.info("🔍 Looking for agents with capabilities: {}", requiredCapabilities);

        // Create a map to store agents grouped by capability
        Map<String, List<AgentDescriptor>> agentsByCapability = new ConcurrentHashMap<>();

        // Query for each required capability
        List<CompletableFuture<Void>> queryFutures = new ArrayList<>();

        for (String capability : requiredCapabilities) {
            // ✅ Query by single capability
            AgentQuery query = AgentQuery.builder()
                .requiredCapability(capability)  // Query by capability!
                .status(AgentStatus.RUNNING)
                .build();

            CompletableFuture<Void> queryFuture = agentDirectory
                .findAgents(query, PageRequest.first(Integer.MAX_VALUE))
                .thenApply(Page::content)
                .thenAccept(agents -> {
                    if (!agents.isEmpty()) {
                        agentsByCapability.put(capability, agents);
                        log.info("✅ Found {} agent(s) for capability: {}",
                                agents.size(), capability);

                        // Log which agents were found
                        agents.forEach(agent ->
                            log.debug("  - {} (all capabilities: {})",
                                     agent.agentName(), agent.capabilities())
                        );
                    } else {
                        log.warn("⚠️ No agents found for capability: {}", capability);
                    }
                });

            queryFutures.add(queryFuture);
        }

        // Wait for all queries to complete
        CompletableFuture.allOf(queryFutures.toArray(new CompletableFuture[0]))
            .thenRun(() -> {
                log.info("📊 Discovery complete: {} capabilities covered",
                         agentsByCapability.size());

                // Delegate to discovered agents
                delegateToAgents(ctx, agentsByCapability);
            });
    }

    /**
     * Delegate tasks to agents based on their capabilities
     */
    private void delegateToAgents(ResearchContext ctx, Map<String, List<AgentDescriptor>> agentsByCapability) {
        if (agentsByCapability.isEmpty()) {
            log.error("❌ No agents available for any required capability!");
            return;
        }

        // Map capabilities to message topics
        Map<String, String> capabilityToTopic = Map.of(
            "technical-analysis", "research.task.technical",
            "market-analysis", "research.task.market",
            "competitive-intelligence", "research.task.competitor",
            "trend-forecasting", "research.task.trends"
        );

        agentsByCapability.forEach((capability, agents) -> {
            String topic = capabilityToTopic.get(capability);
            if (topic == null) {
                log.warn("⚠️ No topic mapping for capability: {}", capability);
                return;
            }

            // Select first agent (could implement load balancing here)
            AgentDescriptor selectedAgent = agents.get(0);

            ctx.expectedResponses.add(capability);

            Message taskMsg = Message.builder()
                .topic(topic)
                .senderId(getAgentId())
                .receiverId(selectedAgent.agentId())
                .content(Map.of(
                    "requestId", ctx.requestId,
                    "topic", ctx.topic,
                    "capability", capability
                ))
                .build();

            getMessageDispatcher().sendTo(taskMsg);
            log.info("📤 Delegated {} to: {} (ID: {})",
                    capability, selectedAgent.agentName(), selectedAgent.agentId());
        });
    }

    @AgenorMessageHandler(value = "research.findings.technical", autoSubscribe = true)
    public void handleTechnicalFindings(Message msg) {
        processFinding(msg, "technical-analysis", "Technical");
    }

    @AgenorMessageHandler(value = "research.findings.market", autoSubscribe = true)
    public void handleMarketFindings(Message msg) {
        processFinding(msg, "market-analysis", "Market");
    }

    @AgenorMessageHandler(value = "research.findings.competitor", autoSubscribe = true)
    public void handleCompetitorFindings(Message msg) {
        processFinding(msg, "competitive-intelligence", "Competitor");
    }

    @AgenorMessageHandler(value = "research.findings.trends", autoSubscribe = true)
    public void handleTrendFindings(Message msg) {
        processFinding(msg, "trend-forecasting", "Trends");
    }

    private void processFinding(Message msg, String capability, String displayName) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) msg.content();
        String requestId = (String) data.get("requestId");
        String findings = (String) data.get("findings");

        ResearchContext ctx = activeResearch.get(requestId);
        if (ctx == null) return;

        log.info("✅ Received {} findings", displayName);
        ctx.addFinding(displayName, findings);
        ctx.receivedResponses.add(capability);

        if (ctx.isComplete()) {
            synthesizeReport(ctx);
        }
    }

    private void synthesizeReport(ResearchContext ctx) {
        log.info("🔄 Synthesizing final report...");

        String allFindings = String.join("\n\n", ctx.findings.values());
        String synthPrompt = "Synthesize research findings:\n\n" + allFindings;

        LLMRequest request = LLMRequest.builder()
            .addMessage(LLMMessage.system("You are a research synthesizer."))
            .addMessage(LLMMessage.user(synthPrompt))
            .temperature(0.5)
            .maxTokens(800)
            .build();

        llm.chat(request).thenAccept(response -> {
            System.out.println("\n" + "=".repeat(70));
            System.out.println("📊 RESEARCH REPORT - " + ctx.topic);
            System.out.println("Analysis Type: " + ctx.analysisType);
            System.out.println("Capabilities Used: " + ctx.receivedResponses.size());
            System.out.println("=".repeat(70));
            System.out.println(response.content());
            System.out.println("=".repeat(70) + "\n");

            activeResearch.remove(ctx.requestId);
        });
    }

    @Behavior(type = BehaviorType.CYCLIC, interval = "30s", autoStart = true)
    public void monitorCapabilities() {
        // Show available capabilities in the system
        AgentQuery query = AgentQuery.builder()
            .status(AgentStatus.RUNNING)
            .build();

        agentDirectory.findAgents(query, PageRequest.first(Integer.MAX_VALUE))
                .thenApply(Page::content)
                .thenAccept(agents -> {
            if (!agents.isEmpty()) {
                Set<String> allCapabilities = new HashSet<>();
                agents.forEach(agent -> allCapabilities.addAll(agent.capabilities()));

                log.info("📊 System capabilities: {} from {} agents",
                         allCapabilities.size(), agents.size());
                log.debug("Available: {}", allCapabilities);
            }
        });
    }

    static class ResearchContext {
        final String requestId, topic, analysisType;
        String plan;
        final Map<String, String> findings = new ConcurrentHashMap<>();
        final Set<String> expectedResponses = ConcurrentHashMap.newKeySet();
        final Set<String> receivedResponses = ConcurrentHashMap.newKeySet();

        ResearchContext(String requestId, String topic, String analysisType) {
            this.requestId = requestId;
            this.topic = topic;
            this.analysisType = analysisType;
        }

        void addFinding(String type, String finding) {
            findings.put(type, String.format("=== %s ===\n%s", type, finding));
        }

        boolean isComplete() {
            return receivedResponses.containsAll(expectedResponses);
        }
    }
}

/**
 * Tech Specialist with multiple capabilities
 */
@Agent(
    value = "capability-tech-specialist",
    type = "specialist",
    capabilities = {
        "technical-analysis",      // Primary
        "architecture-review",     // Secondary
        "feasibility-study",       // Secondary
        "security-assessment"      // Bonus capability!
    },
    autoStart = true
)
class CapabilityTechSpecialist extends BaseAgent {

    private final LLMProvider llm;

    public CapabilityTechSpecialist(LLMProvider llm) {
        super("capability-tech-specialist", "Capability Tech Specialist");
        this.llm = llm;
    }

    @AgenorMessageHandler(value = "research.task.technical", autoSubscribe = true)
    public void handleTask(Message msg) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) msg.content();
        String requestId = (String) data.get("requestId");
        String topic = (String) data.get("topic");
        String capability = (String) data.get("capability");

        log.info("🔧 Technical analysis: {} [using capability: {}]", topic, capability);

        String prompt = String.format("Technical analysis of: %s\nFocus: %s", topic, capability);

        LLMRequest request = LLMRequest.builder()
            .addMessage(LLMMessage.user(prompt))
            .temperature(0.6)
            .maxTokens(700)
            .build();

        llm.chat(request).thenAccept(response -> {
            log.info("✅ Technical analysis complete");

            Message reply = Message.builder()
                .topic("research.findings.technical")
                .senderId(getAgentId())
                .receiverId(msg.senderId())
                .content(Map.of("requestId", requestId, "findings", response.content()))
                .build();

            getMessageDispatcher().sendTo(reply);
        });
    }
}

/**
 * Market Specialist with forecasting capability
 */
@Agent(
    value = "capability-market-specialist",
    type = "specialist",
    capabilities = {
        "market-analysis",         // Primary
        "trend-forecasting",       // Secondary - bonus!
        "demand-analysis"          // Secondary
    },
    autoStart = true
)
class CapabilityMarketSpecialist extends BaseAgent {

    private final LLMProvider llm;

    public CapabilityMarketSpecialist(LLMProvider llm) {
        super("capability-market-specialist", "Capability Market Specialist");
        this.llm = llm;
    }

    @AgenorMessageHandler(value = "research.task.market", autoSubscribe = true)
    public void handleTask(Message msg) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) msg.content();
        String requestId = (String) data.get("requestId");
        String topic = (String) data.get("topic");

        log.info("📈 Market analysis: {}", topic);

        String prompt = "Market analysis of: " + topic;

        LLMRequest request = LLMRequest.builder()
            .addMessage(LLMMessage.user(prompt))
            .temperature(0.6)
            .maxTokens(700)
            .build();

        llm.chat(request).thenAccept(response -> {
            log.info("✅ Market analysis complete");

            Message reply = Message.builder()
                .topic("research.findings.market")
                .senderId(getAgentId())
                .receiverId(msg.senderId())
                .content(Map.of("requestId", requestId, "findings", response.content()))
                .build();

            getMessageDispatcher().sendTo(reply);
        });
    }

    // ✅ Can also handle trend analysis (has trend-forecasting capability)
    @AgenorMessageHandler(value = "research.task.trends", autoSubscribe = true)
    public void handleTrends(Message msg) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) msg.content();
        String requestId = (String) data.get("requestId");
        String topic = (String) data.get("topic");

        log.info("📊 Trend forecasting: {}", topic);

        String prompt = "Forecast trends for: " + topic;

        LLMRequest request = LLMRequest.builder()
            .addMessage(LLMMessage.user(prompt))
            .temperature(0.6)
            .maxTokens(700)
            .build();

        llm.chat(request).thenAccept(response -> {
            log.info("✅ Trend analysis complete");

            Message reply = Message.builder()
                .topic("research.findings.trends")
                .senderId(getAgentId())
                .receiverId(msg.senderId())
                .content(Map.of("requestId", requestId, "findings", response.content()))
                .build();

            getMessageDispatcher().sendTo(reply);
        });
    }
}

/**
 * Competitor Specialist
 */
@Agent(
    value = "capability-competitor-specialist",
    type = "specialist",
    capabilities = {
        "competitive-intelligence",
        "swot-analysis",
        "strategy-analysis"
    },
    autoStart = true
)
class CapabilityCompetitorSpecialist extends BaseAgent {

    private final LLMProvider llm;

    public CapabilityCompetitorSpecialist(LLMProvider llm) {
        super("capability-competitor-specialist", "Capability Competitor Specialist");
        this.llm = llm;
    }

    @AgenorMessageHandler(value = "research.task.competitor", autoSubscribe = true)
    public void handleTask(Message msg) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) msg.content();
        String requestId = (String) data.get("requestId");
        String topic = (String) data.get("topic");

        log.info("🎯 Competitive analysis: {}", topic);

        String prompt = "Competitive analysis of: " + topic;

        LLMRequest request = LLMRequest.builder()
            .addMessage(LLMMessage.user(prompt))
            .temperature(0.6)
            .maxTokens(700)
            .build();

        llm.chat(request).thenAccept(response -> {
            log.info("✅ Competitive analysis complete");

            Message reply = Message.builder()
                .topic("research.findings.competitor")
                .senderId(getAgentId())
                .receiverId(msg.senderId())
                .content(Map.of("requestId", requestId, "findings", response.content()))
                .build();

            getMessageDispatcher().sendTo(reply);
        });
    }
}
