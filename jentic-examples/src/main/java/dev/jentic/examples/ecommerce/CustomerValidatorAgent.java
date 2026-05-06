package dev.jentic.examples.ecommerce;

import dev.jentic.core.*;
import dev.jentic.core.annotations.JenticAgent;
import dev.jentic.core.annotations.JenticMessageHandler;
import dev.jentic.runtime.agent.BaseAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@JenticAgent(
    value = "customer-validator",
    type = "Validator",
    capabilities = {"customer-validation"}
)
public class CustomerValidatorAgent extends BaseAgent {

    private static final Logger log = LoggerFactory.getLogger(CustomerValidatorAgent.class);

    public CustomerValidatorAgent(AgentDirectory agentDirectory,
                                  BehaviorScheduler behaviorScheduler) {
        this.agentDirectory = agentDirectory;
    }

    @JenticMessageHandler("validate-customer")
    public void handleValidateCustomer(Message message) {
        String customerId = message.getContent(String.class);
        
        log.info("🔍 Validating customer: {}", customerId);
        simulateWork(100);
        
        boolean valid = customerId != null && !customerId.trim().isEmpty() 
                       && !customerId.equals("INVALID");
        
        String result = valid ? "VALID" : "INVALID";
        log.info("   Customer validation result: {}", result);

        // Create reply map based on validation result
        Map<String, Object> replyData = valid
                ? Map.of("validator", "customer", "valid", true)
                : Map.of("validator", "customer", "valid", false, "reason", "Invalid customer ID");

        // Reply with validation result
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