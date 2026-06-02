package dev.agenor.examples.jdbc;

import dev.agenor.adapters.persistence.directory.JdbcAgentDirectory;
import dev.agenor.adapters.persistence.directory.JdbcDirectoryConfig;
import dev.agenor.core.AgentQuery;
import dev.agenor.core.Message;
import dev.agenor.core.PageRequest;
import dev.agenor.core.annotations.AgenorMessageHandler;
import dev.agenor.core.annotations.Agent;
import dev.agenor.core.annotations.Behavior;
import dev.agenor.runtime.AgenorRuntime;
import dev.agenor.runtime.agent.BaseAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static dev.agenor.core.BehaviorType.ONE_SHOT;

/**
 * JDBC Agent Directory Example — demonstrates persistent agent registration with a
 * relational database (ADR-022, ADR-023).
 *
 * <p>This example uses an in-process H2 database so it runs without any external
 * infrastructure. In production, replace the JDBC URL with a PostgreSQL or MySQL URL.
 *
 * <p><b>What this example demonstrates:</b>
 * <ol>
 *   <li><b>JDBC-backed directory</b>: {@link JdbcAgentDirectory} (backed by H2) stores agent
 *       registrations, capabilities, and endpoints across restarts.</li>
 *   <li><b>Mixed backend</b>: Registry, discovery, and resolver capabilities are JDBC-backed;
 *       presence (heartbeat / liveness) falls back to the default in-memory implementation —
 *       exactly as documented in ADR-023 and the {@code AgentPresence} Javadoc.</li>
 *   <li><b>Discovery via capabilities</b>: after startup, the orchestrator agent discovers
 *       worker agents by querying the JDBC-backed directory for agents with the
 *       {@code "data-processing"} capability.</li>
 *   <li><b>Schema management</b>: Flyway runs {@code V1__create_agent_directory.sql}
 *       automatically on first start — no manual DDL required.</li>
 * </ol>
 *
 * <p>Run:
 * <pre>
 *   mvn exec:java -pl agenor-examples \
 *       -Dexec.mainClass="dev.agenor.examples.jdbc.JdbcDirectoryExample"
 * </pre>
 *
 * @since 0.22.0
 */
public class JdbcDirectoryExample {

    private static final Logger log = LoggerFactory.getLogger(JdbcDirectoryExample.class);

    public static void main(String[] args) throws InterruptedException {

        log.info("=== JDBC Agent Directory Example ===");

        // In production: JdbcDirectoryConfig.of("jdbc:postgresql://host:5432/db", user, pass)
        var config = JdbcDirectoryConfig.of(
                "jdbc:h2:mem:agenor_example;DB_CLOSE_DELAY=-1",
                "sa", "");

        try (var jdbcDirectory = JdbcAgentDirectory.create(config)) {

            AgenorRuntime runtime = AgenorRuntime.builder()
                    .agentRegistry(jdbcDirectory.registry())
                    .agentDiscovery(jdbcDirectory.discovery())
                    .agentResolver(jdbcDirectory.resolver())
                    // presence falls back to in-memory (see ADR-023)
                    .build();

            runtime.registerAgent(new OrchestratorAgent(jdbcDirectory));
            runtime.registerAgent(new DataWorkerAgent("worker-alpha"));
            runtime.registerAgent(new DataWorkerAgent("worker-beta"));

            runtime.start().join();
            log.info("Runtime started — {} agent(s) running", runtime.getAgents().size());

            Thread.sleep(3_000);

            // After startup: query the JDBC directory directly (not via the runtime)
            log.info("\n--- JDBC Directory contents after startup ---");
            var allAgents = jdbcDirectory.discovery()
                    .findAgents(AgentQuery.builder().build(), PageRequest.first(50))
                    .join();
            allAgents.content().forEach(d -> log.info("  {} | type={} | status={} | caps={}",
                    d.agentId(), d.agentType(), d.status(), d.capabilities()));

            log.info("--- End of directory listing ---\n");

            Thread.sleep(2_000);

            runtime.stop().join();

        } // jdbcDirectory.close() releases HikariCP pool

        log.info("=== Example completed ===");
    }

    // -------------------------------------------------------------------------
    // Agents
    // -------------------------------------------------------------------------

    /**
     * Orchestrator: at startup queries the JDBC directory for workers and publishes
     * a task to each one by topic.
     */
    @Agent(value = "orchestrator",
                 type = "orchestrator",
                 capabilities = {"orchestration"},
                 autoStart = true)
    static class OrchestratorAgent extends BaseAgent {

        private final JdbcAgentDirectory directory;

        OrchestratorAgent(JdbcAgentDirectory directory) {
            super("orchestrator", "Orchestrator");
            this.directory = directory;
        }

        @Behavior(type = ONE_SHOT, autoStart = true)
        public void discoverAndDispatch() {
            var workers = directory.discovery()
                    .findAgents(AgentQuery.byType("data-worker"), PageRequest.first(10))
                    .join()
                    .content();

            log.info("[Orchestrator] Found {} worker(s) in JDBC directory", workers.size());
            workers.forEach(w -> {
                log.info("[Orchestrator] → dispatching task to {}", w.agentId());
                getMessageDispatcher().publish(
                        Message.builder()
                                .topic("tasks.process")
                                .senderId(getAgentId())
                                .content("Process batch for " + w.agentId())
                                .build());
            });
        }

        @AgenorMessageHandler("tasks.result")
        public void onResult(Message msg) {
            log.info("[Orchestrator] Result received from {}: {}", msg.senderId(), msg.content());
        }
    }

    /**
     * Worker: processes data tasks and publishes results back.
     */
    @Agent(type = "data-worker",
                 capabilities = {"data-processing"},
                 autoStart = true)
    static class DataWorkerAgent extends BaseAgent {

        DataWorkerAgent(String agentId) {
            super(agentId, "Data Worker " + agentId);
        }

        @AgenorMessageHandler("tasks.process")
        public void handleTask(Message msg) {
            log.info("[{}] Processing: {}", getAgentId(), msg.content());
            getMessageDispatcher().publish(
                    Message.builder()
                            .topic("tasks.result")
                            .senderId(getAgentId())
                            .content("Done: " + msg.content())
                            .build());
        }
    }
}
