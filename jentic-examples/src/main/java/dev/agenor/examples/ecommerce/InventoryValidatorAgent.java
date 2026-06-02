package dev.agenor.examples.ecommerce;

import dev.agenor.core.*;
import dev.agenor.core.annotations.JenticAgent;
import dev.agenor.core.annotations.JenticMessageHandler;
import dev.agenor.runtime.agent.BaseAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

@JenticAgent(
    value = "inventory-validator",
    type = "Validator",
    capabilities = {"inventory-validation"}
)
public class InventoryValidatorAgent extends BaseAgent {

    private static final Logger log = LoggerFactory.getLogger(InventoryValidatorAgent.class);

    public InventoryValidatorAgent(AgentDirectory agentDirectory,
                                   BehaviorScheduler behaviorScheduler) {
        this.agentDirectory = agentDirectory;
        this.behaviorScheduler = behaviorScheduler;
    }

    @JenticMessageHandler("validate-inventory")
    public void handleValidateInventory(Message message) {
        List<OrderItem> items = message.getContent(List.class);

        log.info("📦 Validating inventory for {} items", items.size());
        simulateWork(150);

        // Simulate inventory check
        boolean valid = !items.isEmpty();

        String result = valid ? "VALID" : "INVALID";
        log.info("   Inventory validation result: {}", result);

        // Create reply map based on validation result
        Map<String, Object> replyData = valid
                ? Map.of("validator", "inventory", "valid", true)
                : Map.of("validator", "inventory", "valid", false, "reason", "Insufficient inventory");

        Message reply = message.reply(replyData)
                .topic("validation-result")
                .build();

        getMessageDispatcher().sendTo(reply);
    }

    private void simulateWork(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
