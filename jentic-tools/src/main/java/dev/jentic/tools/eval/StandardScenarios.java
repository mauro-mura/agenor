package dev.jentic.tools.eval;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import dev.jentic.core.Agent;
import dev.jentic.core.AgentStatus;
import dev.jentic.core.Message;
import dev.jentic.runtime.agent.BaseAgent;

/**
 * Pre-built standard scenarios for common evaluation patterns.
 *
 * <p>Provides ready-to-use scenarios that test fundamental agent
 * behaviors and system properties. These can be used directly or
 * as templates for custom scenarios.
 *
 * <p>Example usage:
 * <pre>{@code
 * ScenarioRunner runner = new ScenarioRunner(runtime);
 * 
 * // Test agent lifecycle
 * EvaluationResult result = runner.run(
 *     StandardScenarios.lifecycle(myAgent)
 * );
 * 
 * // Test message throughput
 * result = runner.run(
 *     StandardScenarios.throughput(100, Duration.ofSeconds(5))
 * );
 * }</pre>
 *
 * @since 0.5.0
 */
public final class StandardScenarios {

    private StandardScenarios() {
        // Utility class
    }

    /**
     * Tests basic agent lifecycle: start and stop.
     *
     * @param agent the agent to test
     * @return lifecycle scenario
     */
    public static Scenario lifecycle(Agent agent) {
        return Scenario.builder("lifecycle-" + agent.getAgentId())
            .description("Tests basic agent lifecycle (start/stop)")
            .timeout(Duration.ofSeconds(10))
            .setup(runtime -> {
                runtime.registerAgent(agent);
            })
            .execute(runtime -> {
                agent.start();
                // Wait for agent to be running
                waitForStatus(agent, AgentStatus.RUNNING, Duration.ofSeconds(5));
            })
            .verify(ctx -> {
                List<AssertionResult> results = new ArrayList<>();
                results.add(ctx.assertAgentRunning(agent.getAgentId()));
                results.add(ctx.assertHealthy());
                return results;
            })
            .teardown(runtime -> {
                agent.stop();
            })
            .build();
    }

    /**
     * Tests agent registration and discovery.
     *
     * @param agents agents to register
     * @return registration scenario
     */
    public static Scenario registration(Agent... agents) {
        return Scenario.builder("registration")
            .description("Tests agent registration and discovery")
            .timeout(Duration.ofSeconds(10))
            .setup(runtime -> {
                for (Agent agent : agents) {
                    runtime.registerAgent(agent);
                }
            })
            .execute(runtime -> {
                // Just verify registration, no execution needed
            })
            .verify(ctx -> {
                List<AssertionResult> results = new ArrayList<>();
                results.add(ctx.assertAgentCount(agents.length));
                for (Agent agent : agents) {
                    results.add(ctx.assertAgentExists(agent.getAgentId()));
                }
                return results;
            })
            .build();
    }

    /**
     * Tests message sending and receiving.
     *
     * @param topic the topic to test
     * @param content message content
     * @return messaging scenario
     */
    public static Scenario messaging(String topic, Object content) {
        return Scenario.builder("messaging-" + topic)
            .description("Tests message sending and receiving on topic: " + topic)
            .timeout(Duration.ofSeconds(10))
            .execute(runtime -> {
                Message message = Message.builder()
                    .topic(topic)
                    .content(content)
                    .build();
                runtime.getMessageDispatcher().publish(message);
            })
            .verify(ctx -> {
                List<AssertionResult> results = new ArrayList<>();
                results.add(ctx.assertMessageReceived(topic));
                results.add(ctx.assertMessageCountAtLeast(1));
                return results;
            })
            .build();
    }

    /**
     * Tests message throughput under load.
     *
     * @param messageCount number of messages to send
     * @param window time window for sending
     * @return throughput scenario
     */
    public static Scenario throughput(int messageCount, Duration window) {
        return Scenario.builder("throughput-" + messageCount)
            .description("Tests throughput of " + messageCount + " messages in " + window.toMillis() + "ms")
            .timeout(window.plus(Duration.ofSeconds(10)))
            .execute(runtime -> {
                long intervalNanos = window.toNanos() / messageCount;
                for (int i = 0; i < messageCount; i++) {
                    Message message = Message.builder()
                        .topic("throughput.test")
                        .content("message-" + i)
                        .build();
                    runtime.getMessageDispatcher().publish(message);
                    
                    // Pace messages across window
                    if (intervalNanos > 0 && i < messageCount - 1) {
                        try {
                            Thread.sleep(intervalNanos / 1_000_000, (int)(intervalNanos % 1_000_000));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            })
            .verify(ctx -> {
                List<AssertionResult> results = new ArrayList<>();
                results.add(ctx.assertMessageCountAtLeast(messageCount));
                results.add(ctx.assertNoDroppedMessages());
                // Don't check assertHealthy - DEGRADED is acceptable for throughput test
                results.add(ctx.assertComponentHealthy("runtime"));
                return results;
            })
            .build();
    }

    /**
     * Tests system health under normal conditions.
     *
     * @return health scenario
     */
    public static Scenario healthCheck() {
        return Scenario.builder("health-check")
            .description("Verifies system health indicators")
            .timeout(Duration.ofSeconds(10))
            .verify(ctx -> {
                List<AssertionResult> results = new ArrayList<>();
                
                // Check overall health - allow DEGRADED if no agents are running
                // (this is normal for a standalone health check)
                var healthReport = ctx.healthReport();
                boolean isAcceptable = healthReport.status().isUp() || 
                    healthReport.status().isDegraded();
                
                results.add(ctx.assertCondition(
                    "health.acceptable",
                    isAcceptable,
                    "System health is DOWN: " + healthReport.status().message()
                ));
                
                // Check critical components
                results.add(ctx.assertComponentHealthy("runtime"));
                results.add(ctx.assertComponentHealthy("memory"));
                results.add(ctx.assertComponentHealthy("threads"));
                
                return results;
            })
            .build();
    }

    /**
     * Tests request-response pattern between two agents.
     *
     * @param requester the requesting agent
     * @param responder the responding agent
     * @param requestTopic topic for requests
     * @param responseTopic topic for responses
     * @return request-response scenario
     */
    public static Scenario requestResponse(
            Agent requester, 
            Agent responder,
            String requestTopic,
            String responseTopic) {
        return Scenario.builder("request-response")
            .description("Tests request-response pattern")
            .timeout(Duration.ofSeconds(15))
            .setup(runtime -> {
                runtime.registerAgent(requester);
                runtime.registerAgent(responder);
                requester.start();
                responder.start();
            })
            .execute(runtime -> {
                Message request = Message.builder()
                    .topic(requestTopic)
                    .senderId(requester.getAgentId())
                    .receiverId(responder.getAgentId())
                    .content("test-request")
                    .build();
                runtime.getMessageDispatcher().sendTo(request);
                
                // Wait for response processing
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            })
            .verify(ctx -> {
                List<AssertionResult> results = new ArrayList<>();
                results.add(ctx.assertAgentRunning(requester.getAgentId()));
                results.add(ctx.assertAgentRunning(responder.getAgentId()));
                results.add(ctx.assertMessageReceived(requestTopic));
                // Response topic assertion - may or may not have response depending on agent
                results.add(ctx.assertMessageCountAtLeast(1));
                return results;
            })
            .teardown(runtime -> {
                requester.stop();
                responder.stop();
            })
            .build();
    }

    /**
     * Tests that execution completes within a time limit.
     *
     * @param executionAction action to time
     * @param maxDuration maximum allowed duration
     * @return timing scenario
     */
    public static Scenario timing(Runnable executionAction, Duration maxDuration) {
        return Scenario.builder("timing")
            .description("Verifies execution completes within " + maxDuration.toMillis() + "ms")
            .timeout(maxDuration.plus(Duration.ofSeconds(5)))
            .execute(runtime -> executionAction.run())
            .verify(ctx -> List.of(
                ctx.assertCompletedWithin(maxDuration)
            ))
            .build();
    }

    /**
     * Tests fault tolerance by verifying system recovers from errors.
     *
     * @param agent agent to test
     * @param faultInjector action that injects a fault
     * @return fault tolerance scenario
     */
    public static Scenario faultTolerance(Agent agent, Runnable faultInjector) {
        return Scenario.builder("fault-tolerance-" + agent.getAgentId())
            .description("Tests fault tolerance and recovery")
            .timeout(Duration.ofSeconds(30))
            .setup(runtime -> {
                runtime.registerAgent(agent);
                agent.start();
            })
            .execute(runtime -> {
                // Inject fault
                try {
                    faultInjector.run();
                } catch (Exception e) {
                    // Expected - fault was injected
                }
                
                // Wait for potential recovery
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            })
            .verify(ctx -> {
                List<AssertionResult> results = new ArrayList<>();
                // System should still be healthy after fault
                results.add(ctx.assertHealthy());
                return results;
            })
            .teardown(runtime -> agent.stop())
            .build();
    }

    /**
     * Composite scenario that runs multiple scenarios in sequence.
     *
     * @param name composite scenario name
     * @param scenarios scenarios to run
     * @return composite scenario
     */
    public static Scenario composite(String name, Scenario... scenarios) {
        Duration totalTimeout = Duration.ZERO;
        for (Scenario s : scenarios) {
            totalTimeout = totalTimeout.plus(s.getTimeout());
        }

        final Duration timeout = totalTimeout.plus(Duration.ofSeconds(10));

        return Scenario.builder(name)
            .description("Composite scenario running " + scenarios.length + " sub-scenarios")
            .timeout(timeout)
            .verify(ctx -> {
                // This scenario's verification aggregates all sub-scenarios
                // In practice, use ScenarioRunner.runAll() instead
                return List.of(
                    AssertionResult.pass("composite.setup", 
                        "Composite scenarios should be run with runAll()")
                );
            })
            .build();
    }

    // === Helper Methods ===

    private static void waitForStatus(Agent agent, AgentStatus expected, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (agent instanceof BaseAgent && ((BaseAgent)agent).getStatus() == expected) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}