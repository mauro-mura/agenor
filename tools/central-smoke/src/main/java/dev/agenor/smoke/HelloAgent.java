package dev.agenor.smoke;

import static dev.agenor.core.BehaviorType.CYCLIC;

import dev.agenor.core.Message;
import dev.agenor.core.annotations.AgenorMessageHandler;
import dev.agenor.core.annotations.Agent;
import dev.agenor.core.annotations.Behavior;
import dev.agenor.runtime.agent.BaseAgent;

/**
 * The agent from the README's "Your First Agent" section, copied verbatim below the
 * imports.
 *
 * <p>Keeping it identical is the point: if a rename or a package move breaks the
 * snippet a newcomer copies, this project stops compiling against the published
 * artifacts and the smoke test fails before it ever runs.
 */
@Agent("hello-agent")
public class HelloAgent extends BaseAgent {

    @Behavior(type = CYCLIC, interval = "5s")
    public void sayHello() {
        getMessageDispatcher().publish(Message.builder()
                .topic("greetings")
                .content("Hello from " + getAgentId())
                .build());
    }

    @AgenorMessageHandler("greetings")
    public void handleGreeting(Message message) {
        log.info("Received: {}", message.content());
    }
}
