package dev.jentic.examples.llm.dynamic_discovery;

import dev.jentic.core.*;
import dev.jentic.core.annotations.*;
import dev.jentic.core.llm.*;
import dev.jentic.runtime.JenticRuntime;
import dev.jentic.runtime.agent.BaseAgent;
import dev.jentic.adapters.llm.openai.OpenAIProvider;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * LLM multi-agent example — dynamic discovery with fault tolerance.
 *
 * Demonstrates a coordinator that queries AgentDirectory for all running
 * specialist agents at task-delegation time, adapts to whatever agents are
 * available, and gracefully handles specialists going offline mid-run.
 * Agents are auto-discovered via scanPackages().
 *
 * The second scenario explicitly stops a specialist before the request to
 * show that the coordinator produces a partial report rather than failing.
 *
 * Pattern: scanPackages, runtime agent status queries, adaptive delegation.
 * See LLMDirectMessagingExample for the simpler point-to-point baseline.
 * See LLMCapabilityDiscoveryExample for structured capability-based routing.
 */
public class LLMFaultToleranceExample {

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("ERROR: OPENAI_API_KEY environment variable not set");
            System.exit(1);
        }

        // Create LLM provider
        LLMProvider llmProvider = OpenAIProvider.builder()
            .apiKey(apiKey)
            .modelName(OpenAIProvider.Models.GPT_4_1_MINI)
            .temperature(0.7)
            .maxTokens(1500)
            .build();

        // Create runtime
        JenticRuntime runtime = JenticRuntime.builder()
            .scanPackages("dev.jentic.examples.llm.dynamic_discovery")
            .service(LLMProvider.class, llmProvider)
            .build();

        // Start runtime
        runtime.start();

        System.out.println("\n" + "=".repeat(70));
        System.out.println("RESEARCH TEAM - DYNAMIC AGENT DISCOVERY");
        System.out.println("=".repeat(70));
        System.out.println("✓ Coordinator queries AgentDirectory for available specialists");
        System.out.println("✓ Dynamic task delegation based on capabilities");
        System.out.println("✓ Fault-tolerant (handles missing specialists)\n");

        // Wait for agents to register
        Thread.sleep(2000);

        // Get coordinator from runtime (it was auto-discovered)
        Agent coordinator = runtime.getAgent("dynamic-coordinator")
            .orElseThrow(() -> new RuntimeException("Coordinator not found!"));
        // Get market researcher from runtime (it was auto-discovered)
        Agent marketResearcher = runtime.getAgent("dynamic-market-researcher")
                .orElseThrow(() -> new RuntimeException("Market researcher not found!"));

        // Example 1: Standard research with all specialists
        System.out.println("=== Example 1: Full Research Team ===");
        sendResearchRequest(coordinator, "Artificial General Intelligence - Path to 2030");
        Thread.sleep(20000);

        // Example 2: Simulate specialist going offline
        System.out.println("\n=== Example 2: Handling Missing Specialist ===");
        System.out.println("Stopping market researcher to test fault tolerance...");
        marketResearcher.stop().join();
        Thread.sleep(2000);

        sendResearchRequest(coordinator, "Neural Network Hardware Acceleration");
        Thread.sleep(20000);

        // Shutdown
        System.out.println("\n=== Shutting Down ===");
        runtime.stop().join();
    }

    private static void sendResearchRequest(Agent coordinator, String topic) {
        Message request = Message.builder()
            .topic("research.request")
            .senderId("user")
            .receiverId(coordinator.getAgentId())
            .content(Map.of(
                "topic", topic,
                "priority", "high",
                "depth", "comprehensive"
            ))
            .build();

        coordinator.getMessageService().send(request);
    }
}

/**
 * Dynamic Coordinator - Discovers specialists via AgentDirectory
 */
@JenticAgent(value = "dynamic-coordinator", type = "coordinator", capabilities = {"research-planning", "synthesis"})
class DynamicCoordinator extends BaseAgent {

    private final LLMProvider llm;
    private final Map<String, ResearchContext> activeResearch = new ConcurrentHashMap<>();

    public DynamicCoordinator(LLMProvider llm) {
        super("dynamic-coordinator", "Dynamic Research Coordinator");
        this.llm = llm;
    }

    @JenticMessageHandler(value = "research.request", autoSubscribe = true)
    public void handleResearchRequest(Message message) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) message.content();
        String topic = (String) data.get("topic");
        String priority = (String) data.getOrDefault("priority", "normal");

        log.info("🎯 Received research request: {} [{}]", topic, priority);

        String requestId = UUID.randomUUID().toString();
        ResearchContext ctx = new ResearchContext(requestId, topic, priority);
        activeResearch.put(requestId, ctx);

        // Step 1: Create research plan with LLM
        createResearchPlan(ctx);
    }

    private void createResearchPlan(ResearchContext ctx) {
        String planningPrompt = String.format(
            "Create a research plan for: '%s'\n" +
            "Identify 3-4 key research areas that need specialist analysis.\n" +
            "For each area, specify the required capability.\n" +
            "Format as: Area | Required Capability",
            ctx.topic
        );

        LLMRequest request = LLMRequest.builder("gpt-4")
            .addMessage(LLMMessage.system("You are a research planning expert."))
            .addMessage(LLMMessage.user(planningPrompt))
            .temperature(0.7)
            .maxTokens(400)
            .build();

        llm.chat(request).thenAccept(response -> {
            ctx.plan = response.content();
            log.info("📋 Research plan created");

            // Step 2: Discover available specialists
            discoverAndDelegateToSpecialists(ctx);

        }).exceptionally(ex -> {
            log.error("❌ Error creating plan", ex);
            return null;
        });
    }

    private void discoverAndDelegateToSpecialists(ResearchContext ctx) {
        // Query AgentDirectory for all specialist agents
        AgentQuery specialistQuery = AgentQuery.builder()
            .agentType("specialist")
            .status(AgentStatus.RUNNING)
            .build();

        agentDirectory.findAgents(specialistQuery).thenAccept(specialists -> {
            log.info("🔍 Found {} available specialists", specialists.size());

            if (specialists.isEmpty()) {
                log.warn("⚠️ No specialists available! Cannot complete research.");
                return;
            }

            // Map capabilities to required analysis
            Map<String, String> capabilityToAnalysis = Map.of(
                "technical-analysis", "research.task.technical",
                "market-analysis", "research.task.market",
                "competitive-intelligence", "research.task.competitor"
            );

            // Delegate tasks based on discovered agents
            specialists.forEach(specialist -> {
                Set<String> capabilities = specialist.capabilities();

                // Find matching analysis task
                capabilityToAnalysis.forEach((requiredCap, taskTopic) -> {
                    if (capabilities.contains(requiredCap)) {
                        ctx.expectedResponses.add(requiredCap);
                        delegateToSpecialist(specialist, taskTopic, ctx);
                    }
                });
            });

            // Check if we have minimum required specialists
            if (ctx.expectedResponses.isEmpty()) {
                log.warn("⚠️ No specialists with required capabilities found!");
            } else {
                log.info("✅ Delegated to {} specialists", ctx.expectedResponses.size());
            }

        }).exceptionally(ex -> {
            log.error("❌ Error discovering specialists", ex);
            return null;
        });
    }

    private void delegateToSpecialist(AgentDescriptor specialist, String taskTopic, ResearchContext ctx) {
        log.info("📤 Delegating {} to agent: {} ({})",
                 taskTopic, specialist.agentName(), specialist.agentId());

        Message taskMsg = Message.builder()
            .topic(taskTopic)
            .senderId(getAgentId())
            .receiverId(specialist.agentId())  // Dynamic agent ID from directory
            .content(Map.of(
                "requestId", ctx.requestId,
                "topic", ctx.topic,
                "plan", ctx.plan,
                "priority", ctx.priority
            ))
            .build();

        messageService.send(taskMsg);
    }

    @JenticMessageHandler(value = "research.findings.technical", autoSubscribe = true)
    public void handleTechnicalFindings(Message message) {
        processFinding(message, "technical-analysis", "Technical");
    }

    @JenticMessageHandler(value = "research.findings.market", autoSubscribe = true)
    public void handleMarketFindings(Message message) {
        processFinding(message, "market-analysis", "Market");
    }

    @JenticMessageHandler(value = "research.findings.competitor", autoSubscribe = true)
    public void handleCompetitorFindings(Message message) {
        processFinding(message, "competitive-intelligence", "Competitor");
    }

    private void processFinding(Message message, String capability, String displayName) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) message.content();
        String requestId = (String) data.get("requestId");
        String findings = (String) data.get("findings");

        ResearchContext ctx = activeResearch.get(requestId);
        if (ctx == null) return;

        log.info("✅ Received {} findings", displayName);
        ctx.addFinding(displayName, findings);
        ctx.receivedResponses.add(capability);

        // Check if all expected responses received
        if (ctx.isComplete()) {
            synthesizeReport(ctx);
        }
    }

    private void synthesizeReport(ResearchContext ctx) {
        log.info("🔄 All findings collected. Synthesizing report...");

        String allFindings = String.join("\n\n", ctx.findings.values());
        String synthPrompt = String.format(
            "Synthesize these research findings:\n\n%s\n\n" +
            "Create an executive summary with:\n" +
            "1. Key insights\n" +
            "2. Strategic recommendations\n" +
            "3. Risk assessment\n" +
            "Note: Analysis based on %d specialists (some may be unavailable).",
            allFindings, ctx.receivedResponses.size()
        );

        LLMRequest request = LLMRequest.builder("gpt-4")
            .addMessage(LLMMessage.system("You are an expert research synthesizer."))
            .addMessage(LLMMessage.user(synthPrompt))
            .temperature(0.5)
            .maxTokens(900)
            .build();

        llm.chat(request).thenAccept(response -> {
            System.out.println("\n" + "=".repeat(70));
            System.out.println("📊 FINAL RESEARCH REPORT");
            System.out.println("Topic: " + ctx.topic);
            System.out.println("Specialists Engaged: " + ctx.receivedResponses.size() + "/" + ctx.expectedResponses.size());
            System.out.println("=".repeat(70));
            System.out.println(response.content());
            System.out.println("=".repeat(70) + "\n");

            activeResearch.remove(ctx.requestId);

        }).exceptionally(ex -> {
            log.error("❌ Error synthesizing report", ex);
            return null;
        });
    }

    @JenticBehavior(type = BehaviorType.CYCLIC, interval = "20s", autoStart = true)
    public void monitorSpecialists() {
        // Periodically check available specialists
        AgentQuery query = AgentQuery.builder()
            .agentType("specialist")
            .status(AgentStatus.RUNNING)
            .build();

        agentDirectory.findAgents(query).thenAccept(specialists -> {
            if (!specialists.isEmpty()) {
                String caps = specialists.stream()
                    .flatMap(s -> s.capabilities().stream())
                    .distinct()
                    .collect(Collectors.joining(", "));
                log.info("📊 Available specialists: {} with capabilities: {}",
                         specialists.size(), caps);
            }
        });
    }

    static class ResearchContext {
        final String requestId;
        final String topic;
        final String priority;
        String plan;
        final Map<String, String> findings = new ConcurrentHashMap<>();
        final Set<String> expectedResponses = ConcurrentHashMap.newKeySet();
        final Set<String> receivedResponses = ConcurrentHashMap.newKeySet();

        ResearchContext(String requestId, String topic, String priority) {
            this.requestId = requestId;
            this.topic = topic;
            this.priority = priority;
        }

        void addFinding(String type, String finding) {
            findings.put(type, String.format("=== %s Analysis ===\n%s", type, finding));
        }

        boolean isComplete() {
            return receivedResponses.containsAll(expectedResponses);
        }
    }
}

/**
 * Dynamic Technical Researcher
 */
@JenticAgent(
    value = "dynamic-tech-researcher",
    type = "specialist",
    capabilities = {"technical-analysis", "architecture-review", "feasibility-study"}
)
class DynamicTechnicalResearcher extends BaseAgent {

    private final LLMProvider llm;
    private final AtomicInteger taskCount = new AtomicInteger(0);

    public DynamicTechnicalResearcher(LLMProvider llm) {
        super("dynamic-tech-researcher", "Dynamic Technical Researcher");
        this.llm = llm;
    }

    @JenticMessageHandler(value = "research.task.technical", autoSubscribe = true)
    public void handleResearchTask(Message message) {
        taskCount.incrementAndGet();

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) message.content();
        String requestId = (String) data.get("requestId");
        String topic = (String) data.get("topic");

        log.info("🔧 Technical analysis starting for: {}", topic);

        String analysisPrompt = String.format(
            "Technical deep-dive: '%s'\n" +
            "Analyze:\n" +
            "- Core technologies and architecture\n" +
            "- Technical feasibility and challenges\n" +
            "- Scalability and performance considerations\n" +
            "- Innovation opportunities and risks",
            topic
        );

        FunctionDefinition techTool = FunctionDefinition.builder("evaluate_technology")
            .description("Evaluate technology maturity and readiness")
            .stringParameter("technology", "Technology to evaluate", true)
            .enumParameter("aspect", "Evaluation aspect", true,
                "maturity", "scalability", "security", "cost")
            .build();

        LLMRequest request = LLMRequest.builder("gpt-4")
            .addMessage(LLMMessage.system("You are a senior technical architect."))
            .addMessage(LLMMessage.user(analysisPrompt))
            .addFunction(techTool)
            .temperature(0.6)
            .maxTokens(700)
            .build();

        llm.chat(request).thenAccept(response -> {
            log.info("✅ Technical analysis complete");

            Map<String, Object> replyContent = new java.util.HashMap<>();
            if (requestId != null) {
                replyContent.put("requestId", requestId);
            }
            String findings = response.content() != null
                    ? response.content()
                    : "No content returned by the model.";
            replyContent.put("findings", findings);

            Message reply = Message.builder()
                .topic("research.findings.technical")
                .senderId(getAgentId())
                .receiverId(message.senderId())
                .content(Map.copyOf(replyContent))
                .correlationId(message.id())
                .build();

            messageService.send(reply);
        }).exceptionally(ex -> {
            log.error("❌ Technical analysis failed", ex);
            return null;
        });
    }

    @JenticBehavior(type = BehaviorType.CYCLIC, interval = "30s", autoStart = true)
    public void reportStatus() {
        if (taskCount.get() > 0) {
            log.info("🔧 Technical Researcher active - Tasks: {}", taskCount.get());
        }
    }
}

/**
 * Dynamic Market Researcher
 */
@JenticAgent(
    value = "dynamic-market-researcher",
    type = "specialist",
    capabilities = {"market-analysis", "trend-forecasting", "demand-analysis"}
)
class DynamicMarketResearcher extends BaseAgent {

    private final LLMProvider llm;
    private final AtomicInteger taskCount = new AtomicInteger(0);

    public DynamicMarketResearcher(LLMProvider llm) {
        super("dynamic-market-researcher", "Dynamic Market Researcher");
        this.llm = llm;
    }

    @JenticMessageHandler(value = "research.task.market", autoSubscribe = true)
    public void handleResearchTask(Message message) {
        taskCount.incrementAndGet();

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) message.content();
        String requestId = (String) data.get("requestId");
        String topic = (String) data.get("topic");

        log.info("📈 Market analysis starting for: {}", topic);

        String analysisPrompt = String.format(
            "Market analysis: '%s'\n" +
            "Analyze:\n" +
            "- Market size, growth, and trends\n" +
            "- Customer segments and demand drivers\n" +
            "- Market barriers and opportunities\n" +
            "- Pricing dynamics and revenue models",
            topic
        );

        FunctionDefinition marketTool = FunctionDefinition.builder("analyze_market_segment")
            .description("Analyze market segment dynamics")
            .stringParameter("segment", "Market segment", true)
            .enumParameter("metric", "Key metric", true,
                "size", "growth", "saturation", "competition")
            .build();

        LLMRequest request = LLMRequest.builder("gpt-4")
            .addMessage(LLMMessage.system("You are a market research analyst."))
            .addMessage(LLMMessage.user(analysisPrompt))
            .addFunction(marketTool)
            .temperature(0.6)
            .maxTokens(700)
            .build();

        llm.chat(request).thenAccept(response -> {
            log.info("✅ Market analysis complete");

            Map<String, Object> replyContent = new java.util.HashMap<>();
            if (requestId != null) {
                replyContent.put("requestId", requestId);
            }
            String findings = response.content() != null
                    ? response.content()
                    : "No content returned by the model.";
            replyContent.put("findings", findings);

            Message reply = Message.builder()
                .topic("research.findings.market")
                .senderId(getAgentId())
                .receiverId(message.senderId())
                .content(Map.copyOf(replyContent))
                .correlationId(message.id())
                .build();

            messageService.send(reply);
        }).exceptionally(ex -> {
            log.error("❌ Market analysis failed", ex);
            return null;
        });
    }

    @JenticBehavior(type = BehaviorType.CYCLIC, interval = "30s", autoStart = true)
    public void reportStatus() {
        if (taskCount.get() > 0) {
            log.info("📈 Market Researcher active - Tasks: {}", taskCount.get());
        }
    }
}

/**
 * Dynamic Competitor Researcher
 */
@JenticAgent(
    value = "dynamic-competitor-researcher",
    type = "specialist",
    capabilities = {"competitive-intelligence", "swot-analysis", "strategy-analysis"}
)
class DynamicCompetitorResearcher extends BaseAgent {

    private final LLMProvider llm;
    private final AtomicInteger taskCount = new AtomicInteger(0);

    public DynamicCompetitorResearcher(LLMProvider llm) {
        super("dynamic-competitor-researcher", "Dynamic Competitor Researcher");
        this.llm = llm;
    }

    @JenticMessageHandler(value = "research.task.competitor", autoSubscribe = true)
    public void handleResearchTask(Message message) {
        taskCount.incrementAndGet();

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) message.content();
        String requestId = (String) data.get("requestId");
        String topic = (String) data.get("topic");

        log.info("🎯 Competitive analysis starting for: {}", topic);

        String analysisPrompt = String.format(
            "Competitive analysis: '%s'\n" +
            "Analyze:\n" +
            "- Key players and market positions\n" +
            "- Competitive advantages and differentiators\n" +
            "- Strategic threats and opportunities\n" +
            "- Emerging players and disruptions",
            topic
        );

        FunctionDefinition compTool = FunctionDefinition.builder("analyze_competitor_strategy")
            .description("Deep dive competitor analysis")
            .stringParameter("competitor", "Competitor type or name", true)
            .enumParameter("focus", "Analysis focus", true,
                "strategy", "products", "pricing", "partnerships")
            .build();

        LLMRequest request = LLMRequest.builder("gpt-4")
            .addMessage(LLMMessage.system("You are a competitive intelligence expert."))
            .addMessage(LLMMessage.user(analysisPrompt))
            .addFunction(compTool)
            .temperature(0.6)
            .maxTokens(700)
            .build();

        llm.chat(request).thenAccept(response -> {
            log.info("✅ Competitive analysis complete");

            Map<String, Object> replyContent = new java.util.HashMap<>();
            if (requestId != null) {
                replyContent.put("requestId", requestId);
            }
            String findings = response.content() != null
                    ? response.content()
                    : "No content returned by the model.";
            replyContent.put("findings", findings);

            Message reply = Message.builder()
                .topic("research.findings.competitor")
                .senderId(getAgentId())
                .receiverId(message.senderId())
                .content(Map.copyOf(replyContent))
                .correlationId(message.id())
                .build();

            messageService.send(reply);
        }).exceptionally(ex -> {
            log.error("❌ Competitive analysis failed", ex);
            return null;
        });
    }

    @JenticBehavior(type = BehaviorType.CYCLIC, interval = "30s", autoStart = true)
    public void reportStatus() {
        if (taskCount.get() > 0) {
            log.info("🎯 Competitor Researcher active - Tasks: {}", taskCount.get());
        }
    }
}
