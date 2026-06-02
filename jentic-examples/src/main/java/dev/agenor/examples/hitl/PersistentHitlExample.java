package dev.agenor.examples.hitl;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.agenor.adapters.persistence.hitl.HitlSchemaManager;
import dev.agenor.adapters.persistence.hitl.JdbcApprovalGate;
import dev.agenor.core.annotations.Agent;
import dev.agenor.core.hitl.ApprovalDecision;
import dev.agenor.core.hitl.ApprovalRequest;
import dev.agenor.runtime.JenticRuntime;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.behavior.CyclicBehavior;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;

/**
 * Demonstrates the persistent HITL approval queue (ADR-024).
 *
 * <p>A {@link PaymentAgent} requests approval for a payment. The approval request
 * is persisted in an H2 database. An {@link OperatorAgent} polls the gate via
 * {@link JdbcApprovalGate#getPendingRequests()} and approves the request.
 *
 * <p>Key properties demonstrated:
 * <ul>
 *   <li>The approval request is visible via {@link JdbcApprovalGate#getPendingRequests()}
 *       from any JVM sharing the same DB.</li>
 *   <li>The DB row persists across JVM restarts (use {@link JdbcApprovalGate#recoverExpired()}
 *       on startup to mark stale rows as EXPIRED).</li>
 *   <li>The {@link JenticRuntime} accepts the gate via {@code .approvalGate(gate)} —
 *       no in-memory {@code InMemoryApprovalGate} is involved.</li>
 * </ul>
 *
 * <p>Run with: {@code mvn exec:java -pl agenor-examples
 * -Dexec.mainClass="dev.agenor.examples.hitl.PersistentHitlExample"}
 *
 * @since 0.23.0
 */
public class PersistentHitlExample {

    private static final Logger log = LoggerFactory.getLogger(PersistentHitlExample.class);

    public static void main(String[] args) throws Exception {
        // --- Set up in-process H2 database (drop-in replacement for Postgres in demos) ---
        var cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:h2:mem:persistent_hitl_demo;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        cfg.setUsername("sa");
        cfg.setPassword("");
        cfg.setMaximumPoolSize(5);
        var dataSource = new HikariDataSource(cfg);

        new HitlSchemaManager(dataSource, "classpath:db/migration/agenor-hitl").migrate();

        var gate = new JdbcApprovalGate(dataSource, "jdbc:h2:mem:...");
        gate.recoverExpired();  // no-op here, but would mark stale rows on a real restart

        var done = new CountDownLatch(1);

        // --- Build runtime wired with the JDBC gate ---
        var runtime = JenticRuntime.builder()
                .withDefaultConfig()
                .approvalGate(gate)
                .build();

        runtime.registerAgent(new PaymentAgent(gate, done));
        runtime.registerAgent(new OperatorAgent(gate, done));

        runtime.start().join();

        log.info("Waiting for HITL workflow to complete...");
        done.await();

        runtime.stop().join();
        gate.close();
        dataSource.close();
        log.info("PersistentHitlExample complete.");
    }

    // -------------------------------------------------------------------------
    // PaymentAgent: submits a payment for approval in onStart()
    // -------------------------------------------------------------------------

    @Agent(value = "payment-agent")
    static class PaymentAgent extends BaseAgent {

        private final JdbcApprovalGate gate;
        private final CountDownLatch done;

        PaymentAgent(JdbcApprovalGate gate, CountDownLatch done) {
            super("payment-agent");
            this.gate = gate;
            this.done = done;
        }

        @Override
        protected void onStart() {
            var request = ApprovalRequest.of(
                    "payment-agent",
                    "transfer-funds",
                    "€10,000 to vendor ACME Corp",
                    Duration.ofMinutes(5));

            log.info("Requesting approval for: {} (requestId={})",
                    request.action(), request.requestId());

            var future = gate.requestApproval(request);

            // Blocks the virtual thread until approval or timeout
            var decision = future.join();

            switch (decision) {
                case ApprovalDecision.Approved a ->
                    log.info("Payment APPROVED — executing transfer");
                case ApprovalDecision.Rejected r ->
                    log.warn("Payment REJECTED: {}", r.reason());
                case ApprovalDecision.Modified m ->
                    log.info("Payment MODIFIED — executing with: {}", m.newPayload());
            }

            done.countDown();
        }
    }

    // -------------------------------------------------------------------------
    // OperatorAgent: polls pending requests and approves them
    // -------------------------------------------------------------------------

    @Agent(value = "operator-agent")
    static class OperatorAgent extends BaseAgent {

        private final JdbcApprovalGate gate;
        private final CountDownLatch done;

        OperatorAgent(JdbcApprovalGate gate, CountDownLatch done) {
            super("operator-agent");
            this.gate = gate;
            this.done = done;
        }

        @Override
        protected void onStart() {
            addBehavior(CyclicBehavior.from("poll-approvals", Duration.ofMillis(500),
                    this::pollAndApprove));
        }

        private void pollAndApprove() {
            if (done.getCount() == 0) return;

            var pending = gate.getPendingRequests();
            for (var req : pending) {
                log.info("Operator approving request: {} (action={})",
                        req.requestId(), req.action());
                gate.submit(req.requestId(), new ApprovalDecision.Approved());
            }
        }
    }
}
