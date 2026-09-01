# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **A conversation can now be read as a conversation, in two ways, and the documentation says
  which one works where.** JADE's Sniffer is the thing people remember about it, and until now
  Agenor could show you a flat list of messages and nothing above it.

  *Across nodes, no code required.* Every `agent.receive` span carries `conversation.id`, so
  filtering on that tag in any OpenTelemetry backend returns the whole exchange — CFP, proposals,
  acceptance, result — across every agent and node that took part. The collector is the fan-out a
  distributed sniffer would otherwise have to build. `docs/observability.md` now says so, with the
  Jaeger stack it already shipped.

  *Without a collector*, the web console groups the message sniffer's buffer:
  `GET /api/conversations` lists the ids with their message counts, `GET /api/conversations/{id}`
  returns one exchange oldest-first. Same data in code through
  `SnifferSupport.findByConversation(runtime, id)` and `getConversations(runtime)`, and on
  `MessageHistoryService` directly.

  **The console route is in-memory only, and this is now written down where you meet it.** The
  sniffer captures through `FilterableSubscriber`, which no remote backend implements by design
  (ADR-020: a Java predicate cannot be evaluated server-side). Against Redis it logs
  *"sniffer disabled"* at startup and captures nothing. That limit had never been stated
  anywhere; the span route exists precisely because it does not have it.


- **The mailbox emits an `agent.receive` span, so tracing an inbound message no longer depends
  on the transport that delivered it.** The Redis adapter emitted `message.receive` from its
  consumer loop; the in-memory dispatcher emitted nothing on the receive side. The same agent
  code was therefore observable on one transport and invisible on the other — the asymmetry
  ADR-032 left when it designated the mailbox drain as the home for receive-side telemetry and
  did not fill it.

  The span carries `agent.id`, `message.id`, `message.topic`, `agent.sender`,
  `message.correlation_id`, and two attributes available nowhere else: `mailbox.lane`
  (`dialogue` or `push`), the routing decision only the mailbox makes, and `conversation.id`,
  which is what lets a whole exchange be reassembled from spans. It is named apart from the
  transport's own `message.receive` so that on Redis the two nest rather than collide.

  Telemetry now reaches an agent the way its dispatcher and directory do:
  `AgenorRuntime` calls `BaseAgent.setTelemetry(...)` at registration, and `createMailbox` passes
  it on. An agent that is never registered, or a mailbox built with the three-argument
  constructor, keeps the no-op and behaves exactly as before.

  `DialogueMessage.CONVERSATION_ID_HEADER` and `PERFORMATIVE_HEADER` are now public constants.
  The mailbox reads the conversation id off a `Message` without building a `DialogueMessage`,
  and the key was a string literal repeated in four places.

### Changed

- **`ContractNetExample` reads the commitment its negotiation creates.** Accepting a proposal
  binds the winning worker, and the manager now prints what it is owed and by whom, then the
  commitment's state once the work is reported done:

  ```
  [Manager] Selected: worker-2
  [Manager] worker-2 now owes me: Task[type=data-processing, complexity=100] (ACTIVE)
  [Manager] Task completed by worker-2: SUCCESS
  [Manager] Commitment by worker-2 is now FULFILLED
  ```

  The manager also accepts with the task rather than with a congratulation, because the content
  of the `AGREE` becomes the content of the commitment — accepting with `"You win!"` left the
  tracker holding an obligation whose subject was a pleasantry.

### Fixed

- **A commitment's performer and requester now come from the protocol, not from the performative
  alone.** `DefaultCommitmentTracker` read `AGREE` as "the sender performs", which is right for
  `RequestProtocol` — you asked, I agreed, I perform — and backwards for Contract Net, where the
  initiator sends `AGREE` to *accept* a proposal and the participant is the party taking on the
  work. A contract-net manager therefore held an active commitment naming itself as the performer
  of the task it had just delegated, with the acceptance string as the content, while
  `getActiveAsRequester(managerId)` — documented as "commitments I'm waiting on" — returned
  nothing.

  `Protocol` gains `senderPerforms(Performative)` as a `default` method, with the static
  `Protocol.senderPerformsByDefault(...)` for implementations that override some performatives
  and defer the rest. `ContractNetProtocol` overrides it. This is where the question already
  belongs: `allowedPerformatives(state, isInitiator)` has read from the sender's perspective
  since ADR-029, because direction is a property of the protocol. A protocol you write yourself
  now participates without the tracker knowing about it.

  `DefaultCommitmentTracker` takes the `ProtocolRegistry` that resolves a message's protocol, and
  `DialogueCapability` hands it the same instance it uses itself. A tracker built without one
  keeps the previous behaviour for every message.

  Two related defects are recorded in ADR-009's amendment and deliberately not fixed here: the
  commitment record is created by the sender only, so the party that took the work on holds
  nothing; and although `Performative.createsCommitment()` reports four performatives,
  commitments are created for `REQUEST` and `AGREE` only.

- **`tools/api-census.sh` no longer reads a fenced package tree or ASCII architecture diagram as
  documentation.** `docs/dialog-protocol.md`'s "Package Structure" listing named
  `ProtocolState`, `ContractNetProtocol` and `QueryProtocol` nowhere except inside that box, so
  they scored **documented, unnamed** — a verdict whose two outcomes both point at removal — for
  a reason unrelated to whether anyone reads about them. Only a *bare* fenced block is excluded,
  though — a first cut stripped every fence alike, `` ```java `` samples included, and that took
  `AgentContext` and `SimpleTokenEstimator` down to **plumbing** even though `docs/*.md` shows
  working, tested code calling both. Only a bare fence (a diagram, a tree, a log line) is
  excluded now; a language-tagged one is kept, because it is the strongest form prose offers.

  A **documented, unnamed** type that also has `framework > 0` can be reached through a getter
  an example never has to name; the census explained this in prose but the verdict itself did
  not say so, and the same misreading recurred across independent reviews of the report. That
  verdict now reads **documented, unnamed — check getters**.

  `tools/api-census-diff.sh <before.md> <after.md>` compares two saved reports per type instead
  of by the summary counts, which a page deletion has fooled before. `CONTRIBUTING.md`'s release
  checklist now points to it where it already asked for the comparison.

- **The census now covers all nine modules that contain code, not five.**
  `agenor-adapters`, `agenor-adapters-persistence`, `agenor-spring-boot-starter` and
  `agenor-tools` had never been measured — two of them, the Spring Boot starter and the
  console/CLI, are how most developers meet the framework at all. Widened only after the
  fenced-block fix above landed, and read as its own diff: 67 new types, zero verdict changes on
  the 204 already measured. Totals move from 204 to 271, and **documented, unnamed** from 77 to
  96.

  The new surface came with one thing the previous five modules never had: an entry point.
  `AgenorCLI` scores **dead surface** — the census counts imports and same-package word matches,
  and nothing ever imports a class the JVM starts by fully-qualified name
  (`agenor-tools/pom.xml`'s `exec:java` `mainClass` property). It is documented, by example, in
  `agenor-examples/.../CLIExample.java`'s Javadoc, but as an `mvn exec:java
  -Dexec.mainClass=...` invocation, which the census's import index cannot see either. Recorded,
  not fixed: an entry point is invisible to this method the same way a getter path already is.

  `WebConsoleServer` also scores **dead surface**, and reading it turned up something the
  verdict itself did not: it has been `@Deprecated(since = "0.4.0")` — twenty-seven releases —
  with no `forRemoval`, so `--check` has never once seen it, because `--check` only audits
  deprecations that carry `forRemoval = true`. `JettyWebConsole` is the live implementation
  (**named by user code**) that ADR-008 says replaced it. Recorded as a separate finding: a
  deprecation with no `forRemoval` at all is as invisible to every tool as an undated one, and
  nothing here checks for that shape either.

## [0.30.0] - 2026-09-01

### Changed

- **A composite behavior no longer declares a `BehaviorType`; it derives one from its
  `SchedulingHint`.** `SchedulingHint` has said since 0.14.0 that it exists because
  `BehaviorType` cannot answer the question for a composite — a sequence is cyclic or one-shot
  depending on whether it carries an interval — and it was then applied to one branch of the
  hierarchy while the scheduler kept dispatching on the enum. `SimpleBehaviorScheduler` now
  routes every `CompositeBehavior` to the hint before looking at the type at all, and
  `CompositeBehavior.getType()` returns `CYCLIC` for a repeating composite and `ONE_SHOT`
  otherwise. `SequentialBehavior` and `ParallelBehavior` lost their `getType()` overrides;
  `FSMBehavior` keeps its own, because a state machine really is a third shape.

  Behaviour is unchanged for every composite in the tree. If you wrote your own
  `CompositeBehavior`, you can delete its `getType()` override.

- **`ParallelBehavior` is no longer deprecated.** Its 0.28.0 deprecation said in its own text
  that it was about the annotation route — "deprecated as an annotation target … building the
  composite directly keeps working" — but the annotation sat on the class, which scheduled the
  class itself for deletion and left `BehaviorType.PARALLEL`'s migration note pointing at
  something that would not survive the same release. The annotation route is gone with the
  constant; the class stays, alongside its sibling `SequentialBehavior`, which was never
  deprecated at class level.

- **`ExtBehaviorAnnotationExtension` now serves `FSM` alone**, having delivered seven types
  before the constants that reached six of them were removed. Whether an SPI is the right shape
  for a single type is left open on purpose: it is a decision about the extension point, not a
  consequence of this removal.

- **Level 1 of the examples learning path is one example instead of five.**
  `CoreBehaviorsExample` shows `ONE_SHOT`, `CYCLIC` and `FSM` in a single agent, with an
  annotated method and a built composite side by side. It replaces `ThrottledExample`,
  `ConditionalBehaviorExample`, `RetryExample`, `BatchProcessingExample` and `ScheduledExample`,
  each of which demonstrated a type that no longer exists.

### Deprecated

- **The twelve types that existed only to serve the removed behavior types are deprecated for
  removal in 0.32.0.** `docs/architecture.md` had already written the finding down — "rate
  limiting and `Condition` gating exist to serve behavior types deprecated in 0.28.0" — and what
  this release changes is that those types are now gone, so what served them serves nothing.

  The measurement, taken after the removal above: outside `dev.agenor.core.condition` and
  `dev.agenor.runtime.condition`, **no file in the tree imports either package**; the same holds
  for `dev.agenor.core.ratelimit` and `dev.agenor.runtime.ratelimit`. Each family is a closed
  island whose members are named only by each other. There are no examples, and the one
  user-facing page that named them is the one quoted above.

  - Conditions: `Condition`, `ConditionContext`, `SystemMetrics` (`agenor-core`);
    `ConditionEvaluator`, `AgentCondition`, `SystemCondition`, `TimeCondition`
    (`agenor-runtime-ext`). Test the condition where the work happens.
  - Rate limiting: `RateLimiter`, `RateLimit`, `RateLimiterStats` (`agenor-core`);
    `TokenBucketRateLimiter` (`agenor-runtime-ext`). Use a resilience library for outbound work;
    for inbound pressure the mailbox bounds concurrent handlers (ADR-033).
  - `CronExpression` (`agenor-runtime-ext`), reached only by the removed `ScheduledBehavior`.

  Two things worth recording rather than quietly fixing. `Condition` was listed in `CLAUDE.md`
  as one of the core contracts `agenor-core` defines; it was **designed** as one and never
  became one, and the list could not tell you the difference. And `SlidingWindowRateLimiter` was
  removed in this same release on the stated grounds that "the rate limiting the framework does
  itself goes through `TokenBucketRateLimiter`" — once `ThrottledBehavior` went, nothing goes
  through that either. Both notes are now in the deprecation Javadoc, where the next reader
  meets them.

  `CompletionStrategy` is **not** deprecated. It was on the same tail, reachable only through
  `ParallelBehavior`, and it keeps a real user because that class survived.

### Removed

- **The twelve `BehaviorType` constants deprecated in 0.28.0 are gone, with the eleven
  `@Behavior` elements that parameterised them and the fourteen types they reached.** Three
  constants remain — `ONE_SHOT`, `CYCLIC`, `FSM` — which is the number of answers the enum has
  to the only question it asks: when does this work run. `tools/api-census.sh --check` went from
  38 overdue entries to zero.

  BREAKING CHANGE:

  Removed `BehaviorType` constants, and where each concern belongs now:

  - `WAKER` — use `ONE_SHOT` with `@Behavior(initialDelay = "...")`, which honours the delay.
    A waker was a polling behavior and the scheduler drove it exactly once, at registration,
    when its wake condition was by construction still false; through the annotation it never
    fired.
  - `EVENT_DRIVEN` — use `@AgenorMessageHandler`. This one never worked either: the processor
    subscribed the behavior to a topic derived from the method name and to nothing else, and the
    scheduler skips event-driven behaviors on purpose.
  - `CUSTOM` — implement `Behavior` and let its owner drive it. Its scheduling path parked a
    platform thread per behavior in the common pool and could not be cancelled.
  - `CONDITIONAL` — gating is a property of a behavior, not a kind of one. Test the condition
    where the work happens.
  - `THROTTLED` — rate limiting wraps a call rather than scheduling one. Use a resilience
    library outbound; inbound, the mailbox drain is where receive-side policy belongs
    (ADR-032, ADR-033).
  - `BATCH` — buffering inbound messages has been the mailbox's concern since ADR-032. Batch
    your own data in your handler.
  - `RETRY` — Resilience4j and Failsafe do this better, and compose with the timeouts and
    bulkheads that arrive with the same problem.
  - `CIRCUIT_BREAKER` — a resilience pattern rather than an agent concept; the class serving it
    went in 0.29.0.
  - `SCHEDULED` — cron is a scheduling concern: drive the agent from whatever already owns your
    schedule, or use `CYCLIC` for a fixed cadence.
  - `PIPELINE` — `SequentialBehavior` covers ordered stages and has real callers.
  - `SEQUENTIAL`, `PARALLEL` — build a `SequentialBehavior` or `ParallelBehavior` and add it
    with `agent.addBehavior()`. Both classes are still here. An annotation cannot carry a list
    of children, which is what made these two constants unable to express what they named.

  Removed `@Behavior` elements, all of which parameterised a removed constant: `condition`,
  `rateLimit`, `batchSize`, `maxWaitTime`, `maxRetries`, `backoff`, `cron`, `stepTimeout`,
  `parallelStrategy`, `requiredCompletions`, `childTimeout`.

  Removed types:

  - `dev.agenor.core.exceptions.MessageException` — nothing threw, caught or declared it.
  - `dev.agenor.runtime.behavior.WakerBehavior`, `EventDrivenBehavior` — see the constants above.
  - `dev.agenor.runtime.behavior.advanced.BatchBehavior`, `ConditionalBehavior`, `RetryBehavior`,
    `ScheduledBehavior`, `ThrottledBehavior` — see the constants above.
  - `dev.agenor.runtime.filter.CompositeFilter`, `PredicateFilter` — `MessageFilter.and/or/negate`
    and `MessageFilter.of(Predicate)` in `agenor-core` do this, and have users.
  - `dev.agenor.runtime.hitl.WebhookApprovalNotifier` — implement `ApprovalNotifier` against
    whatever your deployment actually notifies.
  - `dev.agenor.runtime.ratelimit.SlidingWindowRateLimiter` — the framework's own rate limiting
    goes through `TokenBucketRateLimiter`.
  - `dev.agenor.runtime.guardrail.JsonSchemaOutputGuardrail` — apply a JSON-schema library inside
    an `OutputGuardrail` you write.
  - `dev.agenor.runtime.memory.llm.TokenBudgetManager` — token budgets reach users through
    `LLMMemoryManager` and the context-window strategies.

### Fixed

- **An `@AgenorMessageHandler` can now be asynchronous, and until this release it could not
  be.** The framework's primary documented way to receive a message could only be written as a
  `void` method: `isValidMessageHandlerMethod` accepted `void` or `Void` and nothing else, so a
  handler declaring `CompletableFuture<Void>` was rejected as an invalid signature, logged as a
  warning, and never registered. The only shape left for asynchronous work — start a chain,
  return — is the one that opts out of every guarantee ADR-033 makes: D-5, that a handler's
  failure is no longer swallowed, and D-2, that in-flight handlers are bounded. Both count what
  a handler *returns*, and a `void` method returns as soon as it has started the work.

  A handler may now return a `CompletionStage`, and the framework waits on it: the message is
  acknowledged when the stage completes, a failure in the chain reaches the transport rather
  than vanishing, and the work counts against the mailbox's limit on concurrent handlers. A
  `void` handler behaves exactly as before. Any other return type is still rejected.

  `LLMDirectMessagingExample` was the shipped demonstration of the pattern that opts out — three
  researchers and a coordinator, each doing `llm.chat(...).thenAccept(...)` from a `void`
  handler — and now returns its chains. Running it shows the difference directly: an agent
  stopped while its LLM call is still in flight now logs *"Mailbox … stopped with handlers still
  running"*, where before the mailbox saw nothing running and shut down on a false all-clear. The cross-transport case is in
  `MailboxDeliverySemanticsIT`: a handler whose returned future fails is redelivered and reaches
  the dead-letter stream, exactly as one that throws. It cannot live in
  `MessageDispatcherContractTests` — that suite is in `agenor-core`, which cannot see
  `BaseAgent` or the annotation processor.

- **The behavior scheduler no longer parks an unreclaimable platform thread per behavior.**
  `scheduleCustom` ran a `Thread.sleep` loop on `ForkJoinPool.commonPool` with no
  `ManagedBlocker`, never registered itself in `scheduledBehaviors` — so `cancel()` could not
  reach it — and served `CUSTOM`, `CONDITIONAL`, `THROTTLED` and `BATCH`. It lost every caller
  with those four constants and was deleted rather than repaired. `scheduleWaker` went the same
  way.

## [0.29.0] - 2026-08-31

### Changed

- **`docs/mailbox.md` is gone; the two rules it held that change your code are in
  `docs/messaging.md`.** The mailbox is infrastructure — you never create one, wire one or call
  into one — and a dedicated user-facing page for it was the case that the adoption phase's
  "plumbing must stay invisible" criterion was written against. What survives is the part a
  reader acts on: handlers for one agent may overlap and are not serialised, and a handler must
  be idempotent because a transport with redelivery will run it again. Both now sit in
  *Delivery Semantics*, where they correct two claims that had been false since 0.27.0 — that
  page still said delivery was "at-most-once" and that there was "no backpressure", neither of
  which survived ADR-032 and ADR-033. Why the inbound path is shaped this way stays in those
  two ADRs, which is where a maintainer looks and a user does not.

  The mailbox itself is unchanged. Nothing was deprecated: `MailboxConfig`, `OverflowPolicy`
  and `MailboxOverflowException` are still public and still supported. They now have no
  documentation page and no user outside `src/main`, which is a question about them rather than
  an answer, and it is recorded as such rather than settled by deleting them.

### Removed

- **The six deprecations whose removal was promised for this release are paid.** All six were
  past their own date the moment `0.29.0-SNAPSHOT` opened, which is what
  `tools/api-census.sh --check` exists to say out loud — it exited non-zero on every one of
  them. Each had zero users outside its own test or its own demonstration, so the census
  verdict and the removal are the same decision, taken twice.

  BREAKING CHANGE:

  - `LLMRequest.builder(String model)` — use `LLMRequest.builder()` and `.model(String)` when
    a per-request override is actually needed. Deprecated since 0.16.0, thirteen releases. Its
    only caller anywhere was the test asserting that `builder(null)` does not throw; the
    equivalent guarantee on `builder().model(null)` is now what that test asserts.
  - `LLMMemoryQuery` — build the query arguments at the call site. It was the only type in the
    census with a zero in every column: no framework code, no example, no documentation page.
  - `CircuitBreakerBehavior`, `PipelineBehavior`, `ReflectionBehavior` — with
    `CircuitBreakerExample`, `PipelineExample` and `ReflectionExample`, which existed to
    demonstrate them and were their only callers. Circuit breaking belongs to a resilience
    library; `SequentialBehavior` already covers ordered stages and has real callers; compose
    a critique loop with `ReflectionStrategy`, which survives and is where the judgement lives.
  - `DialogueCapability.initialize(MessageDispatcher)` — call `initialize()`, which resolves
    the dispatcher from the agent. No `src/main` file called the overload; the twenty-one call
    sites were all in one test, whose fake agent returned `null` from `getMessageDispatcher()`
    precisely so the overload had to be used. Giving the fake a dispatcher migrated all of them.

  `BehaviorType.CIRCUIT_BREAKER` and `BehaviorType.PIPELINE` deliberately **stay** until their
  own date of 0.30.0. A constant is what a user writes in `@Behavior(type = …)`, so it is the
  unit of the deprecation contract; retiring it a release early to tidy up after the class
  would break the promise the annotation made, in the release whose subject is keeping them.

  Two consequences worth stating rather than discovering. `ReflectionBehavior` was the sole
  emitter of the `reflection.iteration` span, so that span no longer exists — ADR-019's table
  lists an instrument with no source. And ADR-012 remains **Accepted** and is unaffected: its
  decision was to place `ReflectionStrategy` in `agenor-core`, and the strategy is what stayed.

  This takes the surface scheduled for removal from 44 to 38, and `--check` from red to green.
  The count going *down* is the point: it measures surface still scheduled, not work done.

### Fixed

- **The core Javadoc taught a constant that is being removed.** `Behavior.getType()` said to
  "use `BehaviorType.CUSTOM` for behaviors that don't fit standard patterns", and
  `CompositeBehavior`'s implementation example returned it — while `CUSTOM` is deprecated for
  removal in 0.30.0 with no user of any kind. Both now teach the answer that already exists in
  the code: a composite has no type of its own and answers with the one its `getSchedulingHint()`
  implies, `CYCLIC` when it has an interval and `ONE_SHOT` when it does not. Doing this before
  the removal is what keeps that release mechanical instead of turning it into a redesign.

- **`Agent`'s class Javadoc still contained both fictions that 0.29.0 had already fixed
  twenty lines lower.** The fix to `addBehavior`'s example replaced an invented
  `CyclicBehavior(String, Runnable)` constructor and a `MessageBehavior` type that exists
  nowhere — and the class-level example above it used the same two. It now uses the real factory,
  `CyclicBehavior.from(name, interval, action)`. A sweep of every type used *as a type* inside a
  `{@code}` block across all of `src/main` — `new X(`, `extends X`, `X.method(`, `X var =` —
  turned up one more: `@RequiresApproval`'s only example had a behaviour `extends AgentBehavior`,
  which does not exist, and named `WebhookApprovalNotifier`, which is dead surface already
  deprecated for removal.

- **`MessageHandler`'s error-handling contract described the framework as it was before
  ADR-033.** It promised an unhandled exception would "not affect other handlers or messages"
  and would "be logged by the message service". The first is now the opposite of true — a failed
  future is exactly how the failure reaches the mailbox, which then does not acknowledge the
  message, so it is redelivered and eventually dead-lettered — and the second names
  `MessageService`, a type removed at 0.22.0. `sync()`'s own note claimed it *prevented*
  propagation, which inverts the mechanism ADR-033 D-5 depends on. `MessageService` also appeared
  as a live type in two of the interface's code examples, one of them as a method parameter; both
  now use `TopicSubscriber.subscribeTopic`, which is the API that exists.

- **The README's first paragraph names an audience instead of a lineage.** It opened with
  *"a contemporary multi-agent framework … modernizing the concepts pioneered by JADE"*, which
  answers *what this is* rather than *who it is for*. The JADE lineage is still there, one
  paragraph down, where it informs rather than introduces.

- **`javac` now rejects a broken Javadoc reference at compile time.**
  `-Xdoclint:all,-missing` on `maven-compiler-plugin`. javac already parses the Javadoc while
  compiling, so validating it costs nothing and moves the check from *"when the release is
  published"* to `mvn compile`. The whole reactor compiles with zero diagnostics, so it needed
  no cleanup campaign — only four unescaped `<` in one example's prose (`CPU < 50%`, which javac
  reads as an opening tag). `-missing` stays off deliberately: it asks whether everything is
  documented, a different question from whether what is written is correct, and would bury the
  second under thousands of absent `@param` tags.

- **The build fails in `validate` when `JAVA_HOME` is not a JDK 21+.** `README.md` and
  `CONTRIBUTING.md` have always said "Java 21+" and nothing checked it; a wrong `JAVA_HOME` died
  inside the compiler with `release version 21 not supported`, naming neither the requirement nor
  the fix. `requireJavaVersion` joins the existing enforcer execution. A version number was never
  the whole requirement, either — a JRE satisfies it and still has no `javac` — so both
  prerequisite lists now say **JDK**, and the enforcer message says so too.

- **The integration tests skip when Docker is absent instead of failing.**
  `@EnabledIfSystemProperty(named = "integration.tests.enabled")` asks whether you requested
  them, not whether the machine can run them. On a host with no reachable daemon the ten ITs
  errored, Testcontainers cached the first failure (*"Will not retry"*), the reactor stopped in
  `agenor-adapters`, and four ITs in later modules were never reached — four errors, six tests
  silently not run, nothing wrong with the code. `@EnabledIfDockerAvailable` now asks the second
  question: 49 tests skipped with the reason recorded, and the build reaches the last module.

- **`OllamaProviderIntegrationTest` is now `OllamaProviderIT`.** Ending in `Test` matched
  surefire's default includes, so the suite's heaviest Docker test — it pulls the ollama image
  and a 397MB model — ran in the unit-test phase, discovered by a different convention from the
  nine tests it belongs with. The root pom already says `*IT.java` means an integration test
  needing Docker.


- **`@AgenorMessageHandler` documented a wildcard feature that does not exist, and used one in
  its own example.** The Javadoc promised that `"orders.*"` matches `"orders.new"` and that
  `"#"` matches any topic; the example subscribed to `"inventory.*"`; `docs/message-filtering.md`
  repeated it with the comment *"Topic already filtered by annotation pattern"*. Both delivery
  paths resolve a handler by exact map lookup — `InMemoryMessageDispatcher.deliverToTopic` does
  `topicSubscriptions.get(topic)` and `BaseAgent.handleDirectMessage` does
  `directTopicHandlers.get(message.topic())` — so such a handler was registered and never fired.
  No test covered it and no example used it: the only place a pattern appeared was the
  documentation promising it.

  The promise is gone from the annotation, from `value()` (whose *"wildcard support is
  implementation-dependent"* was the hedge that kept a false claim alive) and from the guide,
  each now pointing at the mechanism that does work — `TopicFilter.wildcard()` applied through
  `FilterableSubscriber.subscribeFiltered()`, which is almost certainly where the claim came
  from: the filter's capability was attributed to the annotation.

  **A pattern is now rejected when the agent is registered**, naming the topic, the reason and
  the alternative. It is raised outside the per-handler `catch` that logs and continues, because
  this is not one handler that failed to build — it is an agent whose declared contract cannot be
  honoured. Silence cost an afternoon; a startup error costs a minute.

  Wildcard subscription was deliberately **not** implemented. Nothing uses it, nothing tests it,
  and making a Javadoc sentence true is not a reason to add a third way to route a message.

- **`Agent.addBehavior`'s Javadoc example was fiction, in the central interface of the core
  module.** Not one of its three lines was real: `MessageBehavior` does not exist anywhere in
  the repository, `TopicFilter` has no `matching()` (its wildcard factory is `wildcard()`), and
  `CyclicBehavior`'s constructors are `protected` and take `(Duration)` or `(String, Duration)`
  — not the `(String, Runnable, Duration)` the example invented, which is what the real factory
  `CyclicBehavior.from(name, interval, action)` already does. The example now uses that factory
  and the anonymous-subclass idiom the shipped examples actually use, and says plainly that
  reacting to a message is not a behavior but `@AgenorMessageHandler`.

  `-Xdoclint` does not reach inside `{@code}` blocks, so nothing catches this class of defect;
  a sweep of every type named in a Javadoc code block across `src/main` turned up no other
  invented framework type.

- **An agent's `@AgenorMessageHandler` topic subscriptions outlived it.**
  `AgentAnnotationProcessor` kept only `subscriptionId()` from `subscribeTopic()`, for a log
  line, and discarded the `Subscription` itself — so nothing could ever cancel it and the
  subscription survived the agent for the life of the process. `BaseAgent` already held and
  released its own direct-message subscription; the asymmetry was the defect. The handle is now
  handed to the agent via `registerTopicSubscription()` and released on stop, next to
  `unsubscribeDirectMessages()`.

- **Removing the `AgentDirectory` facade broke Javadoc, and 0.28.0 shipped that way.**
  `AgentQuery` lives in `dev.agenor.core` and referred to `{@link AgentDirectory}` unqualified,
  which resolved to the facade in its own package until the facade was removed. `mvn clean
  verify` stayed green through the entire release — `verify` does not run javadoc, and neither
  does `build.yml` — so the error first appeared in `deploy-docs.yml`, which runs on `release:
  published`. The published API documentation for 0.28.0 was never generated. The reference is
  now qualified; nothing about runtime behaviour was ever affected.

  The pre-removal audit grepped for the fully-qualified name and missed this the same way it
  missed `AgenorRuntime`: a name that resolves without being spelled out. There it was
  `import dev.agenor.core.*`, here same-package resolution inside a Javadoc tag. The compiler
  caught the first kind. Nothing caught this one, because Javadoc references are not compiled.

- **Two tests in `agenor-runtime-ext` passed only under surefire's default invocation.**
  `WebhookApprovalNotifierTest` registered `WireMockExtension` as a *static* field, binding its
  lifecycle to the enclosing class — which has four `@Nested` children. Any `-Dtest=…` puts
  surefire in specified-tests mode, where those are discovered as separate entries sharing the
  one static object, so an `afterAll` from one stopped the server another still needed: 492
  tests, one error, every time. An instance field binds per test method instead. Separately,
  five approval tests called `execute()`, slept 50ms and took `getPendingRequests().getFirst()`
  — a guess rather than a wait, which threw `NoSuchElementException` under load. They now poll.

## [0.28.0] - 2026-08-29

### Deprecated

- **The behaviour taxonomy keeps the three types that have users.** `tools/api-census.sh` now
  counts `BehaviorType` constants as well as types, because a user writes
  `@Behavior(type = THROTTLED)` and never names `ThrottledBehavior` — so every annotation-driven
  behaviour had been scoring zero example references for a reason unrelated to whether anyone
  used it. Across the 90 example files, `CYCLIC` is named by 18, `ONE_SHOT` and `FSM` by two
  each, and the other twelve constants by their own demonstration or by nothing.

  The usage count is not the whole argument, and on its own it would not justify removing a
  concept rather than waiting for a user. `SchedulingHint` has said the rest since 0.14.0: the
  scheduler consults it *"instead of relying on `BehaviorType` alone"* because
  *"control-flow composites (`RETRY`, `CIRCUIT_BREAKER`, `FSM`, `PIPELINE`) are wrappers
  triggered by external events."* A wrapper is a decorator, not a schedule, and `BehaviorType`
  had been answering two questions at once. Classifying by that criterion reaches the same three
  survivors the usage count does.

  Deprecated for removal in 0.30.0: `EVENT_DRIVEN`, `WAKER`, `CUSTOM`, `PIPELINE`,
  `CIRCUIT_BREAKER`, `PARALLEL`, `SEQUENTIAL`, `CONDITIONAL`, `THROTTLED`, `BATCH`, `RETRY`,
  `SCHEDULED`, together with the eleven `@Behavior` elements that exist only to parameterise
  them (`condition`, `rateLimit`, `batchSize`, `maxWaitTime`, `maxRetries`, `backoff`, `cron`,
  `stepTimeout`, `parallelStrategy`, `requiredCompletions`, `childTimeout`) and the eight
  behaviour classes nothing else names. Each constant's Javadoc names where its concern belongs
  instead: a resilience library for retry, circuit breaking and outbound rate limiting; the
  mailbox drain for inbound shaping (ADR-032, ADR-033); `CompositeBehavior` with its
  `SchedulingHint` for composition; a strategy on `LLMAgent` for LLM reasoning patterns, as
  reflection already is.

  `SequentialBehavior`, `ParallelBehavior` and `CompositeBehavior` remain as classes — only
  their annotation route goes. `OrderOrchestratorAgent` composes them directly, which is the
  supported way.

  **Two of the twelve never worked.** `EVENT_DRIVEN` built a behaviour subscribed to a topic
  derived from the method name and to nothing else, and `SimpleBehaviorScheduler` skips
  event-driven behaviours on purpose, so nothing ever called it. `WAKER` is a polling behaviour
  that the scheduler drove exactly once, at registration, when its wake condition was by
  construction still false — so through the annotation it never woke. Both had unit tests; both
  were tested at the class and never through the wiring.

- **Seven types nothing in the repository references**, for removal in 0.30.0: `PredicateFilter`
  and `CompositeFilter` (superseded by `MessageFilter.of` and `MessageFilter.and/or/negate` in
  `agenor-core`, which have users), `SlidingWindowRateLimiter`, `JsonSchemaOutputGuardrail`,
  `TokenBudgetManager`, `WebhookApprovalNotifier` — whose only mention in the tree is the
  `@RequiresApproval` Javadoc snippet offering it — and `MessageException`, an exception nothing
  throws, catches or declares.

The public surface scheduled for removal goes from 7 to 45: **20 types, 12 enum constants and
13 members**. One removal does land — the `AgentDirectory` facade, below — and every other
deprecated API keeps working until 0.29.0 or 0.30.0 as its Javadoc says.

- **Two deprecations that named no release now name one.** `LLMRequest.builder(String)` has
  carried `forRemoval = true` since 0.16.0 and
  `DialogueCapability.initialize(MessageDispatcher)` since 0.26.0, neither with a target
  release. Both are now scheduled for **0.29.0**. A deprecation with no declared release can
  never *become* overdue, so nothing would ever have flagged it — which is how the
  `AgentDirectory` facade outlived its own date by four releases unnoticed.

### Fixed

- **`initialDelay` was documented for two behaviour types and honoured by neither.** The
  `@Behavior` element advertised itself as *"applicable to CYCLIC and SCHEDULED"*, and
  `createOneShotBehavior` and `createCyclicBehavior` both ignored it: the only reader was
  `createWakerBehavior`, whose behaviour never fired. The delay now lives where a delay belongs
  — `Behavior.getInitialDelay()` is read by `SimpleBehaviorScheduler` for both one-shot and
  cyclic scheduling, so `@Behavior(type = ONE_SHOT, initialDelay = "10s")` runs once after ten
  seconds and a cyclic behaviour waits before its first tick and then keeps its interval. An
  absent `initialDelay` still means *start now*: `parseDuration` answers a blank string with one
  second, which is the right default for `interval` and would have postponed every behaviour
  that never asked to be delayed.

### Changed

- **A `@Behavior(type = ONE_SHOT, initialDelay = "5s")` that ran immediately now waits five
  seconds.** The element was ignored before, so this moves from silently wrong to documented —
  but it is observable, and it is the one behaviour change in this release.

- **The documentation stops offering plumbing as surface.** `docs/architecture.md` listed types
  by module in §1, §3, §4 and §16; 55 of the 112 types the census reported as *documented but
  named by no user code* were named by that one page. Its inventories are replaced by the seams
  a user actually names, and the behaviour section now points at the guide. Eleven behaviour
  pages are removed with their nav entries, `docs/behaviors/README.md` covers the three
  surviving types plus what is deliberately not a behaviour, and the filtering guide is written
  against `MessageFilter`'s own combinators rather than the ext duplicates.

- **`CONTRIBUTING.md` taught an API removed at 0.22.0.** Its canonical "Good" sample and the
  test beneath it both called `messageService.send(...)`; no `MessageService` type has existed
  in the tree for six releases. Both are rewritten against `MessageDispatcher`.

- **`tools/api-census.sh --check` audits the removal schedule and exits non-zero.** It reports
  two failures: a declared release at or below the version being built, and — the worse one —
  `forRemoval = true` with no release declared anywhere, which can never become overdue and so
  would never be flagged. The report the script prints without the flag is unchanged. Nothing
  runs the check automatically; it is a command you can run, not a gate in CI.

### Removed

- **`dev.agenor.core.AgentDirectory` is gone.** Change the import to
  `dev.agenor.core.directory.AgentDirectory` — same simple name, same methods, same behaviour,
  only the package path differs. This is the release's one breaking change.

  It matters beyond the one interface. The facade was deprecated at 0.22.0 with a Javadoc
  promising removal at 0.24.0, and it was still shipping at 0.28.0. This release takes the count
  of surface scheduled for removal from 7 to 45 on the argument that the number measures a
  decision rather than an intention; postponing an overdue removal a fifth time while making
  that argument would have been the release contradicting its own thesis.

  Nothing is lost with it. The five `default` bridge methods it carried are overridden concretely
  in both `InMemoryAgentDirectory` and `CompositeAgentDirectory`, and
  `JdbcAgent{Resolver,Discovery,Presence}` implement the capability interfaces directly — no
  implementation ever inherited a default. Its `heartbeat` default was read-then-write and its
  own Javadoc admitted the race; the implementations do that touch atomically under
  `computeIfPresent` (ADR-028). Removing the facade removed a documented race rather than
  introducing one.

  If you reach the directory through `AgenorRuntime.getAgentDirectory()`, nothing changes for
  you: the accessor returned the deprecated type only because `AgenorRuntime` imports
  `dev.agenor.core.*`, and it now returns the replacement.

- `AgentContextExample` and `WebConsoleExample`, whose only reason to exist was to demonstrate a
  type that the framework itself depends on. The types stay; the demonstrations were the census's
  *plumbing with a demo* verdict, and deleting them is what that verdict asks for. The
  demonstrations of the newly deprecated behaviours stay until 0.30.0, with the APIs they show.


## [0.27.0] - 2026-08-25

### Added

- **A first-agent path that fits in fifteen minutes.** `QuickStartExample` plus
  [Your First Agent](docs/first-agent.md): one agent asks, another answers, and the page states
  the number of concepts it costs — six — so that the count can be defended rather than
  assumed. `docs/getting-started.md` now sends a newcomer there instead of to a
  production-style FSM orchestration example.

- **`tools/api-census.sh`** — counts, for every public type in the runtime modules, who names it
  in code and who documents it, and separates a *use* from a *demonstration*: an example whose
  only reason to exist is to show an API off is not evidence anyone needs it. It reads the tree
  only — no build, no network — and writes its report to stdout.

- **Every `BaseAgent` now has a mailbox, and it owns the agent's inbound path (ADR-032).**
  An agent that also spoke the dialogue protocol subscribed to its own recipient channel
  **twice** — once from `BaseAgent.autoSubscribeDirectMessages()`, once from
  `DialogueCapability.doInitialize()` — and what happened next depended on which dispatcher
  was wired in. `DefaultAgentMailbox` registers the agent's single persistent
  `subscribeRecipient` and drains it with one consumer: a message carrying a known
  performative goes to `@DialogueHandler`, everything else to `@AgenorMessageHandler` /
  `onDirectMessage()`. Routing happens once, in one place, and identically on every transport.

  `AgentMailbox`, `MailboxConfig` and `OverflowPolicy` live in `agenor-core`;
  `DefaultAgentMailbox` in `agenor-runtime`. No new SPI and no runtime accessor (ADR-027 C1
  and C2): the mailbox is reached through the agent that owns it, via `BaseAgent.mailbox()`,
  which is typed on the contract. Two protected hooks on `BaseAgent`, both read once at
  start: `mailboxConfig()` for a different capacity or policy, and `createMailbox()` to
  supply a different `AgentMailbox` altogether — the only place the runtime's implementation
  is named.

  The mailbox is `BaseAgent`'s. The `Agent` interface declares no message handling, so there
  is no lane for a drain to route non-dialogue traffic into; an agent implemented directly
  against `Agent` keeps whatever inbound path it arranges for itself, and `DialogueCapability`
  still subscribes on its behalf. Nothing changes for such agents — the framework never gave
  them a recipient subscription.

  ```java
  @Override
  protected MailboxConfig mailboxConfig() {
      return new MailboxConfig(4096, OverflowPolicy.REJECT);
  }
  ```

  Transient subscribers are unaffected: correlated request/reply helpers such as
  `AgenorA2AAdapter.sendInternal()` still subscribe for the life of one request. The
  invariant is one *persistent* subscription per agent, not one subscription ever.

  See [ADR-032](docs/adr/ADR-032-agent-mailbox-single-inbound-path.md) and
  [the mailbox guide](docs/mailbox.md).

### Changed

- **A handler that throws is now retried instead of being swallowed (ADR-033).** ADR-032 gave
  the mailbox the agent's inbound path and placed delivery semantics out of scope, on the
  reading that the mailbox added none. What it did was *end* the chain the Redis adapter
  provides and documents: `BaseAgent` handed the consumer loop
  `MessageHandler.sync(box::offer)`, which returns as soon as the message is queued, so every
  message was acknowledged at enqueue and a failing handler ran on a detached thread where its
  exception was logged and forgotten. With the default `DROP_OLDEST` policy, an overflowing
  mailbox acknowledged and discarded messages with only a warning.

  `AgentMailbox.offer` now returns `CompletableFuture<Void>` completing when the *handler*
  completes, so the transport acknowledges after processing. A failing handler is redelivered
  and reaches the dead-letter stream; so does a message dropped by overflow. Two further
  changes come with it: concurrent handlers per agent are bounded
  (`MailboxConfig.maxConcurrentHandlers`, default 64) — which is the backpressure the queue
  bound alone never provided, since it sat in front of an unbounded thread spawn — and `stop()`
  waits for handlers already running before discarding what is left.

  `BaseAgent.handleDirectMessage` also stopped swallowing `@AgenorMessageHandler` failures; it
  was the second place the chain ended, and without it the headline case stayed broken.

  *Migration*: **make handlers idempotent** — a failing one will see the same message again. An
  agent that wants a failure contained should catch it in the handler. `AgentMailbox` and
  `MailboxConfig` were introduced in this same unreleased version, so no published API changes.

  See [ADR-033](docs/adr/ADR-033-mailbox-delivery-semantics.md).

- **BREAKING — a dialogue message no longer reaches `onDirectMessage()`.** ADR-029 D3
  accepted that fallthrough because `BaseAgent` "has no way to know whether dialogue is
  active without a coupling that does not exist today". The drain creates that knowledge in a
  legitimate place — it already sees both consumers — so each message is now routed to
  exactly one of them. ADR-029 is amended **in scope**: its D1, D2 and D4–D6 are untouched and
  its Status stays `Accepted`.

  *Migration*: an agent that overrides `onDirectMessage()` and watches its own dialogue
  traffic there will stop seeing it. Move that logic to a `@DialogueHandler`. Agents that
  filtered with `isDialogueMessage()` inside `onDirectMessage()` can simply drop the filter
  and the branch it guarded.

- **Inbound messages are now claimed in arrival order.** In-memory delivery started a virtual
  thread per handler per message with no sequencing, so two messages sent a microsecond apart
  could be processed in either order, while the Redis transport was already FIFO per node —
  the same program behaved differently depending on configuration. The drain claims one
  message at a time.

  The guarantee is **claim order, not completion order**: handler invocation is still
  dispatched onto a virtual thread, so handlers may overlap and finish out of order. Agents
  must keep guarding shared state. Running handlers on the drain thread would give full
  ordering at the cost of letting one slow handler stall its agent's whole queue.

- **A new failure mode: mailbox overflow.** The receive side is bounded for the first time —
  1024 messages by default, dropping the oldest with a WARN. An agent whose producers outrun
  it now drops messages visibly where it previously accumulated an unbounded backlog. Set
  `OverflowPolicy.REJECT` to fail the sender with `MailboxOverflowException` instead, or
  raise `capacity`. Blocking the producer is deliberately not offered: it may be a transport
  consumer loop serving an entire node.

### Deprecated

- **`ReflectionBehavior`, `CircuitBreakerBehavior`, `PipelineBehavior` and `LLMMemoryQuery`**,
  for removal in 0.29.0. The API census (`tools/api-census.sh`) found no code naming the three
  behaviours except the example written to demonstrate each one, while six to eight user-facing
  pages describe them; `LLMMemoryQuery` is referenced by nothing at all.
  Circuit breaking is a resilience pattern rather than an agent concept and dedicated libraries
  do it better; `SequentialBehavior`, which has real callers, already covers ordered stages.
  Each demonstration example is removed together with the API it demonstrates, in 0.29.0.

### Fixed

- **Redis redelivery had never worked; `pendingEntriesTimeoutMs` was read by nothing.**
  `ConsumerLoop` issued `XREADGROUP` with a `lastConsumed` offset and nothing else, which
  returns only entries nobody has read yet. An unacknowledged entry therefore stayed in the
  Pending Entries List forever: `XAUTOCLAIM` was called nowhere in the adapter, so
  `maxDeliveryAttempts` and the dead-letter hop in `processMessage` were unreachable — reaching
  them requires seeing the same entry twice, and nothing ever delivered an entry twice. The
  documented failure-mode chain in `docs/adapters/redis.md` described behaviour that did not
  exist, before ADR-032 as much as after.

  The loop now runs a reclaim pass, no more often than `pendingEntriesTimeoutMs`, issuing
  `XAUTOCLAIM` from `0-0` with `minIdleTime = pendingEntriesTimeoutMs` and reprocessing what it
  reclaims. This also delivers the restart recovery the adapter already promised: a node picks
  up its own pending entries again after a crash.

  Found by writing the integration test for ADR-033 first — repairing only the mailbox would
  have restored a chain whose far end was missing.

- **Annotated methods inherited from a base class or an interface were silently ignored.**
  `AgentAnnotationProcessor` scanned with `getDeclaredMethods()`, which stops at the class
  itself, so an agent inheriting a `@Behavior` or an `@AgenorMessageHandler` from an abstract
  base got neither registered — with no warning, no error, and an agent that simply never
  receives anything. Putting shared handlers on a base agent is the natural way to write a
  family of agents, and it did not work.

  This is the defect 0.26.0 fixed for `@DialogueHandler`; the other two annotations still had
  it. The hierarchy walk that fix introduced is now extracted to
  `dev.agenor.runtime.support.MethodHierarchy` and used by both scanners, so the rule lives in
  one place — and it now covers **interface `default` methods** as well, which the original
  fix did not: a capability interface carrying shared handlers works for `@DialogueHandler`,
  `@Behavior` and `@AgenorMessageHandler` alike.

  The order is classes first, most-derived first, then interfaces, most-derived first, with
  each method taken once at the first declaration found and synthetic and bridge methods
  skipped. So a class declaration wins over an interface default it overrides, a subclass
  override wins over the method it overrides, nothing is registered twice, and dispatch stays
  virtual — even a base-class or interface `Method` runs the most-derived implementation.

  One rule follows from that, and Java rather than Agenor imposes it: **the most-derived
  declaration decides**. Method annotations are not inherited, so overriding an annotated
  method without re-annotating it removes it from the scan's view, whether it came from a base
  class or an interface. Only `default` methods are taken from interfaces: an abstract one has
  no body, and its implementation is seen first anyway.

- **An Ollama integration test reported a failed model pull as a success.** `pullModel` checked
  only the status code of `POST /api/pull`, and Ollama answers 200 even when the pull fails —
  the failure arrives in the body. A failed pull therefore printed "Model pull initiated
  successfully", the helper slept a fixed 30 seconds, and the test that followed died on
  `model 'qwen2.5:0.5b' not found`, accusing `OllamaProvider` of a defect that belonged to the
  pull. The cost is not the red test: the failure stops the reactor before the integration
  tests a release is actually about ever run, so a release can be "verified" by a suite that
  never executed.

  The request now asks for `stream=false`, so the response arrives when the pull has finished
  rather than when it has started. The body is checked for an error, the model is confirmed
  present via `/api/tags` before returning, and a failure throws with the URI and the cause
  instead of being printed to stderr and swallowed. The helper also returns early when the
  model is already there, so its five callers no longer each pay for it: the class runs in 50s
  where it took 207s.

## [0.26.0] - 2026-08-23

### Added

- **Agent presence over JDBC, and something to drive it (ADR-028 Phase A).**
  `JdbcAgentPresence` completes the JDBC directory's fourth capability, so a deployment can
  be entirely JDBC-backed instead of pairing durable registration with an in-memory presence
  that dies with the node. No new table, column or migration: `agenor_agents` already carries
  `status` and `last_seen`. `heartbeat` touches only `last_seen`; `getStatus` answers
  `UNKNOWN` when the row is missing **or** older than the staleness window, checked at read
  time because a relational store has no key expiry. Reach it via
  `JdbcAgentDirectory.presence()` (90-second window) or `presence(Duration)`.

  Nothing in the framework had ever called `heartbeat` — one delegating call site and no
  scheduler anywhere — so a backend that expires entries would have reported `UNKNOWN` for
  every agent one window after start-up. `AgentHeartbeatDriver` closes the loop: one daemon
  thread per runtime, beating for the agents it is actually running, and **off unless an
  interval is configured**. Heartbeats are writes, and turning them on for every existing
  deployment unasked is the write volume ADR-023 objected to.

  ```java
  AgenorRuntime.builder()
      .agentPresence(dir.presence())
      .heartbeatInterval(Duration.ofSeconds(30))   // required, or presence goes UNKNOWN
      .build();
  ```

  ```yaml
  agenor:
    directory:
      provider: jdbc
      presence: jdbc            # opt-in, separate from provider
      heartbeat-interval: 30s
      staleness-window: 90s     # optional; see below
  ```

  An unset `staleness-window` is derived: three times the heartbeat interval, or —
  when nothing is configured to heartbeat — **unbounded**. That default is deliberate. A
  bounded window with no driver behind it fails silently, reporting every agent as `UNKNOWN`
  on a healthy cluster with nothing in the logs to explain it; unbounded instead reports what
  the registry last wrote, exactly as the in-memory backend always has. Programmatically, the
  same escape hatch is `JdbcAgentPresence.UNBOUNDED_STALENESS_WINDOW`.

  Detection is coarse by construction — tens of seconds, not milliseconds — and only as
  trustworthy as the clock agreement between the writing and reading nodes. A `last_seen` in
  the future reads as fresh, so a fast clock costs visibility rather than producing a false
  liveness claim. Redis TTL presence is designed in ADR-028 Phase B and not built.
  See [ADR-028](docs/adr/ADR-028-agent-presence-jdbc-and-redis.md) and
  [the JDBC directory guide](docs/adapters/jdbc-directory.md#presence-and-heartbeats).

- **The four directory contract suites moved to `agenor-core`** and are published as a
  `test-jar` (ADR-028 D-6). `InMemoryAgentDirectoryContractTest` promised that "future
  adapters (Redis, JDBC) follow the same pattern", but the suites lived in `agenor-runtime`
  and no `test-jar` existed anywhere in the reactor, so no adapter could keep that promise.
  `AgentPresenceContractTests` is now typed on the capability rather than on `AgentDirectory`,
  and `JdbcAgentPresenceTest` runs it — one source of truth for what presence means.

- **`CrossRuntimeExample` and `CrossRuntimeDialogueIT`** (`agenor-examples`): two isolated
  `AgenorRuntime` instances — separate dispatchers, directories and connection pools, sharing only
  a PostgreSQL and a Valkey — exchanging a typed REQUEST/INFORM. Every dialogue test before this
  used the in-memory dispatcher, where `sendTo` resolves the recipient in a local map and the
  message never leaves the JVM; nothing exercised the path where the directory has to tell one
  node how to reach another. That is precisely the gap the three defects below hid in.
- **`docs/distributed-quick-start.md`** and a root **`compose.yml`** (PostgreSQL + Valkey). The
  page states the rule that was written down nowhere: the transport's `nodeId` is what the
  directory advertises and what peers route to, and any disagreement between the two loses
  messages without an error. `docs/adapters/redis.md` and `RedisMessagingExample` referred to a
  compose file that did not exist.


- **`Message.getContent(Class<T>)` now actually converts** (ADR-030): it was an unchecked cast
  that did nothing, so it read like the answer to the payload-typing problem and was not.
  `Message.content` is declared `Object` and carries no type information on the wire, so the
  in-memory dispatcher hands the receiver the sender's original object while a serialising
  transport such as Redis hands it a `LinkedHashMap` rebuilt from JSON — a `ClassCastException`
  that no in-memory test can reproduce and that every dialogue test therefore missed. The
  accessor now returns the same reference when the content is already of the requested type
  (no allocation, no Jackson), converts it when it arrived as a `Map`, and throws
  `IllegalArgumentException` naming both types when it can do neither. `DialogueMessage` gains
  `contentAs(Class<T>)`, delegating to the same implementation, and `toMessage()` writes the
  payload's Java type into a `content-class` header — informational only, since conversion is
  driven by the type the caller asks for and the wire format stays language-neutral.
  Deliberately **not** named `content-type`: that key already means a domain label in ADR-005's
  own example and an HTTP-style media type in `docs/message-filtering.md`.
  **Limitation, by design**: conversion is lenient about unknown properties so that a receiver
  keeps reading a payload whose sender added a field. Reading a post-transport payload as an
  *unrelated* record therefore succeeds with null fields instead of throwing. The accessor
  removes the `ClassCastException`, not the need to ask for the right type.
  `MessageFilterBuilder.contentType(Class)` has the same root cause — it filters with
  `instanceof` and silently stops matching after a serialising transport — and is knowingly
  left unfixed until someone needs it.
- **Optional-module diagnostics at startup** (ADR-027 § Amendment 2026-08-15): the ADR-027 seam
  degrades silently — without `agenor-runtime-llm` a `@WithGuardrails` agent runs unguarded, and
  nothing says so. `AgenorRuntime.start()` now logs one INFO line naming the optional modules that
  resolved (`ServiceLoader` finding nothing looks exactly like finding everything from the
  outside), and one aggregated WARN per annotation type that no loaded extension claims, naming
  the affected agents. `AgentRegistrationExtension` gains a diagnostics-only default method
  `handledAnnotations()`, so each module declares what it processes and the runtime needs no
  annotation→artifact map; `agenor-core` contributes only `OPTIONAL_FEATURE_ANNOTATIONS`, a list
  of its own types. Existing extensions keep compiling and claim nothing.
  The shipped extensions also report the case the runtime cannot see: `@WithGuardrails` on an
  agent that is not an `LLMAgent`, and `@RequiresApproval` on one that is not a `BaseAgent`, do
  nothing even with the module present — each extension now says so.
- **`LifecycleHooks` (`agenor-core`)**: `onStartHook`/`onStopHook` extracted from the
  concrete `BaseAgent`, so framework capabilities can wire themselves to an agent's
  lifecycle without downcasting. Deliberately kept out of the `Agent` interface — hook
  support stays opt-in. `PersistenceManager` migrated to it, dropping its cross-module
  `instanceof BaseAgent`.
- **Injection seam for dialogue collaborators**: `DialogueCapability.builder(agent)` accepts
  a `ConversationManagerFactory`, a `ProtocolRegistry` (so a custom `Protocol` can be
  registered without bypassing `DialogueCapability`) and a `CommitmentTracker`. The
  capability now stores and exposes the interfaces, not the default implementations, as
  required by ADR-002 — a persistent or distributed `ConversationManager` no longer requires
  forking the class. `ConversationManager` gains `getCommitmentTracker()` and
  `CommitmentTracker` gains `getByMessageId(String)`.
- **Commitment deadline violations are detected automatically**: `checkViolations()` existed
  but nothing ever called it, so a commitment whose deadline passed stayed `ACTIVE` forever
  unless the consumer wrote its own polling loop — "observable promises" that nobody observed.
  The retention sweep introduced above now runs it, so an overdue commitment moves to
  `VIOLATED` on its own and is logged at WARN naming performer, requester and conversation.
  Detection is delayed by up to one sweep interval (default one minute), configurable via
  `DialogueCapability.builder(agent).sweepInterval(...)`. The sweep prunes *before* it
  violates, so a commitment marked violated survives at least one full interval and stays
  observable through `get(commitmentId)`.

### Changed

- **BREAKING — a heartbeat signals liveness, not progress (ADR-028 D-1).**
  `AgentPresence.heartbeat` used to promote the agent to `RUNNING`:
  `InMemoryAgentDirectory.heartbeat` was literally `updateStatus(agentId, RUNNING)`. It now
  refreshes `lastSeen` and leaves `status` untouched, so an agent stuck in `STARTING` keeps
  reporting `STARTING` however many heartbeats it sends — which is the truth, and the whole
  point of asking. A single heartbeat could previously report an agent as running before it
  had finished starting.

  **Migration**: call `updateStatus(agentId, AgentStatus.RUNNING)` explicitly wherever a
  heartbeat was relied on to make that transition. The deprecated `AgentDirectory` facade's
  default `heartbeat` was changed the same way. Nothing in this repository depended on the old
  behaviour; the only assertion that did was the contract test that specified it.

- **`getStatus` may now answer `UNKNOWN` for a registered agent.** Staleness is part of the
  `AgentPresence` contract: an agent not seen within the backend's staleness window reads
  `UNKNOWN`. `InMemoryAgentDirectory`'s window is unbounded, so the default runtime is
  unaffected and behaves exactly as before — this is visible only on a backend that expires,
  which today means JDBC presence with a heartbeat interval configured.

- **Integration tests now actually run.** The build had no `maven-failsafe-plugin`, and surefire's
  default includes do not match `*IT.java`, so the four existing integration tests
  (`JdbcAgentDirectoryIT`, `JdbcApprovalGateIT`, `RedisMessageTransportIT`,
  `RedisTopicPublisherIT`) had been compiled and never executed. Failsafe is now bound in the
  parent POM. Each test keeps its `@EnabledIfSystemProperty(named = "integration.tests.enabled")`
  guard, so `mvn verify` is unchanged; `mvn verify -Dintegration.tests.enabled=true` runs them and
  requires Docker.
- `DialogueCapability.request(...)` Javadoc corrected: it promised "first-reply, typically AGREE"
  semantics, which stopped being true when ADR-026 moved resolution to the final `INFORM`/
  `FAILURE`. The implementation was right; only the contract as documented was stale.


- **`DialogueCapability` wires itself into the agent lifecycle**: constructing it registers
  `initialize()`/`shutdown()` on the agent's `LifecycleHooks` when the agent supports them
  (`BaseAgent` does), so agents no longer need `onStart()`/`onStop()` overrides for dialogue.
  Agents implementing `Agent` directly get a warning naming what leaks if they never call
  `shutdown()`. `initialize()` is idempotent and resolves the dispatcher from the agent
  itself. Every public method now fails with an actionable `IllegalStateException` instead
  of a bare `NullPointerException` when the capability was never initialized, and
  `shutdown()` clears the manager so a stopped agent can be started again.
  `getCommitmentTracker()` returns `CommitmentTracker` rather than the concrete
  `DefaultCommitmentTracker`.
- Dialogue examples and the docs quick-starts no longer show manual `onStart()`/`onStop()`
  dialogue wiring; `dialog-protocol.md` now states explicitly that conversation state is
  per-agent, in-memory and node-local, and what an agent without `LifecycleHooks` must do.
- **The durability contract for dialogue state is now written down** (ADR-031). It was
  unspecified, and the "node-local" phrasing added above could be read as "dialogue only works
  within one node" — which is false and now stated as such. The two axes are separated
  explicitly: agents in **different runtimes can hold a dialogue** whenever the transport spans
  them (Redis messaging, A2A), while an agent's **own conversation state does not survive its
  own process** — by design, not by omission. `dialog-protocol.md` gains a restart/failover
  section (the peer waits out its own timeout, in-flight commitments are lost and will never be
  marked `VIOLATED`, a pending future cannot be recovered by any store), and `Conversation` and
  `Commitment` carry the same contract in their Javadoc for anyone reading only the API. No
  persistent `ConversationManager` is shipped: the factory seam added in this release means one
  can be added later without changing agent code, so building it before an adopter needs it
  would buy nothing — and it would not fix the part that actually breaks on restart.

### Deprecated

- `DialogueCapability.initialize(MessageDispatcher)` (since 0.26.0, for removal) — use the
  no-arg `initialize()`. The overload still honours the dispatcher passed to it, so existing
  call sites keep working unchanged.

### Fixed

- **A HITL decision could be announced to other nodes without being stored.**
  `JdbcApprovalGate.submit` reads the request, finds it `PENDING`, then writes — and the
  timeout scheduler writes to the same row in between. The write itself was always guarded
  (`UPDATE ... WHERE status = 'PENDING'`), but `submit` ignored whether it matched: it
  completed the local future and emitted the Postgres `NOTIFY` regardless. The future was
  safe by accident, since `CompletableFuture.complete` returns false on an already-completed
  future, but every other node received an approval for a request whose row said `EXPIRED` —
  a decision existing only as a notification — and the submitting caller was told nothing was
  wrong. `persistDecision` now reports whether it matched and `submit` stops when it did not.

- **A timed-out HITL approval could be reported before the store agreed.**
  `JdbcApprovalGate.scheduleTimeout` completed the caller's future exceptionally and *then*
  marked the row `EXPIRED`. The write is synchronous, but it runs on the scheduler thread
  after the caller has already been released, so anyone who read the request straight after
  catching `ApprovalTimeoutException` could still see `PENDING` — and on another node, so
  could a reader with no exception to go on. The row now decides, and decides first: a
  conditional `UPDATE ... WHERE status = 'PENDING'` runs before the future is completed, and
  the timeout is only reported if that update matched. A decision submitted just before the
  deadline therefore wins outright instead of racing. Surfaced by `JdbcApprovalGateIT` under
  a loaded full build, where the window widens enough to lose.

- **The PostgreSQL integration tests did not wait for PostgreSQL.** All three used
  `GenericContainer` with the default wait strategy, which only checks that the port is
  listening. The container's entrypoint runs a temporary server for `initdb` first, so a
  client connecting in between is refused with `FATAL: the database system is starting up`.
  They now wait for the real server's readiness line.

- **`agenor.directory.provider=jdbc` never started a Spring context.** The Spring Boot starter
  exposes the runtime's `AgentDirectory` as a bean, and `AgentDirectory` extends all four
  capability interfaces — so it also answers to `AgentRegistry`, `AgentDiscovery` and
  `AgentResolver`. Alongside the JDBC capability beans that gave two candidates for every
  capability, and the JDBC runtime factory could not be constructed: the context failed to refresh
  with `NoUniqueBeanDefinitionException`, before a single agent existed. The JDBC beans are now
  `@Primary`, resolving injection in favour of the backend the user configured. Nothing had ever
  exercised the property — the starter's only JDBC test covered the HITL queue — so ADR-023's
  headline feature had never worked from Spring. Found while wiring ADR-028's presence property.

- **A failed agent registration could be reported as a success.** `JdbcAgentRegistry.register`
  inserts first and falls back to an `UPDATE` when the row already exists, and it asked
  `JdbcHelper.isUniqueViolation` whether the failure was a duplicate key. That method matched the
  entire ANSI `23xxx` integrity-constraint class, so a NOT NULL violation (`23502`) or a foreign-key
  violation (`23503`) also looked like "the agent is already registered": the insert failed, the
  fallback update matched no rows, the transaction committed empty, and the returned future
  completed normally. Registering a descriptor without an `agentType` — a NOT NULL column — stored
  nothing and told nobody. The check is now a duplicate-key check: `23505` on PostgreSQL and H2, or
  `23000` with vendor code 1062/1586 on MySQL, which collapses the whole class into one SQLState.
  Genuine constraint failures now surface as a failed future. Found while writing the JDBC presence
  tests for ADR-028.

- **`@AgenorMessageHandler` direct delivery was dead on the Redis transport.** An agent
  subscribes to its own recipient channel more than once — `BaseAgent.autoSubscribeDirectMessages()`
  always, plus `DialogueCapability` when dialogue is enabled — and
  `RedisMessageDispatcher.directHandlers` was a `Map<String, MessageHandler>` written with `put`.
  Dialogue registers from a start hook, which runs *after* the agent's own subscription, so on
  Redis it silently replaced `BaseAgent.handleDirectMessage`: every `@AgenorMessageHandler`
  method on a dialogue-enabled agent stopped being invoked for direct messages. Unsubscribing
  either capability also revoked whichever handler happened to hold the slot. The routing table
  now holds a list per agent and delivers to every handler, matching `InMemoryMessageDispatcher`;
  `unsubscribe()` removes only its own handler. Found while writing ADR-032, which removes the
  double subscription entirely — this fix restores the documented contract in the meantime.

- **An agent's endpoint was discarded the moment it started.** `AgenorRuntime.registerAgent`
  stamped the dispatcher's endpoint onto the descriptor, but `BaseAgent.start()` re-registers
  itself as `RUNNING` from its own descriptor, which it rebuilt field by field — without the
  endpoint. The correct row survived milliseconds before being overwritten with a blank
  `node_id`. Compounding it, `AgenorRuntime` never handed the descriptor it had just built to the
  agent, so an agent registered as a plain instance (rather than created by `AgentFactory`) also
  overwrote its annotation-derived `agentType` and capabilities with the bare defaults from its
  constructor. `registerWithDirectory()` now carries the endpoint over, and `registerAgent` calls
  `setAgentDescriptor(...)`. The pre-existing unit test passed throughout because it asserted the
  directory contents after `registerAgent` and before `start()`.

- **`JdbcAgentRegistry.register()` could not re-register an agent on PostgreSQL.** The upsert
  attempts an `INSERT` and falls back to `UPDATE` on a unique violation, but PostgreSQL aborts the
  entire transaction on any failed statement: the expected primary-key conflict poisoned the
  connection, and the `UPDATE` — plus the capability sync after it — failed with *"current
  transaction is aborted"*. Every re-registration therefore failed on the database the adapter
  exists to support, while passing on the H2 used by the unit tests. The `INSERT` now runs inside
  a savepoint. `JdbcAgentDirectoryIT.upsertOnDuplicate` covers exactly this and would have caught
  it — it had never been executed, for the reason described under *Changed*.

- **Cross-node delivery silently dropped every message when a persistent directory was combined
  with a networked transport.** Nothing in the registration path ever populated
  `AgentDescriptor.endpoint`: `AgentDescriptors.create()` leaves it null, and
  `InMemoryAgentDirectory.register()` quietly compensated by synthesising
  `AgentEndpoint.local(nodeId)` from its own node id. `JdbcAgentRegistry` has no such
  compensation, so it stored `node_id = ""` — and `RedisMessageDispatcher.sendTo()`, which routes
  on the resolved endpoint's node id, then wrote to the stream `<prefix>:node:` that no consumer
  loop reads. The message vanished with **no exception and no warning**. No test caught it
  because no test combined the two: each was exercised only against its in-memory counterpart.
  A dispatcher that knows how its own node is addressed now says so, through the new opt-in
  `LocalEndpointProvider` (`agenor-core`), and `AgenorRuntime` stamps that endpoint on the
  descriptor before registering it. `RedisMessageDispatcher` implements it, advertising the very
  node stream its own consumer loop reads — deriving the value from anywhere else would let the
  two drift, and a drift has exactly the same silent symptom. `InMemoryMessageDispatcher`
  deliberately does **not** implement it: it has no node identity worth publishing, so
  single-node behaviour is byte-for-byte unchanged. `AgentDescriptor` gains `withEndpoint(...)`
  for the hand-off.

- **Two lost-update races in the dialogue runtime**: a conversation is mutated from arbitrary
  threads — `InMemoryMessageDispatcher` delivers every message on its own virtual thread with no
  per-recipient serialisation, and the timeout path writes from another — while
  `DefaultConversation` performed `state = protocol.nextState(state, ...)` as an unguarded
  read-modify-write over a `volatile` field. Two concurrent deliveries could each transition from
  the same state, discarding one; more damagingly, a `setState(TIMEOUT)` could be overwritten by a
  transition computed from the state that preceded it, leaving a conversation that looks live
  after its own timeout has fired — and therefore still eligible for the ADR-026 resolution gate.
  The history append, the ADR-029 validation and the transition now happen under one lock;
  `state` and `lastActivity` stay `volatile` so readers need not take it.
  `DefaultConversationManager.handleIncoming()` had a second, distinct race: a check-then-act
  (`get` → null → `new` → `put`) on the conversation map. Two messages opening the same unknown
  conversation built two objects and kept whichever `put` ran last, so the message written to the
  discarded one was **lost outright** — not a wrong state, a missing message. Replaced with
  `computeIfAbsent`. Both races are covered by looped concurrency tests that fail without the fix.

- **Protocol violations were silently absorbed** (ADR-029): `Protocol.isValid()` existed but
  nothing ever called it, so a peer sending a performative the protocol does not allow in the
  current state produced no state change, no error and no log line — the framework detected the
  malformed exchange and discarded the finding. `DefaultConversation.addMessage()` now checks
  each message and reports a violation at WARN, naming the conversation, the sender, the
  performative, the state and what that state allows. Nothing else changes: the message is still
  appended to the history and the transition still runs, so this is observability, not
  enforcement.
  The check uses the **sender's** perspective — `allowedPerformatives(state, isInitiator)`
  answers "what may this party send now?", so validating an inbound reply against the local
  agent's own role would flag every legitimate exchange.
  Expect new warnings in two legitimate-but-late cases: a reply arriving after the initiator's
  timeout finds the conversation in `TIMEOUT`, and one arriving after `cancel()` finds it in
  `CANCELLED`; neither state allows anything. The report is correct — the reply genuinely arrived
  out of protocol.
- **`RequestProtocol` contradicted itself** (ADR-029): `nextState()` routed
  `AWAITING_RESPONSE` + `FAILURE` to `FAILED`, but `allowedPerformatives(AWAITING_RESPONSE,
  isInitiator = false)` omitted `FAILURE`. A responder failing outright without first agreeing —
  which `DialogueCapability.failure(...)` supports — followed a transition the same state machine
  declared invalid. Harmless while `isValid()` was dead; a false violation report once it is
  enforced. `FAILURE` is now listed, and a test asserts the two halves of the FSM agree for every
  performative.
- **Plain direct messages were reinterpreted as dialogue** (ADR-029): an agent's recipient
  channel carries every message sent to it, and a `BaseAgent` with dialogue holds two
  subscriptions on it — its own and the capability's. `DialogueMessage.fromMessage()` defaults a
  missing `performative` header to `INFORM` and generates a `conversationId`, so an ordinary
  `sendTo()` message became a synthetic `INFORM` in a conversation nobody started: it was
  dispatched to any matching `@DialogueHandler`, and it added a permanent entry to the
  conversation map. **This was a fourth resource leak, and the retention sweep added above could
  not reclaim it** — such a conversation never reaches a terminal state, and the sweep only
  removes terminal ones. `DialogueCapability` now classifies inbound traffic with the new
  `DialogueMessage.isDialogueMessage(Message)` and leaves everything else to the direct-message
  path. A message whose `performative` header holds an unrecognised value is likewise not routed
  to dialogue — it is logged at WARN instead of being executed as an `INFORM` its sender never
  sent. `fromMessage()` itself is unchanged, so callers converting a reply they are already
  waiting for (the A2A adapters) are unaffected. Note that a dialogue message still reaches
  `onDirectMessage()`: an agent *without* dialogue must keep receiving it, so `BaseAgent`
  deliberately does not filter.
- **Dialogue Protocol resource leaks**: three maps in the dialogue runtime grew for the
  entire lifetime of an agent. `DefaultConversationManager` only removed a pending-response
  future on the success path, so every timed-out `request()`/`query()` leaked an entry and
  `callForProposals()` leaked one per participant that never proposed — the exact failure
  mode Contract-Net is built to tolerate. `conversations` and `commitments` were never
  pruned at all: `getActiveConversations()` filters the view, not the map, and
  `DefaultCommitmentTracker.cleanup(Duration)` existed but nothing ever called it (and it
  left its `messageId -> commitmentId` index behind). Terminated conversations and
  commitments are now swept periodically — default retention 5 minutes, sweep every
  minute, both configurable via `DialogueCapability.builder(agent)` — by a single daemon
  thread per agent, started in `initialize()` and stopped in `shutdown()`.
  `ConversationManager` and `CommitmentTracker` gain a `default` no-op `cleanup(Duration)`
  so store-backed implementations with their own expiry are unaffected.
  Investigated and **not** present: the suspected per-task subscription leak in
  `AgenorAgentExecutor` — `execute()` already unsubscribes in a `finally` block.
- **`@DialogueHandler` methods inherited from a base class were silently ignored**:
  `DialogueHandlerRegistry.scan()` used `getDeclaredMethods()`, so an `AbstractWorkerAgent`
  holding shared handlers registered zero handlers for its concrete subclasses, with no
  warning. The scan now walks the class hierarchy, de-duplicating by signature so an
  override is registered once and wins over the method it overrides. `scan()` also clears
  its state first, so scanning twice can no longer double-dispatch.

### Migration Guide (0.25.x → 0.26.0)

> Concerns you only if you **implement** the dialogue core interfaces yourself, or if you
> declared a dialogue collaborator with its concrete type. Code that uses `DialogueCapability`
> on a `BaseAgent` needs no changes — the examples in this repo were migrated by deleting
> lifecycle wiring, not by adding any.

**Signature changes**

| Change | Breaks |
|---|---|
| `ConversationManager.getCommitmentTracker()` — new abstract method | external *implementors* of the interface |
| `CommitmentTracker.getByMessageId(String)` — new abstract method | external *implementors* of the interface |
| `DialogueCapability.getCommitmentTracker()` now returns `CommitmentTracker`, not `DefaultCommitmentTracker` | binary always; source only if the caller declared the concrete type |
| `DefaultConversationManager.getCommitmentTracker()` — same return-type change | same |
| `DefaultConversationManager(String, MessageDispatcher, ProtocolRegistry, DefaultCommitmentTracker)` — 4th parameter widened to `CommitmentTracker` | binary; source-compatible |

```java
// Before (0.25.x)
DefaultCommitmentTracker tracker = dialogue.getCommitmentTracker();

// After (0.26.0)
CommitmentTracker tracker = dialogue.getCommitmentTracker();
```

**Behaviour changes** — these compile unchanged and only show up at runtime:

- `shutdown()` clears the conversation manager, so `getConversation(...)` after an agent stops
  now throws `IllegalStateException` instead of returning data. This is what makes a stopped
  agent restartable: the next `initialize()` builds a fresh manager and re-subscribes.
- Public methods called before `initialize()` throw `IllegalStateException` with an actionable
  message instead of `NullPointerException`.
- Terminated conversations and commitments are swept after the retention window (default 5
  minutes). Code that reads the history of a long-finished dialogue must read it sooner, or
  raise the window:

```java
private final DialogueCapability dialogue = DialogueCapability.builder(this)
    .retention(Duration.ofHours(1))
    .build();
```

- `Message.getContent(Class<T>)` converts instead of casting blindly (ADR-030), and throws
  `IllegalArgumentException` — not `ClassCastException` at the use site — when it cannot. This
  is a widening: content already of the requested type still comes back as the same reference,
  and a call that used to blow up later either succeeds now or fails immediately with a message
  naming both types. Nothing that worked stops working. If you catch `ClassCastException` around
  a `getContent(...)` call, that catch no longer fires.

**Recommended change** — casting `content()` is a latent bug on any transport that serialises:

```java
// Before — works in one JVM, throws ClassCastException over Redis or A2A
Bid bid = (Bid) proposal.content();

// After — works on every transport
Bid bid = proposal.contentAs(Bid.class);
```

## [0.25.0] - 2026-08-11

### Added

- **Local-first LLM backend selection for examples**: Level 4/5 LLM examples
  previously hardcoded `LLMProviderFactory.openai()`, requiring a paid
  `OPENAI_API_KEY` just to run them. `ExampleLLMProvider` now selects the
  backend via an explicit `LLM_BACKEND` env var (`ollama` default / `groq` /
  `openai` / `anthropic`), so a local Ollama server with no signup works out
  of the box, and a stale `*_API_KEY` sitting in the shell never silently
  hijacks the default. Applied across the 8 Level 4 LLM examples and
  `McpExample`'s optional LLM round-trip; warns when the selected provider
  doesn't support function calling (the Ollama adapter doesn't wire up tool
  calls). `SupportChatbotExample`'s `LLMConfig` is aligned to the same
  `LLM_BACKEND` selector (adding Groq as a fourth option), with `NONE`
  (template-based responses) kept as its zero-config default; `LLMConfig`
  now also exposes the resolved `getModelName()`/`getBaseUrl()` and fixes a
  hardcoded per-provider model switch that previously ignored `*_MODEL`
  overrides. `OpenAIProviderExample` renamed to `LLMProviderExample`
  (no longer OpenAI-specific).

### Changed

- **BREAKING — `AgenorRuntime.getApprovalService()` now returns `ApprovalHandle` instead of the concrete `ApprovalService` class (ADR-027)**:
  introduces a `ServiceLoader`-based SPI in `dev.agenor.core.spi`
  (`AgentRegistrationExtension`, `AgentDiscoveryEngine`, `HitlSupportProvider`,
  `DefaultLLMMemoryManagerProvider`) plus `dev.agenor.core.hitl.{ApprovalHandle,NoopApprovalHandle}`,
  so `AgenorRuntime` no longer hard-imports `agent.LLMAgent`, `guardrail.*`, `hitl.*`,
  `discovery.*`, or `memory.llm.*` directly. This is the prerequisite decoupling step for
  splitting `agenor-runtime` into `agenor-runtime-llm`/`-ext`/`-scanning` modules, so that a
  pure multi-agent-system consumer will eventually be able to depend on `agenor-runtime`
  alone with zero LLM/HITL/scanning dependencies. When no HITL provider is on the classpath,
  `getApprovalService()` returns a `NoopApprovalHandle` whose decision-submitting methods
  fail fast with `UnsupportedOperationException` instead of silently doing nothing.
  **Migration**: callers that only invoke interface methods (`approve`/`reject`/`modify`/
  `submit`/`getPendingRequests`) are source-compatible but must recompile against the new
  return type. See [ADR-027](docs/adr/ADR-027-minimal-runtime-llm-generic-split.md).

- **`agenor-runtime-llm` extracted as a new module (ADR-027, physical split step 1 of 3)**:
  `LLMAgent` (renamed `dev.agenor.runtime.agent.LLMAgent` → `dev.agenor.runtime.llm.LLMAgent`
  — **breaking change**), `memory/llm/*`, `guardrail/*`, and `reflection/DefaultReflectionStrategy`
  moved out of `agenor-runtime` into `agenor-runtime-llm`. **Migration**: consumers that use
  `LLMAgent`, guardrails, LLM memory management, or the built-in reflection strategy must add
  a new `dev.agenor:agenor-runtime-llm` dependency and update the `LLMAgent` import. A pure
  multi-agent-system consumer with no LLM usage is unaffected and pulls zero new dependencies.
  See [ADR-027](docs/adr/ADR-027-minimal-runtime-llm-generic-split.md).

- **`agenor-runtime-ext` and `agenor-runtime-scanning` extracted as new modules (ADR-027,
  physical split steps 2 and 3 of 3)**: message filtering, rate limiting, conditions,
  file-based persistence, composite/advanced behaviors (FSM, parallel, sequential, retry,
  circuit breaker, batch, scheduled, throttled, conditional, human-checkpoint), the
  human-in-the-loop implementation (`ApprovalService`, `InMemoryApprovalGate`, approval
  notifiers), the knowledge store, and `InMemoryStore` moved out of `agenor-runtime` into
  `agenor-runtime-ext`. Classpath scanning and DI-based discovery (`AgentFactory`,
  `AgentScanner`) moved into `agenor-runtime-scanning`, which depends only on
  `agenor-runtime` (see the annotation-processing fix below — `agenor-runtime-scanning` no
  longer needs `agenor-runtime-ext`). No classes were renamed — every package moved
  wholesale, so this is **not a breaking change** for code
  that already depends on the affected `dev.agenor.runtime.*` packages by their existing
  names. **Migration**: consumers using any of the above (filters, rate limiting,
  conditions, file persistence, composite/advanced behaviors, HITL, knowledge, `InMemoryStore`)
  must add a `dev.agenor:agenor-runtime-ext` dependency; consumers relying on
  `@Agent`/`@Behavior`/`@AgenorMessageHandler` classpath scanning (`scanPackage(...)`,
  `AgenorRuntime.createAgent(Class)`, or Spring Boot's `agenor.agents.base-package`) must
  add `dev.agenor:agenor-runtime-scanning` (declared `optional` in
  `agenor-spring-boot-starter`, matching the existing adapter opt-in pattern from ADR-018).
  A pure multi-agent-system consumer using only `registerAgent(...)` with no filtering,
  rate limiting, HITL, or scanning is unaffected and pulls zero new dependencies. See
  [ADR-027](docs/adr/ADR-027-minimal-runtime-llm-generic-split.md).

### Fixed

- **`@Behavior`/`@AgenorMessageHandler` silently inert for manually-registered agents when `agenor-runtime-scanning` was absent (ADR-027 amendment)**:
  annotation processing was gated behind the `AgentDiscoveryEngine` SPI purely because of
  `AnnotationProcessor`'s historical package location, even though it never used classpath
  scanning — the practical effect was that any `registerAgent(Agent)`-registered agent
  (including every example in `agenor-examples`) lost `@Behavior`/`@AgenorMessageHandler`
  processing without `agenor-runtime-scanning` on the classpath, with only a log line as
  evidence. Split `AnnotationProcessor` by actual dependency: a new
  `dev.agenor.runtime.annotation.AgentAnnotationProcessor` in `agenor-runtime` now handles
  `@AgenorMessageHandler` and the `ONE_SHOT`/`CYCLIC`/`WAKER`/`EVENT_DRIVEN`/`CUSTOM`
  `@Behavior` types unconditionally, with no optional-module dependency. The remaining
  types (`CONDITIONAL`/`THROTTLED`/`BATCH`/`RETRY`/`SEQUENTIAL`/`PARALLEL`/`FSM`, whose
  implementation classes live in `agenor-runtime-ext`) are covered by a new
  `dev.agenor.core.spi.BehaviorAnnotationExtension` SPI, implemented by
  `agenor-runtime-ext`. Unlike other ADR-027 SPI seams, using one of these ext-only types
  without `agenor-runtime-ext` present now fails `start()` loudly with a clear
  `IllegalStateException` rather than silently skipping — a `@Behavior` annotation names
  its required type explicitly, so there's no legitimate "app doesn't use annotations"
  ambiguity to justify a silent no-op. **Also removed**: `AgenorRuntime.registerAgent(Class<T>)`
  (added earlier this same unreleased cycle) — its only purpose was working around this
  bug for `Class`-based registration; with the fix, plain `registerAgent(Agent)` plus
  annotations works correctly with zero optional modules present, making the extra
  overload redundant (it also introduced a `registerAgent(null)` overload-resolution
  ambiguity, now gone). See [ADR-027](docs/adr/ADR-027-minimal-runtime-llm-generic-split.md).

- **`@AgenorMessageHandler` never fired for direct (point-to-point) messages sent via `sendTo()`/`receiverId`**:
  `AnnotationProcessor` only wired annotated handlers onto the topic pub/sub channel
  (`TopicSubscriber.subscribeTopic`), which is triggered by `publish()`. Messages sent with
  `MessageDispatcher.sendTo()` are routed through a separate direct-receiver channel that
  simply invoked `BaseAgent.onDirectMessage()` — a no-op by default — regardless of any
  matching `@AgenorMessageHandler`, contradicting `onDirectMessage()`'s own Javadoc ("only
  called if no `@AgenorMessageHandler` matches"). Agents addressed directly by `receiverId`
  (e.g. `LLMDirectMessagingExample`) silently dropped every task message and never replied.
  `BaseAgent` now tracks annotated handlers per topic (`registerDirectTopicHandler`) and
  `handleDirectMessage()` dispatches to a matching handler before falling back to
  `onDirectMessage()`.

## [0.24.1] - 2026-07-26

### Fixed

- **REQUEST protocol two-phase response (AGREE/INFORM) resolved on the wrong reply (ADR-026)**:
  `ConversationManager.request()` and `AgenorA2AAdapter.sendInternal()` previously resolved
  their returned `CompletableFuture` on the first reply received — typically the intermediate
  `AGREE` — because both `AGREE` and `INFORM` correlate to the original `REQUEST` message. The
  final `INFORM`/`FAILURE` was silently discarded. Both now resolve on the final outcome
  (`INFORM`/`FAILURE`, or an immediate `REFUSE`); `AGREE` no longer completes the future.
  **Migration**: callers that relied on receiving the `AGREE` message from `request()`'s
  future must switch to `ConversationManager.onMessage(conversationId, handler)` to observe
  it. `ContractNetProtocol.callForProposals()` and `QueryProtocol` are unaffected. See
  [ADR-026](docs/adr/ADR-026-request-protocol-final-resolution.md).

### Added

- **`ConversationManager.onMessage(String, Consumer<DialogueMessage>)`** promoted from
  `DefaultConversationManager` onto the `ConversationManager` interface, so callers can
  observe intermediate dialogue messages (e.g. `AGREE`) that no longer resolve the
  `request()` future.

## [0.24.0] - 2026-07-13

### Changed

- **BREAKING — Project rebranded from Jentic to Agenor**. Maven coordinates
  move from `dev.jentic:jentic-*` to `dev.agenor:agenor-*`; Spring Boot properties from
  `jentic.*` to `agenor.*`; structural annotations drop their prefix (`@JenticAgent` →
  `@Agent`, `@JenticBehavior` → `@Behavior`, `@JenticPersist` → `@Persist`,
  `@JenticPersistenceConfig` → `@PersistenceConfig`); `@JenticMessageHandler` is renamed to
  `@AgenorMessageHandler` to avoid collision with Spring's `@MessageMapping`. No backward
  compatibility shim is provided (clean cut — no consumers on Maven Central yet). Full
  migration table and rationale in [ADR-025](docs/adr/ADR-025-agenor-rebrand.md).

### Fixed

- **`InMemoryAgentDirectory` — registration race condition**: `register()`, `unregister()`, and `updateStatus()` used `CompletableFuture.runAsync()`, queuing `ConcurrentHashMap` writes on the `ForkJoinPool`. Because `JenticRuntime.registerAgent()` never awaited the returned future, `resolveEndpoint()` could be called before the write completed — manifesting as `AgentNotFoundException` on loaded CI runners even though the agent had been registered at the API level. All three methods are now synchronous (direct map operations, returning `CompletableFuture.completedFuture(null)`), consistent with the already-synchronous `resolveEndpoint()`. A null/blank agentId guard was also added to `register()`: missing constructors resolved as `null` could produce descriptors with no id; the case is now logged as a warning and skipped gracefully instead of throwing a silent NPE.

## [0.23.0] - 2026-05-22

### Added

- **`JdbcApprovalGate` — persistent HITL approval queue (ADR-024)**: new class in `dev.jentic.adapters.persistence.hitl` that persists approval requests in the `jentic_hitl_requests` table. Approval requests survive JVM restarts; pending requests are visible from any node via `getPendingRequests()`.

  New classes and migration:
  - `JdbcApprovalGate` — implements `ApprovalGate` and `AutoCloseable`. Maintains an in-memory `CompletableFuture` map per JVM (local-future constraint — see ADR-024). Timeout scheduler uses virtual threads.
  - `HitlSchemaManager` — Flyway wrapper for `classpath:db/migration/jentic-hitl`. Idempotent.
  - `PostgresNotificationListener` — optional cross-node decision propagation via Postgres `LISTEN/NOTIFY` on channel `jentic_hitl`. Activated automatically when the JDBC URL contains `postgresql`.
  - `V1__create_hitl_queue.sql` — Flyway migration creating `jentic_hitl_requests` table with status, decision, and audit columns.

  Startup recovery: call `gate.recoverExpired()` once after construction to mark rows with `expires_at <= NOW()` as `EXPIRED`.

  ```java
  new HitlSchemaManager(dataSource, "classpath:db/migration/jentic-hitl").migrate();
  var gate = new JdbcApprovalGate(dataSource, jdbcUrl);
  gate.recoverExpired();
  var runtime = JenticRuntime.builder().withDefaultConfig().approvalGate(gate).build();
  ```

- **`JenticRuntime.Builder.approvalGate(ApprovalGate)`** (since 0.23.0): injects a custom `ApprovalGate` into the runtime. Defaults to `InMemoryApprovalGate` when not set — no behavioral change for existing users.

- **Spring Boot auto-configuration — `jentic.hitl.provider=jdbc`**: new `JdbcHitlConfiguration` inner class in `JenticAutoConfiguration`. Activates when `dev.jentic.adapters.persistence.hitl.JdbcApprovalGate` is on the classpath and `jentic.hitl.provider=jdbc` is set. Exposes a `JdbcApprovalGate` bean and wires it into `JenticRuntime`. New `JenticProperties.Hitl` record with `provider` and `jdbc` sub-record.

  ```yaml
  jentic:
    hitl:
      provider: jdbc
      jdbc:
        url: jdbc:postgresql://localhost:5432/mydb  # fallback: directory.jdbc.url
        username: jentic
        password: ${DB_PASSWORD}
  ```

### Changed

- **Spring Boot starter — typed provider sub-sections replace generic `properties` map (breaking)**

  `jentic.messaging` and `jentic.directory` now use named, type-safe sub-sections instead of a flat `Map<String,String> properties`. This enables IDE auto-complete, native types (int, long), and Bean Validation.

  **Migrate `jentic.messaging.provider=redis`:**
  ```yaml
  # Before (0.22)
  jentic:
    messaging:
      provider: redis
      properties:
        uri: redis://localhost:6379
        read-block-timeout-ms: "2000"

  # After (0.23+)
  jentic:
    messaging:
      provider: redis
      redis:
        uri: redis://localhost:6379
        read-block-timeout-ms: 2000   # native long, no quotes
  ```

  **Migrate `jentic.directory.provider=jdbc`:**
  ```yaml
  # Before (0.22)
  jentic:
    directory:
      provider: jdbc
      properties:
        url: jdbc:postgresql://localhost:5432/jentic
        pool-size: "10"

  # After (0.23+)
  jentic:
    directory:
      provider: jdbc
      jdbc:
        url: jdbc:postgresql://localhost:5432/jentic
        pool-size: 10   # native int, no quotes
  ```

## [0.22.0] - 2026-05-19

### Added

- **`jentic-adapters-persistence` — JDBC agent directory (ADR-022, ADR-023)**: new dedicated Maven module providing durable, relational-database-backed implementations of `AgentRegistry`, `AgentDiscovery`, and `AgentResolver` via plain JDBC (PostgreSQL, MySQL, H2).

  New classes in `dev.jentic.adapters.persistence.directory`:
  - `JdbcAgentDirectory` — factory and lifecycle holder. Owns the HikariCP connection pool, runs Flyway schema migrations on startup, and exposes the three JDBC capability implementations via `registry()`, `discovery()`, and `resolver()`. Implements `Closeable`.
  - `JdbcAgentRegistry` — implements `AgentRegistry`; upsert semantics on register (idempotent re-registration updates the endpoint and metadata).
  - `JdbcAgentDiscovery` — implements `AgentDiscovery`; supports `findById`, `findByCapability`, `findByType`, and paginated `findAgents(AgentQuery, PageRequest)`.
  - `JdbcAgentResolver` — implements `AgentResolver`; resolves `AgentEndpoint` by agent ID.
  - `JdbcDirectoryConfig` — immutable configuration record (`jdbcUrl`, `username`, `password`, `maximumPoolSize`, `migrationLocation`).
  - `DirectorySchemaManager` — thin Flyway wrapper; runs migrations from `classpath:db/migration/jentic-directory`.
  - `JdbcHelper` — centralised JDBC utility (connection acquisition, parameter binding, result-set mapping).

  Flyway migration `V1__create_agent_directory.sql` creates two tables:
  - `jentic_agents` — agent registration, status, endpoint, and metadata.
  - `jentic_agent_capabilities` — normalised capability set; FK cascade-deletes on agent removal.

  `AgentPresence` is intentionally **not** implemented by JDBC — heartbeat/liveness writes belong to purpose-built backends (Redis TTL keys, Consul session leases). The in-memory `AgentPresence` from `InMemoryAgentDirectory` is used as the default fourth capability when combining a JDBC directory with `JenticRuntime.Builder`.

  HikariCP and Flyway are declared as regular (`compile`) dependencies inside `jentic-adapters-persistence`. The persistence stack does not reach the default classpath of applications that only declare `jentic-runtime` — see ADR-022.

  ```java
  try (var dir = JdbcAgentDirectory.create(
          JdbcDirectoryConfig.of("jdbc:postgresql://localhost:5432/jentic", user, pass))) {
      var runtime = JenticRuntime.builder()
              .agentRegistry(dir.registry())
              .agentDiscovery(dir.discovery())
              .agentResolver(dir.resolver())
              .build();
      runtime.start().join();
  }
  ```

- **Spring Boot starter — JDBC directory auto-configuration**: `JenticAutoConfiguration` gains a new `JdbcDirectoryConfiguration` inner class, activated when `JdbcAgentDirectory` is on the classpath and `jentic.directory.provider=jdbc`.
  - `JdbcAgentDirectory` bean is lifecycle-managed (`destroyMethod="close"`).
  - `AgentRegistry`, `AgentDiscovery`, and `AgentResolver` beans backed by the JDBC implementations.
  - `JenticRuntime` bean built with the three JDBC capabilities plus the in-memory presence fallback.
  - `jentic-adapters-persistence` declared as `optional=true` in the starter POM — no impact on applications that use `provider=local` or `provider=inmemory`.
  - Provider-specific properties go in the `jentic.directory.properties` map, consistent with the messaging provider pattern:
    ```yaml
    jentic:
      directory:
        provider: jdbc
        properties:
          url: jdbc:postgresql://localhost:5432/jentic
          username: jentic
          password: ${DB_PASSWORD}
          pool-size: "10"
    ```

- **ADR-022 — `jentic-adapters-persistence` module split**: documents the decision to use a dedicated Maven sub-module rather than `optional=true` inside `jentic-adapters` for the persistence stack (HikariCP, Flyway, JDBC drivers).

- **ADR-023 — Persistent agent directory with JDBC**: documents the schema design, upsert semantics, capability-split rationale, and the deliberate exclusion of `AgentPresence` from the JDBC backend.

- **`jentic-examples` — `JdbcDirectoryExample`**: runnable demo using H2 in-process (no external infrastructure). Demonstrates `JdbcAgentDirectory.create()`, `JenticRuntime.Builder` per-capability wiring, and capability-based discovery across two agents (`OrchestratorAgent`, `DataWorkerAgent`).

- **Docs — `docs/adapters/jdbc-directory.md`**: full adapter guide covering prerequisites, Maven dependency, schema, programmatic quick start, Spring Boot auto-configuration, configuration reference, mixed JDBC+in-memory backend design, multi-node scenario, and integration test commands.

- **`jentic-adapters-persistence` — OTel instrumentation for JDBC directory adapters**: all three JDBC capability implementations now emit `JenticTelemetry` spans, consistent with the rest of the Jentic adapter layer. Spans are emitted only when a non-noop `JenticTelemetry` is wired; the default remains zero-overhead noop.

  New spans and emitting classes:

  | Span name | Emitted by | Key attributes |
  |-----------|------------|----------------|
  | `directory.resolve` | `JdbcAgentResolver` | `agent.id`, `endpoint.type` (`not-found` if absent) |
  | `directory.register` | `JdbcAgentRegistry` | `agent.id` |
  | `directory.unregister` | `JdbcAgentRegistry` | `agent.id` |
  | `directory.update_status` | `JdbcAgentRegistry` | `agent.id`, `agent.status` |
  | `directory.find` | `JdbcAgentDiscovery` | `directory.find.type` (`by_id`\|`by_capability`\|`by_type`\|`query`), `directory.find.result_count` |

  All spans follow the async pattern (`spanBuilder()` before the `JdbcHelper` virtual-thread call, `whenComplete()` to set attributes and end the span) so the OTel parent context is captured on the calling thread before the JDBC I/O boundary.

  **`JdbcAgentDirectory.create(JdbcDirectoryConfig, JenticTelemetry)`** — new overload that forwards the telemetry instance to all three adapter constructors. The existing single-argument `create(JdbcDirectoryConfig)` is retained and delegates to the new overload with `JenticTelemetry.noop()`.

  **Spring Boot starter** — `JdbcDirectoryConfiguration.jdbcAgentDirectory()` now injects `ObjectProvider<JenticTelemetry>` and passes `telemetry.getIfAvailable(JenticTelemetry::noop)` to the factory, consistent with the `RedisMessagingConfiguration` wiring pattern.

  **ADR-019** and **`docs/observability.md`** span inventory tables extended with the five new JDBC spans (`@since 0.22.0`).

- **`jentic-adapters-persistence` — span emission tests**: `JdbcAgentDirectoryTest` gains a `RecordingTelemetry` inline test helper (zero new test dependencies) and 9 new test methods verifying span name, attributes, and `SpanStatus` for every instrumented operation (`directory.resolve`, `directory.register`, `directory.unregister`, `directory.update_status`, `directory.find` for all four find types).

### Changed

- **Spring Boot starter — Redis messaging YAML format** (**breaking** for 0.21.x users): the `jentic.messaging.redis.*` sub-section is replaced by the flat `jentic.messaging.properties.*` map, matching the pattern used by the new JDBC directory provider. The `JenticProperties.Messaging.Redis` sub-record is removed.

  ```yaml
  # Before (0.21.x)
  jentic:
    messaging:
      provider: redis
      redis:
        uri: redis://localhost:6379
        consumer-group-prefix: my-app

  # After
  jentic:
    messaging:
      provider: redis
      properties:
        uri: redis://localhost:6379
        consumer-group-prefix: my-app
  ```

  All other `redis.*` keys (`read-block-timeout-ms`, `max-stream-length`, `pending-entries-timeout-ms`, `max-delivery-attempts`) move to the same `properties` map as string values. Default values are unchanged.

### Removed

> These APIs were deprecated in **0.20.0** and are now removed.

- **`dev.jentic.core.MessageService`** — use `dev.jentic.core.messaging.MessageDispatcher`.
- **`dev.jentic.runtime.messaging.InMemoryMessageService`** — use `dev.jentic.runtime.messaging.InMemoryMessageDispatcher`.
- **`dev.jentic.runtime.directory.LocalAgentDirectory`** — use `dev.jentic.runtime.directory.InMemoryAgentDirectory`.
- **`JenticRuntime.getMessageService()`** — use `JenticRuntime.getMessageDispatcher()`.
- **`JenticRuntime.Builder.messageService()`** — use `Builder.messageDispatcher()`.
- **`JenticRuntime.Builder.agentDirectory()`** — use `Builder.agentRegistry()` / `agentResolver()` / `agentDiscovery()` / `agentPresence()`.
- **`AgentDescriptor(String, String, String, AgentStatus, Set, Map, Instant, Instant)` constructor** — use `AgentDescriptor.builder(agentId)` and call `.endpoint(AgentEndpoint.local(nodeId))`. The 8-argument constructor silently set `endpoint = null`, breaking `AgentResolver` logic in distributed deployments.
- **`AgentQuery.customFilter(Predicate<AgentDescriptor>)`** — remote backends cannot evaluate Java predicates server-side. Use structured criteria: `.agentType()`, `.status()`, `.requiredCapabilities()`.

### Deprecated

- **`dev.jentic.core.AgentDirectory`** — this pre-0.20.0 composite interface will be removed at 0.24.0 (Agenor rebranding). Use `dev.jentic.core.directory.AgentDirectory` instead. The runtime, starter, and all built-in implementations already implement the new interface; only the package path changes.

### Tests

- **`JdbcAgentDirectoryTest`** (unit): full `AgentRegistry`, `AgentDiscovery`, and `AgentResolver` coverage against H2 in-process — runs as part of `mvn test` with no flags.
- **`JdbcAgentDirectoryIT`** (integration): Testcontainers PostgreSQL 16; gated by `-Dintegration.tests.enabled=true` to avoid Docker dependency in CI by default.

### Fixed

- **`Span.makeCurrent()` — OTel context propagation for non-async spans**: without this
  method, every Jentic span was silently emitted as a root span. `Context.current()` at
  `spanBuilder()` time never carried a parent because no span was ever made "current",
  so `behavior.execute`, `reflection.iteration`, `guardrail.evaluate`, and
  `message.receive` spans had no children even when they triggered LLM, MCP, or guardrail
  calls during execution.

  New public type `SpanScope` in `dev.jentic.core.telemetry` — an `AutoCloseable` whose
  `close()` never throws, returned by `Span.makeCurrent()`. The noop implementation
  returns a singleton (zero allocation). The OTel implementation delegates to
  `io.opentelemetry.api.trace.Span.makeCurrent()`.

  Call sites updated to `try (var scope = span.makeCurrent()) { ... }`:
  - `SimpleBehaviorScheduler.executeBehavior()`
  - `ConsumerLoop.processMessage()`
  - `ReflectionBehavior.action()` loop
  - `GuardrailChain.applyInput()` / `applyOutput()`

  Async spans (`llm.chat`, `llm.chat.stream`) use `CompletableFuture.whenComplete` and
  are unaffected — their parent is captured at `spanBuilder()` call time, which is
  correct as long as the caller already holds the right context.

## [0.21.0] - 2026-05-13

### Added

- **Redis Streams messaging adapter (ADR-021)**: distributed messaging over Redis Streams, backed by [Lettuce 7.5.1](https://lettuce.io/) (RESP-compatible with Valkey 8.x). Delivers at-least-once guarantees via consumer groups, virtual-thread consumer loops, and a dead-letter queue after configurable retry exhaustion.

  New classes in `dev.jentic.adapters.messaging.redis`:
  - `RedisMessageDispatcher` — implements the full `MessageDispatcher` interface; **primary entry point** for `JenticRuntime` integration. Routes `sendTo` via a local fast-path (same-JVM handler map) or a remote path (`AgentResolver` → `RedisMessageTransport`). Starts a shared node-stream consumer loop on first `subscribeRecipient` call.
  - `RedisTopicPublisher` — implements `TopicPublisher` + `TopicSubscriber` for fan-out via per-subscription consumer groups (`jentic:topic:<topic>` streams).
  - `RedisMessageTransport` — implements `MessageTransport` for point-to-point delivery via a shared node-scoped consumer group (`jentic:node:<nodeId>` streams).
  - `RedisMessagingFactory` — fluent builder that wires all components from a shared Lettuce `RedisClient`. Exposes `messageDispatcher()` and `messageDispatcher(Supplier<AgentResolver>)` as the recommended entry points. Implements `AutoCloseable`.
  - `RedisMessagingConfig` — immutable configuration record.
  - `RedisStreamClient` — thin Lettuce wrapper isolating sync/async API calls.
  - `ConsumerLoop` — virtual-thread consumer loop; creates the consumer group synchronously before starting, eliminating the subscribe/publish race condition.
  - `MessageCodec` — JSON serialization/deserialization of `Message` records to/from Redis stream entries.

  The Lettuce dependency is declared `optional=true` in `jentic-adapters/pom.xml` per ADR-018 — the adapter is only activated when the caller explicitly declares `io.lettuce:lettuce-core` on the classpath.

  Test coverage: 47 unit tests (Mockito + AssertJ) covering codec, config, transport, publisher, and dispatcher; Testcontainers integration tests gated by `-Dintegration.tests.enabled=true`.

- **Spring Boot starter — Redis messaging auto-configuration**: `JenticAutoConfiguration` gains a new `RedisMessagingConfiguration` inner class, activated when `io.lettuce.core.RedisClient` is on the classpath and `jentic.messaging.provider=redis`.
  - `RedisMessagingFactory` bean is lifecycle-managed (`destroyMethod="close"`).
  - `MessageDispatcher` bean (`redisMessageDispatcher`) backed by `RedisMessageDispatcher`; uses a lazy `ObjectProvider<AgentResolver>` to avoid circular dependency with `JenticRuntime`.
  - `JenticRuntime` bean (`jenticRuntime`) built with the Redis dispatcher as its messaging backend.
  - When `provider=redis` but Lettuce is absent, the starter falls back to the in-memory runtime — no startup failure.
  - New `JenticProperties.Messaging.Redis` nested record with URI-based connection configuration and sensible defaults.

  Minimal Spring Boot wiring:
  ```yaml
  jentic:
    messaging:
      provider: redis
      redis:
        uri: redis://localhost:6379
  ```

- **`jentic-examples` — `RedisMessagingExample`**: runnable demo illustrating `RedisMessageDispatcher` integrated with `JenticRuntime` — two agents (`OrderAgent` CYCLIC pub/sub producer, `FulfillmentAgent` `@JenticMessageHandler` consumer with direct reply) communicating over Redis Streams. Requires a local Redis/Valkey instance on `localhost:6379`.

- **`docs/adapters/redis.md`**: MkDocs-compatible adapter guide covering setup, `RedisMessagingFactory` builder API, Spring Boot wiring, at-least-once delivery semantics, dead-letter queue, and Testcontainers integration test recipe.

- **ADR-021 — Redis-based `MessageTransport`**: documents the choice of Redis Streams over Redis Pub/Sub and Kafka for the first distributed `MessageTransport` implementation, covering the at-least-once delivery model, consumer group strategy, dead-letter queue design, and Lettuce dependency placement.

### Tests

- **Spring Boot starter — Redis dispatcher wiring verified**: added `lettuce-core` as a `test`-scope dependency to `jentic-spring-boot-starter` (version aligned with `jentic-adapters`) and introduced two new tests in `JenticRedisMessagingAutoConfigurationTest`:
  - `redisMessageDispatcherRegisteredWhenLettucePresent` — asserts that the `MessageDispatcher` bean is an instance of `RedisMessageDispatcher` when `provider=redis` and Lettuce is on the classpath.
  - `jenticRuntimeIsWiredWithRedisDispatcherWhenLettucePresent` — asserts that `JenticRuntime.getMessageDispatcher()` returns the Redis dispatcher (not the in-memory default), proving that Spring's `@ConditionalOnMissingBean` ordering resolves `jenticRuntimeWithRedis` before `jenticRuntime`.

  Both tests use a mock `RedisMessagingFactory` supplied via `ApplicationContextRunner.withBean()` — no Redis connection required.

### Changed

- **`TopicPublisher.publish` — redundant `topic` parameter removed** (**breaking**): signature changes from `publish(String topic, Message msg)` to `publish(Message msg)`. Routing now reads `msg.topic()` directly. `IllegalArgumentException` is thrown if `msg.topic()` is `null` or blank. All callers must set `.topic(...)` on the `Message` before publishing.

- **`DirectMessenger.sendTo` — redundant `recipientAgentId` parameter removed** (**breaking**): signature changes from `sendTo(String recipientAgentId, Message msg)` to `sendTo(Message msg)`. Routing now reads `msg.receiverId()` directly. `IllegalArgumentException` is thrown if `msg.receiverId()` is `null` or blank. All callers must set `.receiverId(...)` on the `Message` before sending.

- **`MessageFilter` — dependency on deprecated `MessageService` replaced with `FilterableSubscriber`**: `MessageFilter` now accepts the focused `FilterableSubscriber` capability interface. Source-compatible for callers that pass a `MessageDispatcher` (which extends `FilterableSubscriber`).

- **Built-in agent response messaging — `sendTo` replaced with `publish`**: several built-in agents were using `sendTo` for topic-based responses. Corrected to use `publish`, aligning with pub-sub semantics.

### Migration Guide (publish / sendTo signature change)

```java
// Before (0.20.x)
dispatcher.publish("orders.created", msg);
dispatcher.sendTo("inventory-agent", msg);

// After — set topic / receiverId on the message, then drop the first argument
dispatcher.publish(Message.builder().topic("orders.created").content(data).build());
dispatcher.sendTo(Message.builder().receiverId("inventory-agent").content(data).build());

// If the message already carries topic / receiverId, simply drop the first argument:
dispatcher.publish(msg);   // routes on msg.topic()
dispatcher.sendTo(msg);    // routes on msg.receiverId()
```

## [0.20.0] - 2026-05-03

### Added

- **Core API Refactor — Capability-Sized Interfaces (ADR-020)**: the monolithic `MessageService` and `AgentDirectory` interfaces have been decomposed into focused capability interfaces that compose cleanly and work with distributed backends (Redis, JDBC, Kafka, etc.) without semantic compromise.

  **Messaging** — new interfaces in `dev.jentic.core.messaging`:
  - `TopicPublisher` — `publish(topic, msg)`
  - `TopicSubscriber` — `subscribeTopic(topic, handler)` → `Subscription`
  - `DirectMessenger` — `sendTo(agentId, msg)` with `AgentNotFoundException` on unknown agents
  - `DirectReceiver` — `subscribeRecipient(localAgentId, handler)` → `Subscription`
  - `FilterableSubscriber` — `subscribeFiltered(Predicate<Message>, handler)` → `Subscription`
  - `MessageDispatcher` — composite of the four core messaging interfaces
  - `Subscription` — replaces raw `String` subscription IDs; call `subscription.unsubscribe()` to cancel
  - `MessageTransport` — low-level transport abstraction for future remote backends

  **Directory** — new interfaces in `dev.jentic.core.directory`:
  - `AgentRegistry` — `register`, `unregister`, `updateStatus`
  - `AgentResolver` — `resolveEndpoint(agentId)` → `Optional<AgentEndpoint>`
  - `AgentDiscovery` — `findById`, `findByCapability`, `findByType`, `findAgents(AgentQuery, PageRequest)`
  - `AgentPresence` — `heartbeat`, `getStatus`
  - `AgentDirectory` — composite of all four directory interfaces

  **New value types** in `dev.jentic.core`:
  - `AgentEndpoint(nodeId, transportType, transportProps)` — transport routing record; `AgentEndpoint.local(nodeId)` factory
  - `TransportEndpoint(transportType, address, properties)` — low-level transport address record
  - `Page<T>(content, totalElements, pageNumber, pageSize)` — paginated result record
  - `PageRequest(page, size)` — pagination parameters; `PageRequest.of(page, size)` and `PageRequest.first(size)` factories
  - `AgentQuery.all()` — factory matching every registered agent
  - `AgentQuery.customFilter` deprecated; not evaluated by `InMemoryAgentDirectory` (documented in ADR-020)

  **New exception**: `AgentNotFoundException` in `dev.jentic.core.exceptions` — thrown by `sendTo` when the recipient agent is not registered.

  **`jentic-runtime` — new default implementations**:
  - `InMemoryMessageDispatcher` — replaces `InMemoryMessageService` as the default dispatcher. Delivers messages via virtual threads. Routes `sendTo` via `AgentResolver`; throws `AgentNotFoundException` for unknown recipients. Emits `message.send` OTel spans.
  - `InMemoryAgentDirectory` — replaces `LocalAgentDirectory` as the default directory. Assigns `AgentEndpoint.local(nodeId)` to newly registered agents automatically. Emits `directory.resolve` OTel spans.

  **`JenticRuntime.Builder`** — new capability setter methods (`messageDispatcher`, `agentRegistry`, `agentResolver`, `agentDiscovery`, `agentPresence`) for per-capability overrides without replacing the entire directory.

  **Spring Boot starter** — new capability beans: `jenticMessageDispatcher`, `jenticAgentDirectory`, `jenticAgentRegistry`, `jenticAgentResolver`, `jenticAgentDiscovery`, `jenticAgentPresence`. Each is `@ConditionalOnMissingBean`, so user-provided implementations always win.

  **`Agent` interface** — `getMessageDispatcher()` is now an abstract method on the core `Agent` interface. Implementations that extend `BaseAgent` are unaffected (inherited automatically). Direct `Agent` implementors must add `@Override public MessageDispatcher getMessageDispatcher()` returning their dispatcher instance.

  **Docs**: `docs/messaging.md` and `docs/directory.md` — full API reference with migration tables, Spring Boot wiring, and custom backend extension points.

- **ADR-020 — Core API Refactor for Distributed Backends**: documents the decomposition rationale, backward-compat strategy, removal timeline (0.22.0), and the decision not to evaluate `customFilter` in the paginated `findAgents` path.

### Deprecated

> These APIs will be removed in **0.22.0**. Migrate at your own pace — all deprecated code continues to compile and run unchanged.

- `dev.jentic.core.MessageService` — use `dev.jentic.core.messaging.MessageDispatcher` (and `FilterableSubscriber` for predicate subscriptions).
- `dev.jentic.core.AgentDirectory` — use `dev.jentic.core.directory.AgentDirectory`.
- `dev.jentic.runtime.messaging.InMemoryMessageService` — use `dev.jentic.runtime.messaging.InMemoryMessageDispatcher`.
- `dev.jentic.runtime.directory.LocalAgentDirectory` — use `dev.jentic.runtime.directory.InMemoryAgentDirectory`.
- `JenticRuntime.getMessageService()` — use `JenticRuntime.getMessageDispatcher()`.
- `JenticRuntime.Builder.messageService()` — use `Builder.messageDispatcher()`.
- `JenticRuntime.Builder.agentDirectory()` — use `Builder.agentRegistry()` / `agentResolver()` / `agentDiscovery()` / `agentPresence()`.
- `AgentDirectory.listAll()` — use `AgentDiscovery.findAgents(AgentQuery.all(), PageRequest.first(n))`.
- `Agent.getMessageService()` — use `Agent.getMessageDispatcher()`. The method is now a default bridge that delegates to `getMessageDispatcher()`; it throws `UnsupportedOperationException` if the returned dispatcher does not implement `MessageService`.
- `AgentDiscovery.findAgents(AgentQuery)` (non-paginated) — use `findAgents(AgentQuery, PageRequest)`.
- `AgentQuery.customFilter` field and builder method — not evaluated by the paginated path; use `AgentQuery` structured fields instead.
- `AgentDescriptor(8-arg constructor)` — use `AgentDescriptor.builder(agentId)...build()`.

### Migration Guide (0.19.x → 0.20.0)

```java
// Before (0.19.x)
MessageService svc = runtime.getMessageService();
svc.send(msg);
String id = svc.subscribe("my.topic", handler);
svc.unsubscribe(id);

// After (0.20.0)
MessageDispatcher dispatcher = runtime.getMessageDispatcher();
dispatcher.publish("my.topic", msg);
Subscription sub = dispatcher.subscribeTopic("my.topic", handler);
sub.unsubscribe();
```

```java
// Before (0.19.x)
List<AgentDescriptor> all = directory.listAll().join();

// After (0.20.0)
Page<AgentDescriptor> page = directory
    .findAgents(AgentQuery.all(), PageRequest.first(100)).join();
List<AgentDescriptor> all = page.content();
```

## [0.19.0] - 2026-04-26

### Added

- **OpenTelemetry integration (ADR-019)** — distributed tracing is now a first-class feature of the Jentic framework:
  - **`jentic-core` — observability SPI** (`JenticTelemetry`, `Span`, `SpanBuilder`, `SpanStatus`, `NoopJenticTelemetry`): a thin, dependency-free interface layer so `jentic-core` remains free of third-party imports (ADR-002). `NoopJenticTelemetry` is the zero-allocation default used whenever no OTel SDK is present.
  - **`jentic-adapters` — OTel SDK adapter** (`OtelJenticTelemetry`, `OtelTelemetryFactory`): backed by `opentelemetry-sdk` and `opentelemetry-exporter-otlp`, both declared `optional=true` per ADR-018. `OtelTelemetryFactory` provides a fluent builder and a `fromEnvironment()` factory respecting the standard `OTEL_SERVICE_NAME`, `OTEL_EXPORTER_OTLP_ENDPOINT`, and `OTEL_EXPORTER_TYPE` environment variables. Supports `otlp-http`, `otlp-grpc`, and `none` exporter types.
  - **`jentic-runtime` — `InstrumentedLLMProvider`**: decorator that wraps any `LLMProvider` and emits `llm.chat` / `llm.chat.stream` spans with `llm.provider`, `llm.model`, `llm.tokens.input`, `llm.tokens.output`, and `llm.latency_ms` attributes.
  - **Instrumentation points** — the following components now emit spans when a non-noop `JenticTelemetry` is installed:

    | Component | Span name | Key attributes |
    |-----------|-----------|----------------|
    | `InstrumentedLLMProvider` | `llm.chat`, `llm.chat.stream` | `llm.provider`, `llm.model`, `llm.tokens.input/output`, `llm.latency_ms` |
    | `GuardrailChain` | `guardrail.evaluate` | `guardrail.name`, `guardrail.decision` |
    | `HumanCheckpointBehavior` | `hitl.approval` | `hitl.request_id`, `hitl.decision`, `hitl.wait_ms` |
    | `SimpleBehaviorScheduler` | `behavior.execute` | `behavior.id`, `behavior.type`, `agent.id` |
    | `JenticMcpClientAdapter` | `mcp.tool.call` | `mcp.tool.name`, `mcp.transport` |
    | `ReflectionBehavior` | `reflection.iteration` | `reflection.iteration`, `reflection.accepted` |

  - **`JenticRuntime.Builder.telemetry(JenticTelemetry)`**: wires a telemetry instance into the runtime; all components receive it automatically. Falls back to `NoopJenticTelemetry` when not set.
  - **`LLMAgent.installTelemetry(JenticTelemetry)`**: wraps the agent's `LLMProvider` in `InstrumentedLLMProvider`; called automatically by `JenticRuntime.registerAgent()`.
  - **Spring Boot starter — `TelemetryConfiguration`** (`@ConditionalOnClass(OpenTelemetry.class)`): auto-configures `OtelJenticTelemetry` when the OTel SDK is on the classpath. New YAML properties: `jentic.telemetry.enabled`, `jentic.telemetry.exporter` (`otlp-http` | `otlp-grpc` | `none`), `jentic.telemetry.endpoint`, `jentic.telemetry.service-name`. Falls back to `NoopJenticTelemetry` when OTel is absent or `jentic.telemetry.enabled=false`.
  - **`jentic-examples` — `ObservabilityExample`** and companion `docker-compose.yml` (Jaeger all-in-one): a runnable example demonstrating opt-in OTel activation via `OtelTelemetryFactory`, LLM calls with guardrails, and trace visualisation in Jaeger at `http://localhost:16686`.
  - **`docs/observability.md`**: span taxonomy, metrics reference, OTel Collector setup guide, and step-by-step opt-in instructions for both programmatic and Spring Boot wiring.
- **ADR-018 — Optional adapter dependencies pattern**: codifies when to use `optional=true` inside `jentic-adapters` vs a dedicated Maven sub-module. Prevents per-adapter re-debate as new backends (Redis, JDBC, Kafka) are added. ADR-003 updated to cross-reference ADR-018; `jentic-adapters/README.md` extended with the opt-in contract and a minimum consumer POM snippet.
- **ADR-019 — OpenTelemetry instrumentation strategy**: documents the no-op abstraction layer, the `optional=true` dependency placement, the instrumentation points, context propagation via `ScopedValue` on virtual threads, and the classpath-isolation guarantee.

### Changed

- **`OtelJenticTelemetry` now implements `AutoCloseable`**: retains a reference to the `OpenTelemetrySdk` instance (previously discarded after construction, making the SDK eligible for GC along with its `BatchSpanProcessor`). `close()` calls `OpenTelemetrySdk.close()`, which blocks until the `BatchSpanProcessor` has exported all buffered spans.
- **`JenticRuntime.stop()` flushes telemetry on shutdown**: after stopping all agents and the behavior scheduler, `stop()` calls `close()` on the `JenticTelemetry` instance if it implements `AutoCloseable`. This ensures the OTel `BatchSpanProcessor` exports its buffer before the process exits.

### Fixed

- **`OtelJenticTelemetry` — spans silently discarded on process exit**: the `OpenTelemetrySdk` instance created by `OtelTelemetryFactory.build()` was not retained by `OtelJenticTelemetry`. Only the `Tracer` was stored; the SDK (including its `BatchSpanProcessor` and export queue) became eligible for GC immediately after `build()` returned. Combined with the missing `stop()` integration, all buffered spans were dropped on shutdown and never reached the collector. Fixed by retaining the SDK reference and calling `sdk.close()` from the new `AutoCloseable.close()` implementation.
- **`OtelTelemetryFactory` — HTTP exporter returned HTTP 404 from Jaeger**: `OtlpHttpSpanExporter.setEndpoint()` takes the full URL including the signal path — it does **not** auto-append `/v1/traces`. Passing a bare base URL (e.g. `http://localhost:4318`, as produced by the standard `OTEL_EXPORTER_OTLP_ENDPOINT` environment variable) caused Jaeger to return HTTP 404. The factory now appends `/v1/traces` when the supplied endpoint does not already contain a `/v1/` path segment. The internal `DEFAULT_ENDPOINT_HTTP` constant is updated to `http://localhost:4318/v1/traces` accordingly.

## [0.18.0] - 2026-04-15

### Changed
- **`jentic-spring-boot-starter` — migrated to Spring Boot 4.0.5** (from 3.5.13): Spring Boot 3.x reaches end of open-source support in June 2026. Spring Boot 4.0.5 requires Spring Framework 7 and Jakarta EE 11.
  - Spring Boot BOM version updated to `4.0.5`; redundant SnakeYAML version pin removed (managed by Boot BOM).
  - `SmartLifecycle` implementation now overrides `isPauseable()` returning `false` — Spring Framework 7 introduced context-pausing support; the Jentic runtime has no pause/resume semantics.
  - **Breaking change for actuator users**: Spring Boot 4.0 renamed the actuator health package from `org.springframework.boot.actuate.health` to `org.springframework.boot.health.contributor`. `JenticHealthIndicator` and the `@ConditionalOnClass` guard in `JenticAutoConfiguration.ActuatorConfiguration` updated accordingly. Applications using a custom `HealthIndicator` bean that overrides Jentic's must update their import.
- **ADR-016** updated to reflect the completed Spring Boot 4.0.x migration and document the actuator package rename.

## [0.17.0] - 2026-04-14

### Fixed
- **`JenticRuntime` — `LLMMemoryManager` not injected into non-`BaseAgent` `LLMMemoryAware` agents**: the injection block was nested inside `if (agent instanceof BaseAgent)`, so any plain `Agent` implementor that implemented `LLMMemoryAware` never received an `LLMMemoryManager`. The check is now independent of the `BaseAgent` guard.
- **`JenticRuntime` — `LLMMemoryManager` injection gated on `memoryStore != null`**: when a custom `llmMemoryManagerFactory` was provided without an explicit `MemoryStore`, the factory was silently skipped because the injection was nested inside the `if (memoryStore != null)` block. The two checks are now independent.

### Changed
- **`AgentFactory` — removed redundant service injection**: `configureBaseAgent()` was duplicating the service injection already performed by `JenticRuntime.registerAgent()`. `AgentFactory.createAgent()` now only handles instantiation and descriptor creation; all service injection (including `LLMMemoryManager`) is the sole responsibility of `registerAgent()`.

### Tests
- **`JenticRuntimeTest` — added coverage for `LLMMemoryManager` injection**: four new tests verify the scenarios addressed by the fix: injection into a `BaseAgent`+`LLMMemoryAware` agent, injection without a `MemoryStore`, injection into a plain `Agent` implementor that is `LLMMemoryAware`, and absence of injection (no NPE) when no factory is configured.
- **Docs — split memory and persistence guides**: extracted the Agent State Persistence section from `docs/memory.md` into a dedicated `docs/persistence.md`. The memory guide now covers the key-value memory system only (`MemoryStore`, `MemoryScope`, `InMemoryStore`); persistence concepts (`Stateful`, `AgentState`, `FilePersistenceService`, `PersistenceManager`, `@JenticPersistenceConfig`) are documented in the new guide. Cross-links and the `mkdocs.yml` nav updated accordingly.

## [0.16.0] - 2026-04-12

### Added
- **ADR-017 — `LLMRequest.model` optional with provider fallback**: the `model` field on `LLMRequest` is now optional. Providers resolve the effective model using this precedence: `request.model()` (explicit per-request override) → provider's configured `modelName` → `LLMException("No model specified")`.
  - New `LLMRequest.builder()` no-arg factory — preferred entry point when no per-request override is needed.
  - New `LLMRequest.Builder.model(String)` setter for explicit per-request overrides.
  - `LLMProvider.validateRequest()` no longer rejects a null model; resolution happens at execution time inside each adapter.
  - `OpenAIProvider`, `AnthropicProvider`, and `OllamaProvider` each implement a private `resolveModel(LLMRequest)` method that applies the precedence rule.

### Fixed
- **`MessageHistoryService` race condition in `store()`**: concurrent calls could corrupt the size counter and evict extra messages due to a TOCTOU gap between `addFirst`, `incrementAndGet`, and `pollLast`. The add-increment-evict sequence is now protected by a `ReentrantLock`, making writes fully atomic. `clear()` acquires the same lock to prevent interleaving with concurrent stores.

### Changed
- **`LLMRequest.builder(String model)` deprecated** (`since = "0.16.0"`, `forRemoval = true`): replaced by `LLMRequest.builder()` + optional `.model(String)` call. Existing callers compile with a deprecation warning and behave identically.
- **`DefaultReflectionStrategy`**: removed the hard-coded `"critique"` placeholder model from its internal `LLMRequest`; the injected provider's configured model is now used automatically.

## [0.15.0] - 2026-04-09

### Added
- **.editorconfig**: added project-wide coding style configuration file.
- **New models support (2026-04 update)**: updated LLM providers with current state-of-the-art models:
  - OpenAI: GPT-4.1 family, o3/o4-mini.
  - Anthropic: Claude 4.x series.
  - Ollama: Llama 3.x, Qwen 2.5, DeepSeek-R1.

### Changed
- **Model Enums Implementation**: replaced static maps with provider-specific enums (`OpenAIModel`, `AnthropicModel`, `OllamaModel`) for better type safety and maintainability.
- **`ModelTokenLimits` Decentralization (BREAKING CHANGE)**: moved token limit ownership from `jentic-runtime` to individual adapters (`jentic-adapters`).
  - `ModelTokenLimits` is now a generic registry in `jentic-core` (`dev.jentic.core.memory.llm`).
  - Adapters now register their own models and context sizes on class load.
  - Dependency inversion fix: adapters no longer depend on runtime for model registration.
- **Dependency updates**: bumped `langchain4j` to version `1.12.2`.

### Fixed
- **`WebhookApprovalNotifier`**: now correctly restores the interrupt flag on HTTP client timeout, ensuring retry loops are not interrupted prematurely.
- **Dual source of truth for models**: synchronized `getAvailableModels()` and `ModelTokenLimits` in all providers to prevent divergence.
- **Documentation**: updated `configuration.md` to remove outdated "future" references.

## [0.14.1] - 2026-03-30

### Fixed

- `SequentialBehavior` (repeating mode): `ONE_SHOT` child behaviors were silently
  skipped on the second and subsequent cycles because `isActive()` returned `false`
  after their first execution. The index wrap-around now calls `activate()` on all
  children (via `instanceof BaseBehavior` cast) to re-arm them for the next cycle.

## [0.14.0] - 2026-03-30

### Added
- **Spring Boot Starter (ADR-016)**: Introduced `jentic-spring-boot-starter` for seamless integration with Spring Boot applications.
  - Auto-configuration for `JenticRuntime` based on classpath scanning and configuration properties.
  - Support for `JenticProperties` to configure agent packages, LLM providers, and memory settings.
  - Dedicated documentation guide for Spring Boot Starter in `docs/spring-boot-starter.md`.
- **`SchedulingHint` enum** (`jentic-core`, `dev.jentic.core.composite`): declares how a `CompositeBehavior` wants to be driven by the scheduler (`ONCE`, `CYCLIC`, `ON_DEMAND`). Eliminates the need to wrap workflow composites in a `CyclicBehavior` driver.
- **`CompositeBehavior.getSchedulingHint()`**: new method (default `ON_DEMAND`) that workflow composites override to express their scheduling intent.
- **`SimpleBehaviorScheduler.scheduleComposite()`**: new private method that reads `SchedulingHint` and dispatches `SEQUENTIAL`/`PARALLEL` to `scheduleOneShot()` or `scheduleCyclic()` automatically. `FSM`, `RETRY`, `CIRCUIT_BREAKER`, and `PIPELINE` remain `ON_DEMAND`.
- **`SequentialBehavior.withStepTimeout(Duration)`**: fluent method to set a per-step timeout on both one-shot and repeating instances without requiring a dedicated constructor.

### Changed
- **`SequentialBehavior` — auto-scheduling (BREAKING CHANGE)**: `addBehavior()` is now sufficient to start a `SequentialBehavior`; no manual `execute()` call required.
  - One-shot mode (`SchedulingHint.ONCE`): all steps run once, then `active=false`. `getCurrentStep()` returns the total step count on completion.
  - Repeating mode (`SchedulingHint.CYCLIC`): each scheduler tick advances one step and wraps around immediately after the last step.
  - **Constructor API simplified**: removed `boolean repeatSequence` parameter and the 3-arg `(String, boolean, Duration)` / 4-arg constructors. Mode is now implicit: `new SequentialBehavior(id)` → one-shot; `new SequentialBehavior(id, interval)` → repeating.
  - `isRepeatSequence()` removed; replaced by `isRepeating()` (derived from `interval != null`).
- **`ParallelBehavior` — auto-scheduling**: `addBehavior()` is now sufficient; the behavior fires all children immediately upon registration (`SchedulingHint.ONCE`).
  - Fixed double-increment bug in `executeNOfM()`: `completedCount` was incremented twice per successful child (once in `executeChild()`, once in `executeNOfM()`), causing the N-of-M future to complete prematurely.
  - `addChild()` references in documentation corrected to `addChildBehavior()`.
- **`@JenticBehavior` annotation**: removed `repeatSequence()` attribute. Repeating sequential behaviors are now expressed via the existing `interval()` attribute, consistent with `CYCLIC` behavior.
- **`AnnotationProcessor.createSequentialBehavior()`**: updated to use new `SequentialBehavior` constructor API and `withStepTimeout()`.
- **`CompositeBehavior`**: added `protected Duration interval` field and improved Javadoc (class-level sections for scheduling, child management, thread safety, and implementation guide).
- **Documentation**: Standardized documentation headers and formatting across core and runtime packages for better consistency.
- **Project Structure**: Updated parent `pom.xml` and module-specific configurations to include the new Spring Boot Starter module.

### Fixed
- **`SequentialBehavior` one-shot**: `getCurrentStep()` now correctly returns `size()` (total steps) after completion instead of resetting to `0`.
- **`SequentialBehavior` repeating**: `currentIndex` now wraps to `0` immediately after the last step, not deferred to the next `execute()` call.
- **`SimpleBehaviorScheduler`**: `SEQUENTIAL`/`PARALLEL` behaviors registered via `addBehavior()` were silently ignored (treated as on-demand). Now dispatched correctly via `scheduleComposite()`.
- **`ParallelBehavior.executeNOfM()`**: fixed premature completion caused by double-counting `completedCount`.

## [0.13.0] - 2026-03-25

### Added
- **Human-in-the-Loop Checkpoint (ADR-015)**: Introduced a mechanism to pause agent execution and wait for human approval before proceeding.
  - Core abstractions in `jentic-core`: `ApprovalRequest`, `ApprovalDecision`, `ApprovalGate`, `ApprovalNotifier`, and `ApprovalTimeoutException`.
  - `@RequiresApproval` annotation for declarative wiring of human checkpoints to agents.
  - Implementation in `jentic-runtime`: `ApprovalService` to manage the lifecycle of approval requests.
  - Built-in `ApprovalGate` implementations: `InMemoryApprovalGate` for local testing.
  - Built-in `ApprovalNotifier` implementations: `WebhookApprovalNotifier` for remote notifications and `LoggingApprovalNotifier`.
  - New dedicated guide for Human-in-the-Loop and `HumanCheckpointBehaviorTest` in `jentic-runtime`.
- **Guardrails Layer (ADR-014)**: Introduced a declarative interceptor chain for `LLMAgent` to validate and transform inputs and outputs.
  - Core abstractions in `jentic-core`: `GuardrailResult` (sealed interface), `InputGuardrail`, `OutputGuardrail`, and `GuardrailContext`.
  - `GuardrailChain` in `jentic-runtime` for sequential execution and short-circuiting on violations.
  - `@WithGuardrails` annotation for declarative wiring of guardrails to agents.
  - Built-in implementations: `PiiRedactionGuardrail`, `ContentPolicyGuardrail`, `MaxTokensInputGuardrail`, and `JsonSchemaOutputGuardrail`.
  - New dedicated guide for Guardrails and `GuardrailsExample` in `jentic-examples`.
- **Model Context Protocol (MCP) Integration (ADR-013)**: Support for official MCP SDK to connect external tools to LLM workflows.
  - `JenticMcpClientAdapter` and `McpClientFactory` for synchronous to asynchronous SDK bridging.
  - `McpToolRegistry` with TTL support for efficient tool caching and discovery.
  - `McpFunctionAdapter` to map MCP tools to Jentic function-calling framework.
  - Core abstractions: `McpClient`, `McpTool`, and `McpToolResult` in `jentic-core`.
- **MCP Documentation**: Detailed guide for MCP adapter and architecture overview in `docs/adapters/mcp.md`.
- **MCP Example**: `McpExample` demonstrating Docker-based STDIO transport for MCP servers in `jentic-examples`.
- **Branding Assets**: Added official Jentic logo and wordmark to `docs/assets`.

### Changed
- **Documentation**: Significant refactoring of the documentation structure, including updated ADRs (ADR-002, ADR-004, ADR-015) and a simplified `README.md` with removed outdated roadmap.
- **Project Structure**: Updated ADR documentation with ADR-013, ADR-014, ADR-015 and expanded `mkdocs.yml` navigation for MCP, Guardrails and HITL support.

### Fixed
- **Documentation**: Fixed version annotation in `ReflectionBehavior.md`.

## [0.12.0] - 2026-03-14

### Added
- **Reflection Pattern (ADR-012)**: Introduced `ReflectionStrategy` and `ReflectionBehavior` for the Generate → Critique → Revise loop.
  - `ReflectionStrategy`, `CritiqueResult`, and `ReflectionConfig` added to `jentic-core` as core abstractions.
  - `DefaultReflectionStrategy` and `ReflectionBehavior` added to `jentic-runtime` for LLM-backed self-critique.
- **Reflection Example**: Added `ReflectionExample` demonstrating the self-correction loop in `jentic-examples`.
- **Documentation**: New dedicated guide for `ReflectionBehavior` and updated `mkdocs.yml` navigation.

### Changed
- **Project Structure**: Expanded ADR documentation with ADR-012 and updated README with Support and Development sections.

## [0.11.0] - 2026-03-11

### Added
- **Configuration-driven package scanning**: `JenticRuntime` now uses `getAllScanPackages()` from configuration for agent discovery.

### Changed
- **Configuration Guide**: Clarified builder method behavior and unchecked `ConfigurationException` handling in documentation.
- **Exception Hierarchy (BREAKING CHANGE)**: Restructured all core exceptions (LLM, Persistence, Memory, Embedding) to inherit from `JenticException` (a `RuntimeException`) and moved them to their respective functional packages (e.g., `dev.jentic.core.persistence`).
- **Configuration Loading**: Simplified `ConfigurationLoader` API by removing explicit checked `ConfigurationException` from `loadFromFile`.
- **Validation Logic**: Improved configuration validation in `JenticRuntime.Builder`, ensuring invalid configurations are caught early.

### Fixed
- **Documentation Workflows**: Fixed table formatting in ADR index and link formatting in documentation deployment workflows.

## [0.10.0] - 2026-03-07

### Added
- **`LLMMemoryAware` interface** in `dev.jentic.core.llm`: marker interface that allows any `Agent` implementor (including those that cannot extend `LLMAgent`) to receive an injected `LLMMemoryManager` from the runtime. `LLMAgent` now implements this interface; `JenticRuntime` injects via `LLMMemoryAware` instead of `instanceof LLMAgent`.
- **`AgentContext` support** for plain `Agent` implementations and improved runtime agent creation.
- **LLM-based summarization** in `SummarizationStrategy` for context window management.
- Promotion of `KnowledgeStore` and `EmbeddingProvider` from adapters to core/runtime for broader availability.

### Fixed
- Increased timing thresholds in `ParallelBehaviorTest` and `SequentialBehaviorTest` for CI reliability.
- Use of dedicated `CachedThreadPool` in test behaviors to prevent ForkJoinPool starvation on CI.

### Changed
- Update of LLM integration guide with new summarization and knowledge store features.
- Updated Logback to version `1.5.32`.
- Updated AssertJ to version `3.27.7`.

## [0.9.0] - 2026-03-04

### Added
- "Getting Started" guide and documentation index.
- GitHub Actions workflow for automatic documentation deployment.
- Test coverage for `jentic-adapters` module.
- Support for detailed Javadoc annotations and usage examples in Jentic annotations.

### Fixed
- Synchronization in `ratelimit` to prevent limit overruns in concurrency scenarios.
- Broken links in README.md file.
- Path normalization in documentation deployment workflow.

### Changed
- Optimization of Maven Javadoc configuration.
- Standardization of link formatting throughout documentation.

## [0.8.0] - 2026-02-28

### Added
- Complete documentation for all behavior types.
- README "Learning Path" for `jentic-examples` module.

### Changed
- Refactoring of examples for a more linear structure and pattern-oriented naming.
- Replacement of `ConfigurationLoader` class with a cleaner interface.
- Improvement of `SimpleBehaviorScheduler` to handle additional behavior types.

### Fixed
- Correction of thresholds in system conditions (CPU usage).
- Simplification of agent registration in `BatchProcessing` example.

## [0.7.1] - 2026-02-24

### Fixed
- Improvement of `STOPPING` state validation in `LifecycleManagerTest`.
- More robust handling of asynchronous operations in the agent lifecycle.

## [0.7.0] - 2026-02-22

### Added
- **Bill of Materials (BOM)** module for centralized version management.
- Support for **A2A (Agent-to-Agent)** protocol with Jetty/HTTP-based implementation.
- LLM integration pattern: **Orchestrator-Workers**.
- **Support Chatbot** example with RAG (Retrieval-Augmented Generation) and TF-IDF semantic search.
- Support for **Automatic-Module-Name** (JPMS) in all modules.
- Extended test framework with JaCoCo coverage and new unit tests for core, runtime, and adapters.
- Code of Conduct and Security Policy.

### Fixed
- Handling of NaN/negative values in system metrics.
- Race condition in agent registration during startup.
- Various fixes in timing tests (ScheduledBehavior, WakerBehavior).

## [0.6.0] - 2026-02-14

### Added
- **LLM Memory Management** system with automatic context injection.
- Strategies for Context Window management in AI agents.
- `AIAgent` base class to facilitate development of agents with LLM support.
- Integration of `MemoryStore` into `JenticRuntime`.

### Changed
- Moved `LLMMemoryManager` responsibility directly into `LLMAgent`.

## [0.5.0] - 2026-02-07

### Added
- **Agent Evaluation Framework** for agent testing and validation.
- Full implementation of dialogue protocols: **ContractNet, Query, Request**.
- Support for utilities to convert dialogues into A2A messages.
- A2A integration example.

### Changed
- Refactoring of `ContractNet` example to use `JenticRuntime`.

## [0.4.0] - 2025-11-20

### Added
- **Jentic Web Console**: web interface for agent monitoring and management.
- Support for message history storage with dedicated REST API.
- CLI tools for message monitoring and watching.
- `MessageSnifferAgent` for passive traffic monitoring.
- `AIAssistantAgent` example with LLM-based tool execution.

### Fixed
- Reactivation of behaviors after agent restart.
- Resolution of classpath in `AgentScanner` for CLI execution.
- Uptime calculation in `RestAPIHandler`.

## [0.3.0] - 2025-11-04

### Added
- Integration with LLM providers: **OpenAI, Anthropic, and Ollama**.
- Support for streaming, function calling, and LLM request/response logging.
- `ResearchTeam` example with agent collaboration and dynamic discovery.
- `baseUrl` configuration for LLM providers (support for proxies and local LLMs).

### Fixed
- Metadata loss in `AgentDescriptor`.
- NPE in handling null content in `OpenAIProvider`.

## [0.2.0] - 2025-10-27

### Added
- Support for **YAML Configuration**.
- New Behavior types:
  - `BatchBehavior`: batch processing by size or time.
  - `RetryBehavior`: retry strategies with backoff.
  - `CircuitBreakerBehavior`: resilience patterns.
  - `PipelineBehavior`: staged processing.
  - `ScheduledBehavior`: cron-like scheduling.
  - `ThrottledBehavior`: rate limiting (Token Bucket, Sliding Window).
  - `CompositeBehavior`: sequential, parallel, and FSM.
  - `ConditionalBehavior`.
- Support for file-based persistence and lifecycle hooks.
- Advanced message filtering and direct messaging.

## [0.1.1] - 2025-10-18

### Fixed
- Minor documentation fixes (README).

## [0.1.0] - 2025-10-17

### Added
- Initial release of Jentic framework.
- Core abstractions for Agents and Behaviors.
- `JenticRuntime` for agent lifecycle management.
- `LifecycleManager` for agent state monitoring.
- Support for agent discovery via annotations.
- ADR-based architecture (Architectural Decision Records).
- Architecture guide and initial documentation.

[Unreleased]: https://github.com/mauro-mura/agenor/compare/v0.30.0...HEAD
[0.30.0]: https://github.com/mauro-mura/agenor/compare/v0.29.0...v0.30.0
[0.29.0]: https://github.com/mauro-mura/agenor/compare/v0.28.0...v0.29.0
[0.28.0]: https://github.com/mauro-mura/agenor/compare/v0.27.0...v0.28.0
[0.27.0]: https://github.com/mauro-mura/agenor/compare/v0.26.0...v0.27.0
[0.26.0]: https://github.com/mauro-mura/agenor/compare/v0.25.0...v0.26.0
[0.25.0]: https://github.com/mauro-mura/agenor/compare/v0.24.1...v0.25.0
[0.24.1]: https://github.com/mauro-mura/agenor/compare/v0.24.0...v0.24.1
[0.24.0]: https://github.com/mauro-mura/agenor/compare/v0.23.0...v0.24.0
[0.23.0]: https://github.com/mauro-mura/jentic/compare/v0.22.0...v0.23.0
[0.22.0]: https://github.com/mauro-mura/jentic/compare/v0.21.0...v0.22.0
[0.21.0]: https://github.com/mauro-mura/jentic/compare/v0.20.0...v0.21.0
[0.20.0]: https://github.com/mauro-mura/jentic/compare/v0.19.0...v0.20.0
[0.19.0]: https://github.com/mauro-mura/jentic/compare/v0.18.0...v0.19.0
[0.18.0]: https://github.com/mauro-mura/jentic/compare/v0.17.0...v0.18.0
[0.17.0]: https://github.com/mauro-mura/jentic/compare/v0.16.0...v0.17.0
[0.16.0]: https://github.com/mauro-mura/jentic/compare/v0.15.0...v0.16.0
[0.15.0]: https://github.com/mauro-mura/jentic/compare/v0.14.0...v0.15.0
[0.14.0]: https://github.com/mauro-mura/jentic/compare/v0.13.0...v0.14.0
[0.13.0]: https://github.com/mauro-mura/jentic/compare/v0.12.0...v0.13.0
[0.12.0]: https://github.com/mauro-mura/jentic/compare/v0.11.0...v0.12.0
[0.11.0]: https://github.com/mauro-mura/jentic/compare/v0.10.0...v0.11.0
[0.10.0]: https://github.com/mauro-mura/jentic/compare/v0.9.0...v0.10.0
[0.9.0]: https://github.com/mauro-mura/jentic/compare/v0.8.0...v0.9.0
[0.8.0]: https://github.com/mauro-mura/jentic/compare/v0.7.1...v0.8.0
[0.7.1]: https://github.com/mauro-mura/jentic/compare/v0.7.0...v0.7.1
[0.7.0]: https://github.com/mauro-mura/jentic/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/mauro-mura/jentic/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/mauro-mura/jentic/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/mauro-mura/jentic/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/mauro-mura/jentic/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/mauro-mura/jentic/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/mauro-mura/jentic/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/mauro-mura/jentic/releases/tag/v0.1.0
