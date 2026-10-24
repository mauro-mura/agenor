package dev.agenor.examples.support.a2a;

import dev.agenor.core.Message;
import dev.agenor.core.MessageHandler;
import dev.agenor.core.dialogue.DialogueHandler;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.core.messaging.Subscription;
import dev.agenor.examples.support.model.SupportResponse;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.dialogue.DialogueCapability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The agent an A2A task reaches: it answers a {@code REQUEST} by running it through the support
 * pipeline.
 *
 * <p>{@code AgenorAgentExecutor} sends each A2A task to one agent as a {@code REQUEST} and waits for
 * its final reply (ADR-026). The support agents do not work that way: the router listens on the
 * {@code support.query} topic, and whichever agent handles the query publishes on
 * {@code support.response}. This agent joins the two: it publishes the request as a query, using
 * the task's conversation id as the correlation id, and answers the request with an
 * {@code INFORM} when the matching response arrives.
 *
 * <p>An application whose agents already answer a {@code REQUEST} needs none of this: point the
 * executor at the agent itself.
 */
public class SupportA2AGatewayAgent extends BaseAgent {

    /** The id {@link SupportA2AServer} routes A2A tasks to. */
    public static final String ID = "support-a2a-gateway";

    private static final Logger log = LoggerFactory.getLogger(SupportA2AGatewayAgent.class);

    private final DialogueCapability dialogue = new DialogueCapability(this);
    private final Duration timeout;

    /** Requests waiting for their support response, by correlation id. */
    private final Map<String, DialogueMessage> waiting = new ConcurrentHashMap<>();

    private Subscription responses;

    /**
     * @param timeout how long a request waits for its response before it is forgotten; the
     *                executor's own timeout fails the task in the meantime
     */
    public SupportA2AGatewayAgent(Duration timeout) {
        super(ID, "Support A2A gateway");
        this.timeout = timeout;
    }

    @Override
    protected void onStart() {
        responses = getMessageDispatcher().subscribeTopic("support.response", MessageHandler.sync(this::answer));
    }

    @Override
    protected void onStop() {
        if (responses != null) {
            responses.unsubscribe();
        }
    }

    @DialogueHandler(performatives = Performative.REQUEST)
    public void handle(DialogueMessage request) {
        String correlationId = request.conversationId();
        waiting.put(correlationId, request);
        CompletableFuture.delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .execute(() -> waiting.remove(correlationId));

        getMessageDispatcher().publish(Message.builder()
                .topic("support.query")
                .senderId(request.senderId())
                .correlationId(correlationId)
                .content(String.valueOf(request.content()))
                .build());
    }

    private void answer(Message message) {
        if (message.correlationId() == null || !(message.content() instanceof SupportResponse response)) {
            return;
        }
        DialogueMessage request = waiting.remove(message.correlationId());
        if (request != null) {
            log.debug("A2A task {} answered, intent {}", request.conversationId(), response.intent());
            dialogue.inform(request, response.text());
        }
    }
}
