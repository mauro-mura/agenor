# ADR-015 — Human-in-the-Loop Checkpoint

**Status**: Accepted  
**Date**: 2026-03-24  
**Deciders**: Project team  
**Related**: ADR-001 (Java 21 Virtual Threads), ADR-002 (Interface-First), ADR-005 (Records), ADR-006 (Annotations), ADR-014 (Guardrails Layer)

---

## Context

Agent workflows may execute critical, irreversible actions (financial transactions, data deletion,
external API calls with side-effects) that require human validation before proceeding.
The current runtime provides no mechanism to suspend execution, request human approval, and resume
or abort based on the decision received.

Constraints:
- `jentic-core` must remain free of external dependencies (ADR-002)
- Agents without checkpoints must behave identically to current behavior (backward compat)
- Suspension must not consume an OS thread while waiting — virtual threads (ADR-001) make this cheap
- The decision type must be exhaustive and compiler-enforced, consistent with `GuardrailResult` (ADR-014)
- Must integrate with the existing annotation-based configuration pattern (ADR-006)

---

## Decision

Introduce a **non-blocking approval gate** (`ApprovalGate`) that suspends the agent's virtual thread
via `CompletableFuture`, notifies an external observer (`ApprovalNotifier`), and resumes when
`ApprovalService.submit()` completes the future with an `ApprovalDecision`.

```
Agent behavior executes critical action
  → HumanCheckpointBehavior.execute()
  → ApprovalGate.requestApproval(request)     ← virtual thread parks on future.join()
  → ApprovalNotifier.notify(request)          ← fire-and-forget (webhook / log)
  ← ApprovalService.submit(requestId, decision) ← from external system (HTTP / test / webhook)
  → resumes with ApprovalDecision (APPROVED / REJECTED / MODIFIED)
```

Declarative wiring via `@RequiresApproval` annotation processed by `JenticRuntime` at agent
registration time, consistent with `@WithGuardrails` (ADR-014).

---

## Alternatives Considered

### Option A — `CompletableFuture` + virtual thread park ✅ Chosen

```java
// InMemoryApprovalGate internals
CompletableFuture<ApprovalDecision> future = new CompletableFuture<>();
pendingRequests.put(request.requestId(), future);
scheduledExecutor.schedule(
    () -> future.completeExceptionally(new ApprovalTimeoutException(request)),
    timeout, TimeUnit.MILLISECONDS);
return future;

// Agent side — virtual thread parks here, no OS thread consumed
ApprovalDecision decision = gate.requestApproval(request).join();
```

**Pros**:
- Coherent with the existing codebase (`CompletableFuture` used throughout Jentic)
- Composable: callers can chain `.thenApply()`, `.whenComplete()`, set their own timeout
- Virtual thread park/unpark is near-zero cost (Project Loom, ADR-001)
- Timeout via `ScheduledExecutorService` — no additional dependency
- `completeExceptionally(ApprovalTimeoutException)` integrates cleanly with the `JenticException` hierarchy

**Cons**:
- Callers must not call `.join()` on a carrier/platform thread; documented constraint

---

### Option B — `SynchronousQueue<ApprovalDecision>` ❌ Rejected

**Pros**:
- Simpler implementation

**Cons**:
- No native timeout support without wrapping in an additional thread or `poll(timeout, unit)`
- Harder to cancel or compose
- Less expressive than `CompletableFuture` for callers needing async callbacks

---

### Option C — Reactor `Mono<ApprovalDecision>` ❌ Rejected

**Pros**:
- Idiomatic for reactive codebases

**Cons**:
- Introduces Project Reactor as a dependency in `jentic-runtime` — unacceptable overhead
- Breaks the zero-external-dep rule for `jentic-core`
- Mismatched abstraction for a primarily imperative/virtual-thread runtime

---

## `ApprovalDecision` as Sealed Interface

Consistent with `GuardrailResult` (ADR-014), `ApprovalDecision` is modeled as a sealed interface
with record variants to enforce exhaustive `switch` at compile time:

```java
public sealed interface ApprovalDecision
        permits ApprovalDecision.Approved,
                ApprovalDecision.Rejected,
                ApprovalDecision.Modified {

    record Approved()                  implements ApprovalDecision {}
    record Rejected(String reason)     implements ApprovalDecision {}
    record Modified(Object newPayload) implements ApprovalDecision {}
}
```

Usage in `HumanCheckpointBehavior`:

```java
ApprovalDecision decision = gate.requestApproval(request).join();
switch (decision) {
    case ApprovalDecision.Approved a  -> executeAction(request.payload());
    case ApprovalDecision.Rejected r  -> log.warn("Rejected: {}", r.reason());
    case ApprovalDecision.Modified m  -> executeAction(m.newPayload());
}
```

---

## Timeout Management

Timeout is scheduled via a single `ScheduledExecutorService` shared across all pending requests:

```java
scheduledExecutor.schedule(
    () -> future.completeExceptionally(
            new ApprovalTimeoutException(request.requestId(), request.expiresAt())),
    Duration.between(Instant.now(), request.expiresAt()).toMillis(),
    TimeUnit.MILLISECONDS);
```

`ApprovalTimeoutException` extends `JenticException` (unchecked), carrying `requestId` and
`expiresAt` for logging and alerting. Expired entries are removed from the pending map in the
timeout callback to prevent memory leaks.

---

## Architecture

### Module Boundary

```
jentic-core
  └── dev.jentic.core.hitl
        ├── ApprovalRequest.java          (record — UUID, agentId, action, payload, expiresAt, metadata)
        ├── ApprovalDecision.java         (sealed interface — Approved | Rejected | Modified)
        ├── ApprovalGate.java             (interface — requestApproval returns CompletableFuture)
        ├── ApprovalNotifier.java         (SPI interface — fire-and-forget notify)
        ├── ApprovalTimeoutException.java (unchecked, extends JenticException)
        └── RequiresApproval.java         (@interface annotation — timeout, notifier class)

jentic-runtime
  └── dev.jentic.runtime.hitl
        ├── InMemoryApprovalGate.java     (ConcurrentHashMap + ScheduledExecutorService)
        ├── ApprovalService.java          (facade — approve / reject / modify / getPendingRequests)
        ├── HumanCheckpointBehavior.java  (wraps a critical action, parks on gate future)
        ├── LoggingApprovalNotifier.java  (SLF4J WARN, zero deps — default)
        └── WebhookApprovalNotifier.java  (HTTP POST, java.net.http, retry + backoff)
```

### Annotation Wiring

```java
@RequiresApproval(timeout = "30m", notifier = WebhookApprovalNotifier.class)
public class PaymentBehavior extends AgentBehavior { ... }
```

`JenticRuntime.registerAgent()` detects `@RequiresApproval`, parses the `timeout` string
(`"5s"` / `"10m"` / `"2h"`), instantiates the notifier via no-arg constructor, and wraps
the behavior in `HumanCheckpointBehavior` with the singleton `InMemoryApprovalGate`.

`ApprovalService` is exposed as a singleton via `runtime.getApprovalService()`, providing
the external decision submission interface and a `getPendingRequests()` snapshot for dashboards.

---

## Consequences

### Positive
- Critical actions cannot execute without explicit human approval when `@RequiresApproval` is present
- Virtual thread parking imposes no OS thread cost during wait (ADR-001)
- `ApprovalDecision` sealed interface prevents unhandled decision variants at compile time
- `ApprovalService.getPendingRequests()` enables UI/dashboard integration without additional state
- Backward compatible: agents without `@RequiresApproval` are unaffected

### Negative
- `.join()` on a platform thread would block it; callers must use virtual threads or async chaining
- `@RequiresApproval` requires no-arg constructor for `ApprovalNotifier` — notifiers needing config must be wired programmatically via `HumanCheckpointBehavior` builder

### Neutral
- `ApprovalTimeoutException` is unchecked, consistent with `JenticException` hierarchy; callers may catch it explicitly but are not required to declare it
- `WebhookApprovalNotifier` retry logic runs on a separate virtual thread and does not affect agent execution latency

## Compliance

- `jentic-core` package `dev.jentic.core.hitl` must have zero external dependencies (verified by `mvn dependency:analyze`)
- Coverage ≥ 80% on `dev.jentic.core.hitl` and `dev.jentic.runtime.hitl` (enforced by JaCoCo in `mvn verify`)
- Agents without `@RequiresApproval` verified unaffected by existing test suite passing unchanged

## Notes

- Related: ADR-001 (Virtual Threads), ADR-002 (Interface-First), ADR-005 (Records), ADR-006 (Annotations), ADR-014 (GuardrailResult sealed interface pattern)
- `@WithGuardrails` (ADR-014) and `@RequiresApproval` can be combined on the same behavior; they operate independently
- `WebhookApprovalNotifier` uses `java.net.http.HttpClient` (JDK 11+) — no additional HTTP library required
