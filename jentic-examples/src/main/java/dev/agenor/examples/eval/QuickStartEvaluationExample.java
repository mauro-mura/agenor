package dev.agenor.examples.eval;

import dev.agenor.core.Message;
import dev.agenor.core.annotations.Agent;
import dev.agenor.core.annotations.Behavior;
import dev.agenor.runtime.AgenorRuntime;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.tools.eval.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.agenor.core.BehaviorType.CYCLIC;

/**
 * Quick-start example for Agent Evaluation Framework.
 */
public class QuickStartEvaluationExample {

    public static void main(String[] args) {
        // 1. Create runtime
        AgenorRuntime runtime = AgenorRuntime.builder().build();
        runtime.start();

        // 2. Create runner
        ScenarioRunner runner = new ScenarioRunner(runtime);

        try {
            // 3. Define a simple scenario
            Scenario scenario = Scenario.builder("counter-agent-test")
                .description("Tests counter agent increments correctly")
                .timeout(Duration.ofSeconds(10))

                // Setup: create and register agent
                .setup(rt -> {
                    rt.registerAgent(new CounterAgent());
                })

                // Execute: start agent and send increment messages
                .execute(rt -> {
                	CounterAgent agent = (CounterAgent) rt.getAgents().stream()
                            .filter(a -> a.getAgentId().equals("counter-agent"))
                            .findFirst()
                            .orElseThrow();
                    agent.start();

                    // Send 5 increment messages
                    for (int i = 0; i < 5; i++) {
                        var incMsg = Message.builder()
                            .topic("counter.increment")
                            .content(1)
                            .build();
                        rt.getMessageDispatcher().publish(incMsg);
                    }

                    // Wait for processing
                    sleep(500);
                })

                // Verify: check results
                .verify(ctx -> {
                	CounterAgent agent = (CounterAgent) ctx.runtime().getAgents().stream()
                            .filter(a -> a.getAgentId().equals("counter-agent"))
                            .findFirst()
                            .orElse(null);

                    return List.of(
                        ctx.assertAgentRunning("counter-agent"),
                        ctx.assertComponentHealthy("runtime"),
                        ctx.assertMessageCountAtLeast(5),
                        ctx.assertCondition(
                            "counter-value",
                            agent.getCount() >= 5,
                            "Expected count >= 5, got: " + agent.getCount()
                        )
                    );
                })

                // Teardown: cleanup
                .teardown(rt -> {
                    rt.getAgents().forEach(a -> a.stop());
                })
                .build();

            // 4. Run scenario
            EvaluationResult result = runner.run(scenario);

            // 5. Print result
            System.out.println("\n" + result.format());

            // 6. Or use standard scenarios
            System.out.println("\n--- Running standard health check ---");
            EvaluationResult healthResult = runner.run(StandardScenarios.healthCheck());
            System.out.println(healthResult.format());

        } finally {
            runner.shutdown();
            runtime.stop();
        }
    }

    /**
     * Simple counter agent for testing.
     */
    @Agent(value = "counter-agent", type = "Processor")
    static class CounterAgent extends BaseAgent {

        private final AtomicInteger count = new AtomicInteger(0);

        public CounterAgent() {
            super("counter-agent", "Counter Agent");
        }

        @Override
        protected void onStart() {
            getMessageDispatcher().subscribeTopic("counter.increment", msg -> {
                int increment = msg.content() instanceof Integer i ? i : 1;
                count.addAndGet(increment);
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            });
        }

        @Behavior(type = CYCLIC, interval = "1s")
        public void logStatus() {
            System.out.println("Counter value: " + count.get());
        }

        public int getCount() {
            return count.get();
        }
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
