# ADR-028: Agent Presence — a Driven Liveness Signal, JDBC First

**Status**: Accepted
**Date**: 2026-08-19
**Authors**: Project Team
**References**: ADR-020 (Core API Refactor for Distributed Backends), ADR-023 (Persistent
Agent Directory with JDBC), ADR-022 (`agenor-adapters-persistence` Module Split),
ADR-021 (Redis MessageTransport), ADR-027 (Minimal Runtime Core — LLM / Generic-MAS Module
Split), ADR-004 (Progressive Complexity Strategy), ADR-002 (Interface-First Architecture)

---

## Context

`AgentPresence` has been part of the core API since ADR-020 split `AgentDirectory` into four
capabilities. It declares two methods — `heartbeat(agentId)` and `getStatus(agentId)` — and
`InMemoryAgentDirectory` implements both. ADR-023 then decided that the JDBC directory would
implement the other three capabilities and deliberately not this one.

Three things about the current state have to be stated plainly, because each of them changes
what a presence backend can usefully be.

### 1. Nothing drives the heartbeat

`AgentPresence.heartbeat` has exactly one production call site in the repository:
`CompositeAgentDirectory:111-113`, which delegates to whichever presence implementation the
runtime was built with. Nothing calls *that*. `BaseAgent` does not, `AgenorRuntime` does not,
no behavior does, and no scheduled task exists anywhere in the tree.

The capability is therefore a method an application may call if it knows to, not a signal the
framework produces. `last_seen` on a registered agent moves only when `AgentRegistry.register`
or `updateStatus` writes it — that is, at start-up and at status transitions.

This is the finding that shapes the rest of this ADR. A liveness backend whose whole value is
answering "have I heard from this agent lately?" is worthless if nothing ever speaks.

### 2. ADR-023's exclusion was quantified against a mechanism that was never built

ADR-023's section "Why `AgentPresence` is not implemented" reads:

> `AgentPresence.heartbeat` is called by every agent on a regular interval (default: every
> 10 seconds). At 100 agents this is 10 writes/second to a single table; at 1 000 agents it is
> 100 writes/second.

There is no such interval and no such default. The arithmetic is correct; its premise is not.
The objection was not wrong to raise — a 10-second heartbeat over Postgres *would* be a poor
idea — but it measured a hypothetical, and it hardened into "Postgres is not the right tool
for liveness" without anyone ever having to choose a cadence.

Two further claims in ADR-023 also fail to describe the code. It states that configuring
`agenor.directory.presence: jdbc` "yields a clear startup error
(`UnsupportedCapabilityException`)", but no such exception type exists and
`agenor.directory.presence` is not a property the starter reads — `AgenorProperties.Directory`
has only `provider` and `jdbc`. Setting it today does nothing at all.

### 3. The contract does not say what "status" means over time

`InMemoryAgentDirectory.heartbeat` is literally `updateStatus(agentId, RUNNING)`, so a
heartbeat *is* a status write, and `getStatus` returns the stored status while ignoring
`lastSeen` entirely. A store with no expiry can behave that way. A store meant to report
liveness cannot: it has to distinguish "this agent said `RUNNING` and is still saying so" from
"this agent said `RUNNING` an hour ago and has been silent since".

Until that distinction is in the contract, two backends can both satisfy `AgentPresence` and
disagree about every interesting case.

---

## Decision

### D-1 — A heartbeat is a liveness signal and nothing else

`heartbeat(agentId)` refreshes the agent's last-seen timestamp. It does **not** change the
agent's `AgentStatus`. Status transitions remain `AgentRegistry.updateStatus`'s job.

`InMemoryAgentDirectory` changes accordingly: it refreshes `lastSeen` and leaves `status`
untouched. This is a **behaviour change to shipped code**, not an addition — an agent left in
`STARTING` will no longer be promoted to `RUNNING` by a heartbeat. It is declared in the
CHANGELOG with a migration note, and `AgentPresenceContractTests.heartbeat_setsRunning` is
rewritten to assert the new rule.

The reason is that the old behaviour cannot be implemented honestly by a backend with expiry.
Under a TTL store (Phase B) a heartbeat refreshes a key; it has no status to promote, and
inventing one would make the three implementations disagree.

### D-2 — Staleness is part of the contract; the window is a property of the backend

`getStatus(agentId)` returns `UNKNOWN` when the agent is not registered, **or** when it has
not been seen within the implementation's staleness window.

The window itself is not fixed by the contract. Each implementation documents its own, and an
**unbounded** window is a legal value meaning "this backend does not expire status". That is
exactly today's in-memory behaviour, which therefore keeps satisfying the contract unchanged
and keeps `getStatus_reflectsLastUpdate` passing as written.

This is the smallest rule that lets a TTL backend, a timestamp-comparing backend and a
never-expiring backend all be correct without any of them lying.

### D-3 — An opt-in heartbeat driver in `agenor-runtime`

`AgentHeartbeatDriver` (package `dev.agenor.runtime.directory`) periodically calls
`presence.heartbeat(agentId)` for every agent the runtime has started. It is **off by default**
and starts only when an interval is configured.

- One `ScheduledExecutorService` with a single daemon thread for the whole runtime, not one
  timer per agent, and explicitly **not** `SimpleBehaviorScheduler` — that pool is four
  platform threads shared by every behavior of every agent, and a periodic I/O task does not
  belong in it.
- The timer thread only submits: `heartbeat` returns a `CompletableFuture` and the JDBC
  implementation runs its work on `JdbcHelper`'s virtual-thread executor, so a slow database
  delays heartbeats but never blocks the runtime.
- Started after `startAllAgents()` and stopped before agents stop, so it never heartbeats an
  agent that is no longer registered.
- Configured by `AgenorRuntime.Builder.heartbeatInterval(Duration)` and, in Spring, by
  `agenor.directory.heartbeat-interval`. Absent means the driver never starts and nothing
  changes for existing deployments.
- **Recommended minimum: 15–30 seconds.** D-4 explains why the number carries weight.

Opt-in rather than on-by-default follows directly from §2: ADR-023's objection was about write
volume, and switching on a periodic `UPDATE` for every existing JDBC user without their asking
would earn that objection rather than answer it.

### D-4 — Phase A: `JdbcAgentPresence`

**No new table and no migration.** `agenor_agents` already carries `status` and `last_seen`
with a primary key on `agent_id` (`V1__create_agent_directory.sql`).

- `heartbeat` → `UPDATE agenor_agents SET last_seen = ? WHERE agent_id = ?` — strictly lighter
  than `JdbcAgentRegistry`'s existing `UPDATE_STATUS`, which writes both columns.
- `getStatus` → `SELECT status, last_seen FROM agenor_agents WHERE agent_id = ?`, returning
  `UNKNOWN` if no row exists or if `now - last_seen` exceeds the staleness window. The check
  happens at read time because a relational store has no key expiry.
- **Default staleness window: 90 seconds** — three missed heartbeats at the recommended
  30-second cadence. Configurable.
- **Placement**: `agenor-adapters-persistence`, package
  `dev.agenor.adapters.persistence.directory`, class `JdbcAgentPresence`, sibling to
  `JdbcAgentRegistry` / `JdbcAgentDiscovery` / `JdbcAgentResolver`, reusing the same
  `JdbcHelper` and connection pool.
- **Exposure**: `JdbcAgentDirectory.presence()` returns a cached instance using the default
  window; `presence(Duration)` returns one with a chosen window.

`JdbcDirectoryConfig` is deliberately **not** extended. It is a `record` whose canonical
constructor is called directly at `AgenorAutoConfiguration:467` and again at `:567` — where it
configures the **HITL** pool, which has nothing to do with presence. Adding a component would
break the public constructor and place a presence setting in a record two unrelated features
already share.

**On write volume.** At the recommended 30-second cadence, 1 000 agents produce roughly 33
single-row `UPDATE`s per second against an indexed primary key. That is an ordinary OLTP
pattern, and a third of the load ADR-023's 10-second hypothetical implied at the same scale.
Phase A does not claim Postgres is a good liveness store at high frequency. It claims that at a
*coarse* cadence the objection no longer applies, and that a failure-detection latency of tens
of seconds is a fair price for adding no new infrastructure.

### D-5 — Phase B: Redis TTL, designed here, built later

Recorded so the capability is not designed twice, and explicitly not implemented under this
ADR:

- Redis string key with TTL: `SET agenor:presence:<agentId> <AgentStatus> PX <ttl>`. Not
  Streams, not Pub/Sub — the contract is poll-based.
- TTL default 3× the heartbeat interval, matching D-4's ratio.
- `getStatus`: missing key → `UNKNOWN`. Expiry *is* the staleness window, so D-2 is satisfied
  by the store rather than by a read-time comparison.
- Placement: `agenor-adapters`, reusing the Lettuce `optional=true` dependency already declared
  for messaging (ADR-018).

Phase B buys seconds instead of tens of seconds of detection latency, at the cost of a new
infrastructure dependency. A deployment on the JDBC directory can adopt Phase A alone and stop
there.

### D-6 — The capability contract tests move to `agenor-core`

`AgentRegistryContractTests`, `AgentResolverContractTests`, `AgentDiscoveryContractTests` and
`AgentPresenceContractTests` currently live in `agenor-runtime/src/test`, are typed on
`AgentDirectory createSubject()`, and no module publishes a `test-jar`. So
`InMemoryAgentDirectoryContractTest`'s stated promise — "future adapters (Redis, JDBC) follow
the same pattern" — cannot be kept: `agenor-adapters-persistence` depends on `agenor-core`
only, and `JdbcAgentDirectory` is a `Closeable` holding accessors, not an `AgentDirectory`.

The four suites move to `agenor-core/src/test/java/dev/agenor/core/directory/` and
`agenor-core` publishes a `test-jar`. They are contracts for core interfaces, and core is
already every module's dependency, so no new module edge appears. `AgentPresenceContractTests`
is retyped onto the capability — a subject hook plus a seeding hook — so a standalone
`JdbcAgentPresence` can satisfy it.

### D-7 — ADR-027's conventions are discharged

**C1** forbids new feature accessors on `AgenorRuntime`: none is added. The driver is internal,
and `Builder.heartbeatInterval(Duration)` is a builder setter — the same shape as the existing
`agentRegistry` / `agentDiscovery` / `agentResolver` / `agentPresence` setters.

**C2** requires evaluating consolidation before a sixth core SPI: no SPI is added.
`AgentPresence` is one of the five ADR-020 already established, and this ADR only gives it
semantics and implementations.

---

## Alternatives considered

**Leave presence in-memory only, as ADR-023 decided.** Rejected because the reason recorded in
ADR-023 does not survive contact with the code (§2). Once the cadence is a choice rather than an
assumed 10 seconds, "Postgres is the wrong tool" becomes "Postgres is the wrong tool *at high
frequency*" — a statement about configuration, not about capability.

**Ship `JdbcAgentPresence` with no driver.** Rejected. Nothing would refresh `last_seen`, so
every agent would read `UNKNOWN` one window after start-up. A feature that reports the opposite
of the truth by default is worse than no feature.

**Drive the heartbeat on by default.** Rejected: it would start writing to Postgres every 30
seconds for every existing JDBC deployment without being asked. See D-3.

**Batch heartbeat updates.** ADR-023 considered and rejected this; still rejected, but for a
different reason than it gave. At a coarse cadence the volume does not need batching, and
batching would blur the very timestamp the window is measured against.

**Add the staleness window to `JdbcDirectoryConfig`.** Rejected on the two grounds in D-4.

**A `CyclicBehavior` per agent as the driver.** Rejected: it would fill the four-thread
`SimpleBehaviorScheduler` pool with periodic I/O, and presence is a runtime concern rather than
an agent-authored one.

---

## Consequences

### Positive

- Presence stops being a method nobody calls and becomes a signal the runtime can produce.
- The JDBC directory gains its fourth capability, so a deployment can be entirely JDBC-backed
  instead of mixing in an in-memory presence whose state dies with the node.
- `getStatus` becomes meaningful across nodes: a second runtime can ask about an agent it never
  started and get an answer that expires.
- One contract suite, reachable from every module, so Phase B and any future backend are proved
  against the same rules rather than against re-typed copies.
- ADR-004's ladder gains its presence rung: in-memory → JDBC-coarse → Redis-TTL.

### Negative / trade-offs

- **A shipped behaviour changes.** `heartbeat` no longer promotes `STARTING` to `RUNNING`. Code
  that relied on it — the only in-tree example is the contract test itself — must call
  `updateStatus` instead.
- **Detection latency is coarse**: tens of seconds to about two minutes at the recommended
  cadence. Phase A is not a substitute for Phase B in latency-sensitive routing.
- **Write load is real when the driver is enabled**, roughly one `UPDATE` per agent per
  interval. Bounded and predictable, but not zero, and the default cadence must stay coarse for
  D-4's argument to hold.
- **`agenor-core` gains a `test-jar`**, a build artefact that did not exist. It is test-scoped
  and not published as API, but it is one more thing the reactor produces.
- **Staleness is evaluated at read time on JDBC**, so an answer is only as trustworthy as the
  clock skew between the writing and reading nodes allows. The window is coarse enough to
  absorb ordinary NTP skew; the dependency is stated rather than hidden.

---

## Amendment to ADR-023

**Scope: the section "Why `AgentPresence` is not implemented" only.** ADR-023's decisions on the
registry, resolver and discovery implementations, its schema, its data-access strategy and its
rejection of multi-endpoint routing are untouched and remain in force. Its Status stays
`Accepted`; this is not a replacement.

Superseded: the conclusion that the JDBC module will not implement `AgentPresence`. Phase A
implements it — opt-in, and at a coarse cadence.

Corrected as a matter of record, because a later reader would otherwise take them as
descriptions of the code:

- The "default: every 10 seconds" heartbeat interval, and the 10 and 100 writes/second figures
  derived from it, describe a mechanism that was never implemented. The arithmetic holds for
  that cadence; the cadence was hypothetical.
- "Attempting to configure `agenor.directory.presence: jdbc` yields a clear startup error
  (`UnsupportedCapabilityException`)" is not true. No such exception type exists, and the
  property is not read.

ADR-023's underlying engineering judgement — that a relational store is a poor fit for
*high-frequency* liveness — is not overturned. It is bounded to the frequency it was actually
about.

---

## Implementation and verification

Phase A only. The work is tracked in `work-plan-adr028-redis-agent-presence-20260810.md`.

- `agenor-core`: `AgentPresence` Javadoc states D-1 and D-2 without naming a backend; the four
  contract suites move here and a `test-jar` is published. The deprecated
  `dev.agenor.core.AgentDirectory` facade carries a `default heartbeat` at `:93` and must be
  checked for the same stale wording.
- `agenor-runtime`: `InMemoryAgentDirectory.heartbeat` becomes liveness-only;
  `AgentHeartbeatDriver` is added and wired into `AgenorRuntime.start()` / `stop()`.
- `agenor-adapters-persistence`: `JdbcAgentPresence`, plus `JdbcAgentDirectory.presence()`.
- `agenor-spring-boot-starter`: `agenor.directory.presence` and
  `agenor.directory.heartbeat-interval` in `AgenorProperties.Directory`, honoured by the nested
  `JdbcDirectoryConfiguration` at `AgenorAutoConfiguration:455`, whose Javadoc at `:448` and
  `:501` still says presence falls back to in-memory.

Verification:

- The relocated contract suite passes against `InMemoryAgentDirectory` and `JdbcAgentPresence`
  from one source.
- Unit tests on H2 cover the window at, just under and just over the threshold, and that a
  heartbeat leaves `status` unchanged.
- **An integration test on real Postgres** covers concurrent heartbeats from two simulated
  nodes, and a stopped node's agent reading `UNKNOWN` after its window. This is not optional:
  every one of the four defects found in ADR-028 Part 1 lived in a seam between components that
  were individually well tested, and each was invisible to the unit suite.
- With `agenor.directory.heartbeat-interval` absent, runtime behaviour is exactly what it is
  today.

---

## Related ADRs

- **ADR-020** (Core API Refactor for Distributed Backends): established `AgentPresence` as one
  of the four directory capabilities. This ADR gives it semantics and a second implementation;
  it adds no capability and no SPI.
- **ADR-023** (Persistent Agent Directory with JDBC): amended in scope above. Its schema is
  reused unchanged — Phase A adds no table, no column and no migration.
- **ADR-022** (`agenor-adapters-persistence` Module Split): `JdbcAgentPresence` lands in that
  module, beside the three capabilities ADR-023 put there.
- **ADR-021** (Redis MessageTransport) and **ADR-018** (Optional Adapter Dependencies): Phase B
  reuses the Lettuce optional dependency they established rather than adding one.
- **ADR-027** (Minimal Runtime Core): C1 and C2 discharged in D-7.
- **ADR-004** (Progressive Complexity Strategy): presence gains the ladder every other stateful
  capability already has — in-memory for development, JDBC without new infrastructure, Redis
  when detection latency matters.
- **ADR-002** (Interface-First Architecture): the reason D-2 puts the staleness *rule* in the
  contract and the staleness *window* in the implementation.
