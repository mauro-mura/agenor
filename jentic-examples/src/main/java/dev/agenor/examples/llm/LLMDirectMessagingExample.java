package dev.agenor.examples.llm;

import dev.agenor.core.*;
import dev.agenor.core.annotations.*;
import dev.agenor.core.annotations.Agent;
import dev.agenor.core.annotations.Behavior;
import dev.agenor.core.llm.*;
import dev.agenor.runtime.AgenorRuntime;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.adapters.llm.openai.OpenAIProvider;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LLM multi-agent example — annotations + point-to-point direct messaging.
 *
 * Demonstrates @Agent, @Behavior, @AgenorMessageHandler with agents
 * registered manually via registerAgent() and communicating via direct receiverId
 * addressing (point-to-point), with LLM function calling through OpenAI GPT-4.
 *
 * Pattern: manual registration, hardcoded agent IDs, direct messaging.
 * See LLMCapabilityDiscoveryExample for capability-based discovery.
 * See LLMFaultToleranceExample for dynamic discovery with fault tolerance.
 */
public class LLMDirectMessagingExample {

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("ERROR: OPENAI_API_KEY environment variable not set");
            System.exit(1);
        }

        LLMProvider llmProvider = OpenAIProvider.builder()
            .apiKey(apiKey)
            .modelName(OpenAIProvider.Models.GPT_4O_MINI)
            .temperature(0.7)
            .maxTokens(1500)
            .build();

        AgenorRuntime runtime = AgenorRuntime.builder().build();

        runtime.registerAgent(new CoordinatorAgent(llmProvider));
        runtime.registerAgent(new TechnicalResearcher(llmProvider));
        runtime.registerAgent(new MarketResearcher(llmProvider));
        runtime.registerAgent(new CompetitorResearcher(llmProvider));

        runtime.start().join();

        System.out.println("\n=== Research Team Started ===");

        String researchTopic = "Quantum Computing Applications in Finance 2025";
        System.out.println("Research Topic: " + researchTopic);
        System.out.println("\nSending direct message to coordinator...\n");

        Message request = Message.builder()
            .topic("research.request")
            .senderId("user")
            .receiverId("coordinator")
            .content(Map.of("topic", researchTopic, "priority", "high"))
            .build();

        runtime.getMessageDispatcher().sendTo(request);

        Thread.sleep(60000);

        System.out.println("\n=== Shutting Down ===");
        runtime.stop().join();
    }

    // =========================================================================
    // COORDINATOR
    // =========================================================================

    @Agent(value = "coordinator", type = "coordinator", capabilities = {"planning", "synthesis"})
    public static class CoordinatorAgent extends BaseAgent {

        private final LLMProvider llm;
        private final Map<String, ResearchContext> activeResearch = new ConcurrentHashMap<>();

        public CoordinatorAgent(LLMProvider llm) {
            super("coordinator", "Research Coordinator");
            this.llm = llm;
        }

        @AgenorMessageHandler(value = "research.request", autoSubscribe = true)
        public void handleResearchRequest(Message message) {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) message.content();
            String topic = (String) data.get("topic");
            String priority = (String) data.getOrDefault("priority", "normal");

            log.info("📋 Coordinator received research request: {} [Priority: {}]", topic, priority);

            String requestId = UUID.randomUUID().toString();
            ResearchContext ctx = new ResearchContext(requestId, topic, priority);
            activeResearch.put(requestId, ctx);

            createResearchPlan(ctx);
        }

        @Behavior(type = BehaviorType.CYCLIC, interval = "10s", autoStart = true)
        public void checkResearchProgress() {
            if (!activeResearch.isEmpty()) {
                log.info("📊 Active research tasks: {}", activeResearch.size());
            }
        }

        @AgenorMessageHandler(value = "research.findings.technical", autoSubscribe = true)
        public void handleTechnicalFindings(Message message) { processFinding(message, "Technical"); }

        @AgenorMessageHandler(value = "research.findings.market", autoSubscribe = true)
        public void handleMarketFindings(Message message) { processFinding(message, "Market"); }

        @AgenorMessageHandler(value = "research.findings.competitor", autoSubscribe = true)
        public void handleCompetitorFindings(Message message) { processFinding(message, "Competitor"); }

        private void createResearchPlan(ResearchContext ctx) {
            String planningPrompt = String.format(
                "Create a research plan for: '%s'\nPriority: %s\n" +
                "Break into 3 focused research questions:\n" +
                "1. Technical Analysis\n2. Market Analysis\n3. Competitive Analysis\n" +
                "Be specific and actionable.",
                ctx.topic, ctx.priority
            );

            LLMRequest request = LLMRequest.builder()
                .addMessage(LLMMessage.system("You are an expert research planner."))
                .addMessage(LLMMessage.user(planningPrompt))
                .temperature(0.7)
                .maxTokens(400)
                .build();

            llm.chat(request).thenAccept(response -> {
                ctx.plan = response.content();
                log.info("📝 Research plan created for: {}", ctx.topic);
                delegateResearch(ctx);
            }).exceptionally(ex -> {
                log.error("❌ Error creating research plan", ex);
                return null;
            });
        }

        private void delegateResearch(ResearchContext ctx) {
            var techMsg = Message.builder()
                .topic("research.task.technical").senderId(getAgentId())
                .receiverId("tech-researcher")
                .content(Map.of("requestId", ctx.requestId, "topic", ctx.topic, "plan", ctx.plan))
                .build();
            getMessageDispatcher().sendTo(techMsg);
            var marketMsg = Message.builder()
                .topic("research.task.market").senderId(getAgentId())
                .receiverId("market-researcher")
                .content(Map.of("requestId", ctx.requestId, "topic", ctx.topic, "plan", ctx.plan))
                .build();
            getMessageDispatcher().sendTo(marketMsg);
            var compMsg = Message.builder()
                .topic("research.task.competitor").senderId(getAgentId())
                .receiverId("competitor-researcher")
                .content(Map.of("requestId", ctx.requestId, "topic", ctx.topic, "plan", ctx.plan))
                .build();
            getMessageDispatcher().sendTo(compMsg);
            log.info("🚀 Research tasks delegated to all specialists");
        }

        private void processFinding(Message message, String type) {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) message.content();
            String requestId = (String) data.get("requestId");
            String findings = (String) data.get("findings");

            ResearchContext ctx = activeResearch.get(requestId);
            if (ctx == null) {
                log.warn("⚠️ Unknown research request: {}", requestId);
                return;
            }

            log.info("✅ Received {} findings", type);
            ctx.addFinding(type, findings);

            if (ctx.isComplete()) {
                synthesizeReport(ctx);
            }
        }

        private void synthesizeReport(ResearchContext ctx) {
            log.info("🔄 Synthesizing final report...");

            String allFindings = String.join("\n\n", ctx.findings.values());
            String synthPrompt = String.format(
                "Synthesize these research findings into an executive summary:\n\n%s\n\n" +
                "Provide key insights, trends, and strategic recommendations.",
                allFindings
            );

            LLMRequest request = LLMRequest.builder()
                .addMessage(LLMMessage.system("You are an expert research synthesizer."))
                .addMessage(LLMMessage.user(synthPrompt))
                .temperature(0.5)
                .maxTokens(800)
                .build();

            llm.chat(request).thenAccept(response -> {
                System.out.println("\n" + "=".repeat(70));
                System.out.println("📊 FINAL RESEARCH REPORT - " + ctx.topic);
                System.out.println("=".repeat(70));
                System.out.println(response.content());
                System.out.println("=".repeat(70) + "\n");
                activeResearch.remove(ctx.requestId);
            }).exceptionally(ex -> {
                log.error("❌ Error synthesizing report", ex);
                return null;
            });
        }

        static class ResearchContext {
            final String requestId, topic, priority;
            String plan;
            final Map<String, String> findings = new ConcurrentHashMap<>();

            ResearchContext(String requestId, String topic, String priority) {
                this.requestId = requestId;
                this.topic = topic;
                this.priority = priority;
            }

            void addFinding(String type, String finding) {
                findings.put(type, String.format("=== %s Analysis ===\n%s", type, finding));
            }

            boolean isComplete() { return findings.size() >= 3; }
        }
    }

    // =========================================================================
    // SPECIALISTS
    // =========================================================================

    @Agent(value = "tech-researcher", type = "specialist",
                 capabilities = {"technical-analysis", "specs-evaluation"})
    public static class TechnicalResearcher extends BaseAgent {

        private final LLMProvider llm;
        private final AtomicInteger taskCount = new AtomicInteger(0);

        public TechnicalResearcher(LLMProvider llm) {
            super("tech-researcher", "Technical Researcher");
            this.llm = llm;
        }

        @AgenorMessageHandler(value = "research.task.technical", autoSubscribe = true)
        public void handleResearchTask(Message message) {
            taskCount.incrementAndGet();

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) message.content();
            String requestId = (String) data.get("requestId");
            String topic = (String) data.get("topic");

            log.info("🔧 Technical researcher analyzing: {}", topic);

            FunctionDefinition techTool = FunctionDefinition.builder("evaluate_technology")
                .description("Evaluate technical specifications and requirements")
                .stringParameter("technology", "Technology to evaluate", true)
                .enumParameter("aspect", "Evaluation aspect", true,
                    "maturity", "scalability", "security", "performance")
                .build();

            LLMRequest request = LLMRequest.builder()
                .addMessage(LLMMessage.system("You are a senior technical architect."))
                .addMessage(LLMMessage.user(String.format(
                    "Technical analysis of: '%s'\nFocus on: core technologies, feasibility, " +
                    "scalability requirements, innovation opportunities.", topic)))
                .addFunction(techTool)
                .temperature(0.6).maxTokens(700)
                .build();

            llm.chat(request).thenAccept(response -> {
                if (response.hasFunctionCalls()) log.info("🛠️ Using technical evaluation tools");
                log.info("✅ Technical analysis complete");

                Map<String, Object> content = new HashMap<>();
                content.put("requestId", requestId);
                content.put("findings", response.content());

                var techReplyMsg = Message.builder()
                    .topic("research.findings.technical")
                    .senderId(getAgentId()).receiverId(message.senderId())
                    .content(content).correlationId(message.id())
                    .build();
                getMessageDispatcher().sendTo(techReplyMsg);
            }).exceptionally(ex -> {
                log.error("❌ Error in technical analysis", ex);
                return null;
            });
        }

        @Behavior(type = BehaviorType.CYCLIC, interval = "15s", autoStart = true)
        public void reportStatus() {
            int count = taskCount.get();
            if (count > 0) log.info("🔧 Technical Researcher - Tasks completed: {}", count);
        }
    }

    @Agent(value = "market-researcher", type = "specialist",
                 capabilities = {"market-analysis", "trend-forecasting"})
    public static class MarketResearcher extends BaseAgent {

        private final LLMProvider llm;
        private final AtomicInteger taskCount = new AtomicInteger(0);

        public MarketResearcher(LLMProvider llm) {
            super("market-researcher", "Market Researcher");
            this.llm = llm;
        }

        @AgenorMessageHandler(value = "research.task.market", autoSubscribe = true)
        public void handleResearchTask(Message message) {
            taskCount.incrementAndGet();

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) message.content();
            String requestId = (String) data.get("requestId");
            String topic = (String) data.get("topic");

            if (requestId == null || requestId.isBlank()) { log.error("❌ Missing requestId"); return; }
            if (topic == null || topic.isBlank())         { log.error("❌ Missing topic");     return; }

            log.info("📈 Market researcher analyzing: {}", topic);

            FunctionDefinition marketTool = FunctionDefinition.builder("analyze_market_segment")
                .description("Analyze specific market segment dynamics")
                .stringParameter("segment", "Market segment to analyze", true)
                .enumParameter("metric", "Metric to focus on", true,
                    "size", "growth", "penetration", "competition")
                .build();

            LLMRequest request = LLMRequest.builder()
                .addMessage(LLMMessage.system("You are a market research analyst."))
                .addMessage(LLMMessage.user(String.format(
                    "Market analysis of: '%s'\nFocus on: market size, drivers, customer segments, " +
                    "barriers and opportunities.", topic)))
                .addFunction(marketTool)
                .temperature(0.6).maxTokens(700)
                .build();

            llm.chat(request).thenAccept(response -> {
                if (response.hasFunctionCalls()) log.info("📊 Using market analysis tools");

                String findings = response.content();
                if (findings == null || findings.isBlank()) {
                    findings = "Analysis could not be completed - no response from LLM";
                }
                log.info("✅ Market analysis complete");

                var marketReplyMsg = Message.builder()
                    .topic("research.findings.market")
                    .senderId(getAgentId()).receiverId(message.senderId())
                    .content(Map.of("requestId", requestId, "findings", findings))
                    .correlationId(message.id())
                    .build();
                getMessageDispatcher().sendTo(marketReplyMsg);

            }).exceptionally(ex -> {
                log.error("❌ Error in market analysis", ex);
                var marketErrMsg = Message.builder()
                    .topic("research.findings.market")
                    .senderId(getAgentId()).receiverId(message.senderId())
                    .content(Map.of("requestId", requestId,
                        "findings", "Error during analysis: " + ex.getMessage(), "error", true))
                    .correlationId(message.id())
                    .build();
                getMessageDispatcher().sendTo(marketErrMsg);
                return null;
            });
        }

        @Behavior(type = BehaviorType.CYCLIC, interval = "15s", autoStart = true)
        public void reportStatus() {
            int count = taskCount.get();
            if (count > 0) log.info("📈 Market Researcher - Tasks completed: {}", count);
        }
    }

    @Agent(value = "competitor-researcher", type = "specialist",
                 capabilities = {"competitive-intelligence", "swot-analysis"})
    public static class CompetitorResearcher extends BaseAgent {

        private final LLMProvider llm;
        private final AtomicInteger taskCount = new AtomicInteger(0);

        public CompetitorResearcher(LLMProvider llm) {
            super("competitor-researcher", "Competitor Researcher");
            this.llm = llm;
        }

        @AgenorMessageHandler(value = "research.task.competitor", autoSubscribe = true)
        public void handleResearchTask(Message message) {
            taskCount.incrementAndGet();

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) message.content();
            String requestId = (String) data.get("requestId");
            String topic = (String) data.get("topic");

            if (requestId == null || requestId.isBlank()) { log.error("❌ Missing requestId"); return; }
            if (topic == null || topic.isBlank())         { log.error("❌ Missing topic");     return; }

            log.info("🎯 Competitor researcher analyzing: {}", topic);

            FunctionDefinition compTool = FunctionDefinition.builder("analyze_competitor_strategy")
                .description("Deep dive into competitor strategy and positioning")
                .stringParameter("competitor", "Competitor name or type", true)
                .enumParameter("focus", "Analysis focus area", true,
                    "strategy", "products", "pricing", "marketing")
                .build();

            LLMRequest request = LLMRequest.builder()
                .addMessage(LLMMessage.system("You are a competitive intelligence expert."))
                .addMessage(LLMMessage.user(String.format(
                    "Competitive analysis of: '%s'\nFocus on: major players, competitive advantages, " +
                    "strategic threats, emerging competitors.", topic)))
                .addFunction(compTool)
                .temperature(0.6).maxTokens(700)
                .build();

            llm.chat(request).thenAccept(response -> {
                if (response.hasFunctionCalls()) log.info("🔍 Using competitive analysis tools");

                String findings = response.content();
                if (findings == null || findings.isBlank()) {
                    findings = "Analysis could not be completed - no response from LLM";
                }
                log.info("✅ Competitive analysis complete");

                var compReplyMsg = Message.builder()
                    .topic("research.findings.competitor")
                    .senderId(getAgentId()).receiverId(message.senderId())
                    .content(Map.of("requestId", requestId, "findings", findings))
                    .correlationId(message.id())
                    .build();
                getMessageDispatcher().sendTo(compReplyMsg);

            }).exceptionally(ex -> {
                log.error("❌ Error in competitor analysis", ex);
                var compErrMsg = Message.builder()
                    .topic("research.findings.competitor")
                    .senderId(getAgentId()).receiverId(message.senderId())
                    .content(Map.of("requestId", requestId,
                        "findings", "Error during analysis: " + ex.getMessage(), "error", true))
                    .correlationId(message.id())
                    .build();
                getMessageDispatcher().sendTo(compErrMsg);
                return null;
            });
        }

        @Behavior(type = BehaviorType.CYCLIC, interval = "15s", autoStart = true)
        public void reportStatus() {
            int count = taskCount.get();
            if (count > 0) log.info("🎯 Competitor Researcher - Tasks completed: {}", count);
        }
    }
}
