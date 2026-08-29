package dev.agenor.runtime.dialogue;

import dev.agenor.core.Agent;
import dev.agenor.core.LifecycleHooks;
import dev.agenor.core.Message;
import dev.agenor.core.MessageHandler;
import dev.agenor.core.messaging.MessageDispatcher;
import dev.agenor.core.messaging.Subscription;
import dev.agenor.core.dialogue.CommitmentTracker;
import dev.agenor.core.dialogue.Conversation;
import dev.agenor.core.dialogue.ConversationManager;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.dialogue.protocol.ProtocolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Provides dialogue capabilities to agents via composition.
 *
 * <p>Usage — an agent implementing {@link LifecycleHooks} (as {@code BaseAgent} does) needs
 * no lifecycle wiring at all: the capability registers itself on construction.
 *
 * <pre>{@code
 * public class MyAgent extends BaseAgent {
 *     private final DialogueCapability dialogue = new DialogueCapability(this);
 *
 *     @DialogueHandler(performatives = REQUEST)
 *     public void handleRequest(DialogueMessage msg) {
 *         dialogue.reply(msg, Performative.AGREE, "OK");
 *     }
 * }
 * }</pre>
 *
 * <p>An agent implementing {@link Agent} directly, without {@link LifecycleHooks}, must do
 * the wiring itself: call {@link #initialize()} from its {@code start()} and
 * {@link #shutdown()} from its {@code stop()}. Skipping {@code shutdown()} leaks the
 * agent's recipient subscription and its retention sweep for the lifetime of the process.
 *
 * <p>Conversation state is <strong>per-agent</strong>: each capability owns its own
 * {@link ConversationManager}, and nothing is shared between agents beyond the transport.
 *
 * <p>While initialized, a background sweep drops terminated conversations and commitments
 * past their retention window and marks overdue commitments as
 * {@link dev.agenor.core.dialogue.CommitmentState#VIOLATED} — no manual
 * {@link CommitmentTracker#checkViolations()} loop is needed. Detection is therefore delayed
 * by up to one sweep interval (default one minute); shorten it via {@link Builder#sweepInterval}.
 *
 * <p>Collaborators can be replaced through {@link #builder(Agent)} — see
 * {@link ConversationManagerFactory}.
 *
 * @since 0.5.0
 */
public class DialogueCapability {

    private static final Logger log = LoggerFactory.getLogger(DialogueCapability.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /** How long terminated conversations and commitments are kept before being swept. */
    static final Duration DEFAULT_RETENTION = Duration.ofMinutes(5);

    /** How often the sweep runs. */
    static final Duration DEFAULT_SWEEP_INTERVAL = Duration.ofMinutes(1);

    private final Agent agent;
    private final DialogueHandlerRegistry handlerRegistry;
    private final ConversationManagerFactory conversationManagerFactory;
    private final ProtocolRegistry protocolRegistry;
    private final CommitmentTracker commitmentTracker;
    private final Duration retention;
    private final Duration sweepInterval;

    private volatile ConversationManager conversationManager;
    private Subscription subscription;
    private boolean attachedToMailbox;
    private ScheduledExecutorService reaper;

    /**
     * Creates a dialogue capability for the given agent, with the built-in protocols and an
     * in-memory conversation manager.
     *
     * <p><strong>Invariant — this constructor must not dereference {@code agent}</strong>
     * beyond the {@code instanceof} check below. The idiomatic call site is a field
     * initializer ({@code private final DialogueCapability dialogue = new DialogueCapability(this)}),
     * which runs while the agent is still being constructed: the runtime has not injected the
     * {@code MessageDispatcher} yet, and no subclass constructor body has executed, so
     * {@code getAgentId()} can legitimately still return null. Everything that reads from the
     * agent is deferred to {@link #initialize()}.
     *
     * @param agent the agent this capability belongs to
     */
    public DialogueCapability(Agent agent) {
        this(agent, DefaultConversationManager::new, new ProtocolRegistry(),
            new DefaultCommitmentTracker(), DEFAULT_RETENTION, DEFAULT_SWEEP_INTERVAL);
    }

    private DialogueCapability(
            Agent agent,
            ConversationManagerFactory conversationManagerFactory,
            ProtocolRegistry protocolRegistry,
            CommitmentTracker commitmentTracker,
            Duration retention,
            Duration sweepInterval) {
        this.agent = Objects.requireNonNull(agent, "agent cannot be null");
        this.conversationManagerFactory =
            Objects.requireNonNull(conversationManagerFactory, "conversationManagerFactory cannot be null");
        this.protocolRegistry = Objects.requireNonNull(protocolRegistry, "protocolRegistry cannot be null");
        this.commitmentTracker = Objects.requireNonNull(commitmentTracker, "commitmentTracker cannot be null");
        this.retention = Objects.requireNonNull(retention, "retention cannot be null");
        this.sweepInterval = Objects.requireNonNull(sweepInterval, "sweepInterval cannot be null");
        this.handlerRegistry = new DialogueHandlerRegistry();

        if (agent instanceof LifecycleHooks hooks) {
            hooks.onStartHook(this::initialize);
            hooks.onStopHook(this::shutdown);
        } else {
            // Deliberately the class name, not getAgentId(): see the invariant above.
            log.warn("{} does not implement LifecycleHooks: call dialogue.initialize() from the "
                    + "agent's start() and dialogue.shutdown() from its stop(). Without shutdown() "
                    + "the recipient subscription and the retention sweep leak for the lifetime "
                    + "of the process.",
                agent.getClass().getName());
        }
    }

    /**
     * Creates a builder for a capability with custom collaborators (conversation manager,
     * protocol registry, commitment tracker, retention window).
     *
     * @param agent the agent this capability belongs to
     * @return a new builder
     * @since 0.26.0
     */
    public static Builder builder(Agent agent) {
        return new Builder(agent);
    }

    /**
     * Initializes dialogue capabilities, resolving the transport from the agent itself.
     *
     * <p>Called automatically on agent start when the agent implements {@link LifecycleHooks};
     * agents that do not must call it from their own {@code start()}. Idempotent: a second
     * call is a no-op, so mixing automatic and manual wiring is safe.
     *
     * @throws IllegalStateException if the agent has no message dispatcher yet
     * @since 0.26.0
     */
    public void initialize() {
        if (conversationManager != null) {
            reattachToMailboxIfNeeded();
            return;
        }
        MessageDispatcher dispatcher = agent.getMessageDispatcher();
        if (dispatcher == null) {
            throw new IllegalStateException(
                "Cannot initialize dialogue for agent " + agent.getAgentId()
                    + ": no MessageDispatcher available. Register the agent with AgenorRuntime "
                    + "before starting it.");
        }
        doInitialize(dispatcher);
    }

    private synchronized void doInitialize(MessageDispatcher dispatcher) {
        if (conversationManager != null) {
            reattachToMailboxIfNeeded();
            return; // also protects handlerRegistry.scan() from double-registration
        }
        var manager = conversationManagerFactory.create(
            agent.getAgentId(),
            dispatcher,
            protocolRegistry,
            commitmentTracker
        );
        handlerRegistry.scan(agent);
        subscription = subscribeInbound(dispatcher);
        this.conversationManager = manager;
        startReaper();
        log.info("Dialogue capability initialized for agent {} with {} handlers",
            agent.getAgentId(), handlerRegistry.size());
    }

    /**
     * Attaches this capability to the agent's inbound path.
     *
     * <p>Where the agent owns a mailbox, dialogue is one of its consumers: the mailbox holds
     * the agent's only persistent recipient subscription and routes each message to exactly
     * one consumer (ADR-032). An agent that is not a {@link BaseAgent} has no mailbox, and
     * still needs its own subscription — which is also why
     * {@link #handleIncomingMessage(Message)} keeps its own classifier guard.
     *
     * @param dispatcher the dispatcher to subscribe to when there is no mailbox
     * @return the handle that detaches this capability again
     */
    private Subscription subscribeInbound(MessageDispatcher dispatcher) {
        var handler = MessageHandler.sync(this::handleIncomingMessage);
        if (agent instanceof BaseAgent base) {
            var box = base.mailbox();
            if (box.isPresent()) {
                attachedToMailbox = true;
                return box.get().registerDialogueConsumer(handler);
            }
        }
        attachedToMailbox = false;
        return dispatcher.subscribeRecipient(agent.getAgentId(), handler);
    }

    /**
     * Moves an existing dispatcher subscription onto the agent's mailbox once one exists.
     *
     * <p>Covers initialization ahead of the agent's own start, which the documentation calls
     * safe: at that point a {@code BaseAgent} has no mailbox yet, so the first attachment goes
     * to the dispatcher. Leaving it there would put the agent back where ADR-032 found it —
     * two subscriptions on one channel, with dialogue traffic routed to the direct path
     * because the mailbox has no dialogue consumer to hand it to.
     */
    private synchronized void reattachToMailboxIfNeeded() {
        if (attachedToMailbox || !(agent instanceof BaseAgent base)) {
            return;
        }
        var box = base.mailbox();
        if (box.isEmpty()) {
            return;
        }
        if (subscription != null) {
            subscription.unsubscribe();
        }
        subscription = box.get().registerDialogueConsumer(
            MessageHandler.sync(this::handleIncomingMessage));
        attachedToMailbox = true;
        log.debug("Dialogue for agent {} moved onto the agent's mailbox", agent.getAgentId());
    }

    /**
     * @return the conversation manager, failing with an actionable message when the
     *         capability was never initialized
     */
    private ConversationManager requireInitialized() {
        var manager = conversationManager;
        if (manager == null) {
            throw new IllegalStateException(
                "DialogueCapability not initialized for agent " + agent.getAgentId()
                    + " — call initialize() from your agent's start(), or implement "
                    + "LifecycleHooks (BaseAgent already does) for automatic wiring");
        }
        return manager;
    }

    /**
     * Shuts down dialogue capabilities, cancelling the recipient subscription and
     * stopping the retention sweep.
     *
     * <p>Called automatically on agent stop when the agent implements {@link LifecycleHooks};
     * agents that do not must call it from their own {@code stop()}. Conversation state does
     * not survive: a subsequent {@link #initialize()} starts from a fresh manager, which is
     * what an agent restart needs since the old subscription is gone.
     *
     * @since 0.20.0
     */
    public void shutdown() {
        if (subscription != null) {
            subscription.unsubscribe();
            subscription = null;
        }
        attachedToMailbox = false;
        stopReaper();
        conversationManager = null;
        log.debug("Dialogue capability shut down for agent {}", agent.getAgentId());
    }

    /**
     * Starts the periodic sweep that drops terminated conversations and commitments and
     * marks overdue commitments as violated.
     *
     * <p>Both maps are keyed by identifiers that are never reused, so nothing removes an
     * entry once a dialogue ends — without this sweep they grow for the whole lifetime of
     * the agent. The thread is a daemon so that an agent which forgets {@link #shutdown()}
     * still lets the JVM exit.
     */
    private void startReaper() {
        if (isReaperRunning()) {
            return; // initialize() called twice: never start a second sweep thread
        }
        reaper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dialogue-reaper-" + agent.getAgentId());
            t.setDaemon(true);
            return t;
        });
        reaper.scheduleAtFixedRate(
            this::sweep,
            sweepInterval.toMillis(),
            sweepInterval.toMillis(),
            TimeUnit.MILLISECONDS
        );
    }

    private void sweep() {
        var manager = conversationManager;
        if (manager == null) {
            return; // shutdown() raced the sweep
        }
        try {
            var tracker = manager.getCommitmentTracker();
            int conversations = manager.cleanup(retention);
            int commitments = tracker.cleanup(retention);

            // Order matters: checkViolations() moves an overdue commitment to VIOLATED, which
            // CommitmentState.isTerminal() reports as terminal, and cleanup() selects on
            // createdAt — not on when the state became terminal. Violating first would let a
            // commitment older than the retention window be marked and deleted in the same
            // sweep, so its creditor could never observe VIOLATED through get(). Sweeping
            // first leaves it in place for at least one full interval.
            var violated = tracker.checkViolations();
            violated.forEach(c -> log.warn(
                "Commitment {} violated for agent {}: deadline exceeded, {} owes {} in conversation {}",
                c.getId(), agent.getAgentId(), c.getPerformer(), c.getRequester(),
                c.getConversationId()));

            if (conversations > 0 || commitments > 0) {
                log.debug("Dialogue sweep for agent {}: removed {} conversations, {} commitments",
                    agent.getAgentId(), conversations, commitments);
            }
        } catch (Exception e) {
            // Never let a sweep failure kill the scheduled task
            log.warn("Dialogue sweep failed for agent {}: {}", agent.getAgentId(), e.getMessage(), e);
        }
    }

    private void stopReaper() {
        if (reaper == null) {
            return;
        }
        reaper.shutdown();
        try {
            if (!reaper.awaitTermination(5, TimeUnit.SECONDS)) {
                reaper.shutdownNow();
            }
        } catch (InterruptedException e) {
            reaper.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** Package-private: the sweep has no public surface, but its lifecycle needs a test. */
    boolean isReaperRunning() {
        return reaper != null && !reaper.isShutdown();
    }

    /**
     * Handles an incoming message, converting to DialogueMessage and dispatching.
     *
     * <p>The guard stays even though a mailbox now routes by the same predicate before calling
     * this method: an {@link dev.agenor.core.Agent} that is not a {@link BaseAgent} has no
     * mailbox, subscribes to its recipient channel directly, and so still sees traffic that is
     * not dialogue. Converting such a message here would fabricate an {@code INFORM} in a
     * conversation nobody started, which would then never reach a terminal state and never be
     * swept. See ADR-029 and ADR-032.
     */
    private void handleIncomingMessage(Message message) {
        if (!DialogueMessage.isDialogueMessage(message)) {
            if (message.headers().containsKey("performative")) {
                // A peer speaking a dialect this runtime does not know. Silently dropping it is
                // the kind of thing that costs a day of debugging, so say so.
                log.warn("Agent {} is not routing message {} from {} to dialogue: unknown "
                        + "performative '{}'",
                    agent.getAgentId(), message.id(), message.senderId(),
                    message.headers().get("performative"));
            } else {
                log.trace("Agent {} received a non-dialogue message {} from {}; left to the "
                        + "direct-message path",
                    agent.getAgentId(), message.id(), message.senderId());
            }
            return;
        }
        try {
            DialogueMessage dialogueMessage = DialogueMessage.fromMessage(message);

            // Let conversation manager handle state tracking
            conversationManager.handleIncoming(dialogueMessage);

            // Dispatch to registered handlers
            boolean handled = handlerRegistry.dispatch(dialogueMessage);

            if (!handled) {
                log.debug("No handler found for message: performative={}, protocol={}",
                    dialogueMessage.performative(), dialogueMessage.protocol());
            }
        } catch (Exception e) {
            log.error("Error handling dialogue message: {}", e.getMessage(), e);
        }
    }

    // =========================================================================
    // HIGH-LEVEL API
    // =========================================================================

    /**
     * Sends a REQUEST to another agent and waits for the outcome.
     *
     * <p>The future resolves on the <strong>final</strong> reply — {@code INFORM} or
     * {@code FAILURE}, or an immediate {@code REFUSE}. An intermediate {@code AGREE} that the
     * protocol expects to be followed by one of those does <em>not</em> resolve it, so a
     * two-phase exchange yields the result rather than the acknowledgement. To observe the
     * AGREE as well, register a listener on the conversation id via
     * {@link ConversationManager#onMessage(String, java.util.function.Consumer)}. See ADR-026.
     *
     * @param targetAgentId the agent to send the REQUEST to
     * @param content       the request payload; any type the transport's codec can serialise
     * @return a future completing with the final reply, or failing on timeout
     */
    public CompletableFuture<DialogueMessage> request(String targetAgentId, Object content) {
        return request(targetAgentId, content, DEFAULT_TIMEOUT);
    }

    /**
     * Sends a REQUEST to another agent and waits for the outcome, with an explicit timeout.
     *
     * @param targetAgentId the agent to send the REQUEST to
     * @param content       the request payload
     * @param timeout       how long to wait before the future fails
     * @return a future completing with the final reply, or failing on timeout
     * @see #request(String, Object)
     */
    public CompletableFuture<DialogueMessage> request(String targetAgentId, Object content, Duration timeout) {
        return requireInitialized().request(targetAgentId, content, timeout);
    }

    /**
     * Sends a QUERY to another agent and waits for the first reply.
     *
     * <p>QUERY expects a single {@code INFORM} reply; unlike REQUEST it does not
     * produce an intermediate AGREE, so the future typically resolves with the result.
     */
    public CompletableFuture<DialogueMessage> query(String targetAgentId, Object query) {
        return query(targetAgentId, query, DEFAULT_TIMEOUT);
    }

    /**
     * Sends a QUERY to another agent and waits for the first reply.
     *
     * @see #query(String, Object)
     */
    public CompletableFuture<DialogueMessage> query(String targetAgentId, Object query, Duration timeout) {
        return requireInitialized().query(targetAgentId, query, timeout);
    }

    /**
     * Initiates a call for proposals.
     */
    public CompletableFuture<List<DialogueMessage>> callForProposals(
            List<String> participants, Object taskSpec, Duration deadline) {
        return requireInitialized().callForProposals(participants, taskSpec, deadline);
    }

    /**
     * Replies to a received message.
     */
    public CompletableFuture<Void> reply(DialogueMessage original, Performative performative, Object content) {
        return requireInitialized().reply(original, performative, content);
    }

    /**
     * Convenience: reply with AGREE.
     */
    public CompletableFuture<Void> agree(DialogueMessage original) {
        return reply(original, Performative.AGREE, null);
    }

    /**
     * Convenience: reply with AGREE and content.
     */
    public CompletableFuture<Void> agree(DialogueMessage original, Object content) {
        return reply(original, Performative.AGREE, content);
    }

    /**
     * Convenience: reply with REFUSE.
     */
    public CompletableFuture<Void> refuse(DialogueMessage original, String reason) {
        return reply(original, Performative.REFUSE, reason);
    }

    /**
     * Convenience: reply with INFORM (result).
     */
    public CompletableFuture<Void> inform(DialogueMessage original, Object result) {
        return reply(original, Performative.INFORM, result);
    }

    /**
     * Convenience: reply with FAILURE.
     */
    public CompletableFuture<Void> failure(DialogueMessage original, String reason) {
        return reply(original, Performative.FAILURE, reason);
    }

    /**
     * Convenience: reply with PROPOSE.
     */
    public CompletableFuture<Void> propose(DialogueMessage cfp, Object proposal) {
        return reply(cfp, Performative.PROPOSE, proposal);
    }

    // =========================================================================
    // CONVERSATION ACCESS
    // =========================================================================

    /**
     * Gets a conversation by ID.
     */
    public Optional<Conversation> getConversation(String conversationId) {
        return requireInitialized().getConversation(conversationId);
    }

    /**
     * Gets all active conversations.
     */
    public List<Conversation> getActiveConversations() {
        return requireInitialized().getActiveConversations();
    }

    /**
     * Gets the underlying conversation manager.
     */
    public ConversationManager getConversationManager() {
        return requireInitialized();
    }

    /**
     * Gets the commitment tracker.
     *
     * @since 0.26.0 returns the {@link CommitmentTracker} interface rather than the
     *        concrete default implementation
     */
    public CommitmentTracker getCommitmentTracker() {
        return requireInitialized().getCommitmentTracker();
    }

    /**
     * Builder for a {@link DialogueCapability} with non-default collaborators.
     *
     * <p>Every collaborator has the same default the plain constructor uses, so only what
     * actually differs needs to be set.
     *
     * @since 0.26.0
     */
    public static final class Builder {

        private final Agent agent;
        private ConversationManagerFactory conversationManagerFactory = DefaultConversationManager::new;
        private ProtocolRegistry protocolRegistry = new ProtocolRegistry();
        private CommitmentTracker commitmentTracker = new DefaultCommitmentTracker();
        private Duration retention = DEFAULT_RETENTION;
        private Duration sweepInterval = DEFAULT_SWEEP_INTERVAL;

        private Builder(Agent agent) {
            this.agent = agent;
        }

        /**
         * @param factory creates the conversation manager; default
         *                {@code DefaultConversationManager::new} (in-memory, node-local)
         * @return this builder
         */
        public Builder conversationManagerFactory(ConversationManagerFactory factory) {
            this.conversationManagerFactory = factory;
            return this;
        }

        /**
         * @param registry the protocols available to this agent, e.g. one carrying a custom
         *                 {@code Protocol}; default: the built-in request/query/contract-net
         * @return this builder
         */
        public Builder protocolRegistry(ProtocolRegistry registry) {
            this.protocolRegistry = registry;
            return this;
        }

        /**
         * @param tracker records the commitments made in this agent's conversations;
         *                default {@code DefaultCommitmentTracker}
         * @return this builder
         */
        public Builder commitmentTracker(CommitmentTracker tracker) {
            this.commitmentTracker = tracker;
            return this;
        }

        /**
         * @param retention how long terminated conversations and commitments are kept
         *                  before being swept; default 5 minutes
         * @return this builder
         */
        public Builder retention(Duration retention) {
            this.retention = retention;
            return this;
        }

        /**
         * @param sweepInterval how often the retention sweep runs; default 1 minute. It also
         *                      bounds how late an overdue commitment is marked violated
         * @return this builder
         */
        public Builder sweepInterval(Duration sweepInterval) {
            this.sweepInterval = sweepInterval;
            return this;
        }

        /**
         * @return the configured capability, already registered on the agent's lifecycle
         *         hooks when it supports them
         */
        public DialogueCapability build() {
            return new DialogueCapability(agent, conversationManagerFactory, protocolRegistry,
                commitmentTracker, retention, sweepInterval);
        }
    }
}
