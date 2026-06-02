package dev.agenor.examples.ecommerce;

import dev.agenor.core.*;
import dev.agenor.core.annotations.JenticAgent;
import dev.agenor.core.annotations.JenticMessageHandler;
import dev.agenor.runtime.agent.BaseAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Map;

@JenticAgent(
    value = "payment-validator",
    type = "Validator",
    capabilities = {"payment-validation"}
)
public class PaymentValidatorAgent extends BaseAgent {

    private static final Logger log = LoggerFactory.getLogger(PaymentValidatorAgent.class);

    public PaymentValidatorAgent(AgentDirectory agentDirectory,
                                BehaviorScheduler behaviorScheduler) {
        this.agentDirectory = agentDirectory;
        this.behaviorScheduler = behaviorScheduler;
    }

    @JenticMessageHandler("validate-payment")
    public void handleValidatePayment(Message message) {
        BigDecimal amount = new BigDecimal(message.getContent(String.class));

        log.info("💳 Validating payment amount: ${}", amount);
        simulateWork(120);

        boolean valid = amount.compareTo(BigDecimal.ZERO) > 0
                       && amount.compareTo(new BigDecimal("10000")) < 0;

        String result = valid ? "VALID" : "INVALID";
        log.info("   Payment validation result: {}", result);

        // Create reply map based on validation result
        Map<String, Object> replyData = valid
                ? Map.of("validator", "payment", "valid", true)
                : Map.of("validator", "payment", "valid", false, "reason", "Invalid payment amount");

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
