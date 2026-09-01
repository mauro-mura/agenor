package dev.agenor.runtime.annotation;

import dev.agenor.runtime.support.MethodHierarchy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import dev.agenor.core.annotations.AgenorMessageHandler;
import dev.agenor.core.annotations.Behavior;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.agenor.core.Agent;
import dev.agenor.core.Message;
import dev.agenor.core.MessageHandler;
import dev.agenor.core.messaging.TopicSubscriber;
import dev.agenor.core.spi.BehaviorAnnotationExtension;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.behavior.BaseBehavior;
import dev.agenor.runtime.behavior.CyclicBehavior;
import dev.agenor.runtime.behavior.OneShotBehavior;

/**
 * Processor for handling {@code @Behavior} and {@code @AgenorMessageHandler}
 * annotations on agent classes.
 *
 * <p>Handles {@code @AgenorMessageHandler} and the {@code @Behavior} types whose
 * implementation classes live in {@code agenor-runtime} (ONE_SHOT, CYCLIC, WAKER,
 * EVENT_DRIVEN, CUSTOM) directly — no optional-module dependency. Every other
 * {@code @Behavior} type is delegated to the {@link BehaviorAnnotationExtension} SPI,
 * implemented by {@code agenor-runtime-ext}; when that extension is absent, using one
 * of those types fails loudly with an {@link IllegalStateException} rather than being
 * silently skipped.
 *
 * @since 0.25.0
 */
public class AgentAnnotationProcessor {

    private static final Logger log = LoggerFactory.getLogger(AgentAnnotationProcessor.class);

    private final TopicSubscriber topicSubscriber;
    private final Optional<BehaviorAnnotationExtension> extension;

    public AgentAnnotationProcessor(TopicSubscriber topicSubscriber,
                                     Optional<BehaviorAnnotationExtension> extension) {
        this.topicSubscriber = topicSubscriber;
        this.extension = extension;
    }

    /**
     * Process all annotations on an agent
     */
    public void processAnnotations(Agent agent) {
        Class<?> agentClass = agent.getClass();

        log.debug("Processing annotations for agent: {} ({})", agent.getAgentName(), agentClass.getName());

        // Process behavior annotations
        processBehaviorAnnotations(agent, agentClass);

        // Process message handler annotations
        processMessageHandlerAnnotations(agent, agentClass);

        log.debug("Annotation processing completed for agent: {}", agent.getAgentName());
    }

    /**
     * Process @Behavior annotations.
     *
     * <p>The whole class hierarchy is scanned, so a behaviour declared on an abstract base
     * agent runs for its concrete subclasses too; each method is taken at its most-derived
     * declaration, so an override is registered once.
     */
    private void processBehaviorAnnotations(Agent agent, Class<?> agentClass) {
        for (Method method : MethodHierarchy.mostDerivedMethods(agentClass)) {
            Behavior behaviorAnnotation = method.getAnnotation(Behavior.class);

            if (behaviorAnnotation != null) {
                try {
                    createBehaviorFromAnnotation(agent, method, behaviorAnnotation);
                } catch (IllegalStateException e) {
                    // missing agenor-runtime-ext for an ext-only @Behavior type — fail
                    // loudly instead of the log-and-skip below
                    throw e;
                } catch (Exception e) {
                    log.error("Failed to create behavior from annotation for method: {}.{}",
                             agentClass.getName(), method.getName(), e);
                }
            }
        }
    }

    /**
     * Process @AgenorMessageHandler annotations.
     *
     * <p>Scanned over the whole class hierarchy, on the same terms as
     * {@link #processBehaviorAnnotations(Agent, Class)}.
     */
    private void processMessageHandlerAnnotations(Agent agent, Class<?> agentClass) {
        for (Method method : MethodHierarchy.mostDerivedMethods(agentClass)) {
            AgenorMessageHandler handlerAnnotation = method.getAnnotation(AgenorMessageHandler.class);

            if (handlerAnnotation != null && handlerAnnotation.autoSubscribe()) {
                rejectTopicPattern(handlerAnnotation.value(), agentClass, method);
                try {
                    createMessageHandlerFromAnnotation(agent, method, handlerAnnotation);
                } catch (Exception e) {
                    log.error("Failed to create message handler from annotation for method: {}.{}",
                             agentClass.getName(), method.getName(), e);
                }
            }
        }
    }

    /**
     * Rejects a topic that carries wildcard syntax.
     *
     * <p>Both delivery paths resolve a handler by exact map lookup — {@code deliverToTopic} in
     * the dispatcher and {@code BaseAgent.handleDirectMessage} — so {@code "orders.*"} matches
     * no topic at all. Until 0.29.0 the annotation's Javadoc advertised wildcards and its own
     * example used one, so this failed by delivering nothing and saying nothing. Failing at
     * registration costs a startup error; the alternative costs an afternoon.
     *
     * <p>Thrown rather than logged, and raised outside the per-handler {@code catch}: this is
     * not one handler that failed to build, it is an agent whose declared contract cannot be
     * honoured.
     *
     * @throws IllegalArgumentException if the topic contains {@code *} or {@code #}
     */
    private static void rejectTopicPattern(String topic, Class<?> agentClass, Method method) {
        if (topic == null || (topic.indexOf('*') < 0 && topic.indexOf('#') < 0)) {
            return;
        }
        throw new IllegalArgumentException(
                "@AgenorMessageHandler(\"" + topic + "\") on " + agentClass.getName() + "."
                        + method.getName() + " declares a topic pattern, and topics are matched "
                        + "exactly — this handler would never fire. Subscribe to an exact topic, "
                        + "or for pattern matching subscribe programmatically with a MessageFilter "
                        + "(TopicFilter.wildcard(\"" + topic + "\") in agenor-runtime-ext).");
    }

    /**
     * Create behavior from @Behavior annotation
     */
    private void createBehaviorFromAnnotation(Agent agent, Method method, Behavior annotation) {
        if (!annotation.autoStart()) {
            log.debug("Skipping auto-start for behavior method: {}", method.getName());
            return;
        }

        // Validate method signature
        if (!isValidBehaviorMethod(method)) {
            log.warn("Invalid behavior method signature: {}. Method should be public, non-static, and have no parameters",
                    method.getName());
            return;
        }

        method.setAccessible(true);

        dev.agenor.core.Behavior behavior = switch (annotation.type()) {
            case ONE_SHOT -> createOneShotBehavior(agent, method, annotation);
            case CYCLIC -> createCyclicBehavior(agent, method, annotation);
            default -> delegateToExtension(agent, method, annotation);
        };

        if (behavior != null) {
            if (behavior instanceof BaseBehavior baseBehavior) {
                baseBehavior.setAgent(agent);
            }

            agent.addBehavior(behavior);

            log.info("Added {} behavior '{}' to agent '{}'",
                    annotation.type().name().toLowerCase(),
                    method.getName(),
                    agent.getAgentName());
        }
    }

    /**
     * Delegates {@code @Behavior} types not handled directly (CONDITIONAL, THROTTLED,
     * BATCH, RETRY, SEQUENTIAL, PARALLEL, FSM) to the {@link BehaviorAnnotationExtension}
     * SPI, implemented by {@code agenor-runtime-ext}.
     */
    private dev.agenor.core.Behavior delegateToExtension(Agent agent, Method method, Behavior annotation) {
        BehaviorAnnotationExtension ext = extension.orElseThrow(() -> new IllegalStateException(
                "@Behavior(type=" + annotation.type() + ") requires agenor-runtime-ext on the classpath"));
        if (!ext.supports(annotation.type())) {
            throw new UnsupportedOperationException("Behavior type not supported: " + annotation.type());
        }
        return ext.createBehavior(agent, method, annotation);
    }

    /**
     * Create message handler from @AgenorMessageHandler annotation
     */
    private void createMessageHandlerFromAnnotation(Agent agent, Method method, AgenorMessageHandler annotation) {
        // Validate method signature
        if (!isValidMessageHandlerMethod(method)) {
            log.warn("Invalid message handler method signature: {}. Method should be public, non-static, and take a Message parameter",
                    method.getName());
            return;
        }

        method.setAccessible(true);
        String topic = annotation.value();

        MessageHandler handler = message -> invokeHandler(agent, method, message);

        var subscription = topicSubscriber.subscribeTopic(topic, handler);

        // Also wire the handler to direct (point-to-point) messages addressed to this
        // agent whose topic matches, so @AgenorMessageHandler works with sendTo()/receiverId
        // addressing, not just topic pub/sub (see BaseAgent#registerDirectTopicHandler).
        //
        // The Subscription is handed to the agent rather than discarded: it is what stopping
        // the agent releases. Keeping only subscriptionId() for a log line left the topic
        // subscription alive for the life of the process, which BaseAgent already avoids for
        // its own direct subscription.
        if (agent instanceof BaseAgent baseAgent) {
            baseAgent.registerDirectTopicHandler(topic, handler);
            baseAgent.registerTopicSubscription(subscription);
        }

        log.info("Subscribed agent '{}' to topic '{}' (method: {}, subscription: {})",
                agent.getAgentName(), topic, method.getName(), subscription.subscriptionId());
    }

    private dev.agenor.core.Behavior createOneShotBehavior(Agent agent, Method method, Behavior annotation) {
        String behaviorId = generateBehaviorId(agent, method);
        Duration initialDelay = parseOptionalDuration(annotation.initialDelay());

        return new OneShotBehavior(behaviorId) {
            @Override
            protected void action() {
                invokeMethod(agent, method);
            }

            @Override
            public Duration getInitialDelay() {
                return initialDelay;
            }
        };
    }

    private dev.agenor.core.Behavior createCyclicBehavior(Agent agent, Method method, Behavior annotation) {
        String behaviorId = generateBehaviorId(agent, method);
        Duration interval = parseDuration(annotation.interval());
        Duration initialDelay = parseOptionalDuration(annotation.initialDelay());

        return new CyclicBehavior(behaviorId, interval) {
            @Override
            public Duration getInitialDelay() {
                return initialDelay;
            }

            @Override
            protected void action() {
                invokeMethod(agent, method);
            }
        };
    }

    private void invokeMethod(Agent agent, Method method) {
        try {
            method.invoke(agent);
        } catch (Exception e) {
            log.error("Error invoking behavior method: {}.{}",
                     agent.getClass().getName(), method.getName(), e);
        }
    }

    private String generateBehaviorId(Agent agent, Method method) {
        return agent.getAgentId() + "." + method.getName();
    }

    /**
     * Parses an optional duration, where "absent" is a meaningful answer.
     *
     * <p>{@link #parseDuration} answers a blank string with one second, which is the right
     * default for {@code interval} and the wrong one for {@code initialDelay} — it would push
     * every behavior a second into the future for saying nothing.
     *
     * @param durationString the annotation value, possibly blank
     * @return the parsed duration, or null when the value is absent
     */
    private Duration parseOptionalDuration(String durationString) {
        if (durationString == null || durationString.isBlank()) {
            return null;
        }
        return parseDuration(durationString);
    }

    private Duration parseDuration(String durationString) {
        if (durationString == null || durationString.trim().isEmpty()) {
            return Duration.ofSeconds(1); // Default interval
        }

        try {
            durationString = durationString.trim().toLowerCase();

            if (durationString.endsWith("ms")) {
                long value = Long.parseLong(durationString.substring(0, durationString.length() - 2));
                return Duration.ofMillis(value);
            } else if (durationString.endsWith("s")) {
                long value = Long.parseLong(durationString.substring(0, durationString.length() - 1));
                return Duration.ofSeconds(value);
            } else if (durationString.endsWith("m")) {
                long value = Long.parseLong(durationString.substring(0, durationString.length() - 1));
                return Duration.ofMinutes(value);
            } else if (durationString.endsWith("min")) {
                long value = Long.parseLong(durationString.substring(0, durationString.length() - 3));
                return Duration.ofMinutes(value);
            } else if (durationString.endsWith("h")) {
                long value = Long.parseLong(durationString.substring(0, durationString.length() - 1));
                return Duration.ofHours(value);
            } else {
                // Try to parse as seconds
                long value = Long.parseLong(durationString);
                return Duration.ofSeconds(value);
            }
        } catch (NumberFormatException e) {
            log.warn("Invalid duration format: '{}', using 1 second default", durationString);
            return Duration.ofSeconds(1);
        }
    }

    private boolean isValidBehaviorMethod(Method method) {
        return Modifier.isPublic(method.getModifiers()) &&
               !Modifier.isStatic(method.getModifiers()) &&
               method.getParameterCount() == 0 &&
               (method.getReturnType() == void.class || method.getReturnType() == Void.class);
    }

    /**
     * Invokes an annotated handler and returns what the mailbox should wait on.
     *
     * <p>A {@code void} handler has finished when the method returns, so it yields an already
     * completed future — exactly as before. A handler that returns a {@link CompletionStage}
     * has not: its work is still running, and handing that stage back is what puts it inside
     * the delivery guarantees of ADR-033. The message is acknowledged when the stage completes
     * rather than when the method returns, the failure of an asynchronous chain reaches the
     * transport instead of vanishing, and the work counts against the mailbox's bound on
     * concurrent handlers.
     *
     * <p>A failure thrown out of the method is wrapped, as it always was. A failure carried by
     * a returned stage is passed through untouched: the handler built that future, and its own
     * exception says more than a wrapper would.
     */
    private CompletableFuture<Void> invokeHandler(Agent agent, Method method, Message message) {
        Object result;
        try {
            result = method.invoke(agent, message);
        } catch (InvocationTargetException e) {
            return CompletableFuture.failedFuture(
                    new RuntimeException("Error executing message handler: " + method.getName(),
                            e.getCause()));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(
                    new RuntimeException("Error executing message handler: " + method.getName(), e));
        }

        if (result instanceof CompletionStage<?> stage) {
            return stage.toCompletableFuture().thenApply(ignored -> null);
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * A handler method must be public, non-static, and take a single {@link Message}.
     *
     * <p>It may return {@code void}, or a {@link CompletionStage} for work that is not finished
     * when the method returns — see {@link #invokeHandler}. Any other return type is rejected:
     * the framework would have nothing to do with the value, and accepting it silently is how a
     * handler that meant to be asynchronous ends up outside the delivery guarantees.
     */
    private boolean isValidMessageHandlerMethod(Method method) {
        return Modifier.isPublic(method.getModifiers()) &&
               !Modifier.isStatic(method.getModifiers()) &&
               method.getParameterCount() == 1 &&
               method.getParameterTypes()[0] == Message.class &&
               (method.getReturnType() == void.class
                       || method.getReturnType() == Void.class
                       || CompletionStage.class.isAssignableFrom(method.getReturnType()));
    }
}
