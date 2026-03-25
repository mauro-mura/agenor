# Human-in-the-Loop Checkpoint

The HITL Checkpoint pattern (ADR-015) suspends agent execution until a human
operator approves, rejects, or modifies a critical action. Virtual threads
(ADR-001) park cheaply during the wait — no OS thread is consumed.

## Flow

```
Agent behavior executes critical action
  → HumanCheckpointBehavior.action()
  → ApprovalGate.requestApproval(request)   ← virtual thread parks
  → ApprovalNotifier.notify(request)        ← fire-and-forget (webhook / log)
  ← ApprovalService.submit(requestId, decision)  ← from external system
  → resumes with ApprovalDecision (Approved | Rejected | Modified)
```

## ApprovalDecision

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

The sealed interface enforces exhaustive `switch` at compile time.

---

## Programmatic usage — HumanCheckpointBehavior

Use directly when the notifier requires configuration (e.g. a webhook URL):

```java
var notifier = WebhookApprovalNotifier.builder()
        .url("https://approval.example.com/hitl")
        .header("Authorization", "Bearer " + token)
        .build();

var checkpoint = new HumanCheckpointBehavior<>(
        "payment-checkpoint",
        runtime.getApprovalGate(),       // InMemoryApprovalGate singleton
        notifier,
        paymentPayload,
        "process-payment",
        Duration.ofMinutes(30),
        decision -> switch (decision) {
            case ApprovalDecision.Approved  a -> processPayment(paymentPayload);
            case ApprovalDecision.Rejected  r -> log.warn("Rejected: {}", r.reason());
            case ApprovalDecision.Modified  m -> processPayment((Payment) m.newPayload());
        }
);
agent.addBehavior(checkpoint);
```

---

## Annotation-based usage — @RequiresApproval

Declare the checkpoint on a behavior class. `JenticRuntime` wraps it automatically
at registration time:

```java
@RequiresApproval(timeout = "30m", notifier = WebhookApprovalNotifier.class)
public class DeleteRecordBehavior extends OneShotBehavior {

    @Override
    protected void action() {
        // executed only after Approved or Modified decision
        repository.delete(recordId);
    }
}
```

**Timeout format**: `"30s"`, `"10m"`, `"2h"`.  
**Notifier**: must have a public no-arg constructor. Use the programmatic API for
notifiers that need parameters.

`@RequiresApproval` and `@WithGuardrails` can be combined on the same behavior.

---

## Submitting decisions

`JenticRuntime.getApprovalService()` exposes the decision API:

```java
ApprovalService service = runtime.getApprovalService();

// Approve
service.approve(requestId);

// Reject with reason
service.reject(requestId, "Amount exceeds daily limit");

// Modify payload
service.modify(requestId, new Payment(originalId, reducedAmount));

// Query pending requests (for dashboards / polling UIs)
List<ApprovalRequest> pending = service.getPendingRequests();
```

---

## Timeout handling

If no decision arrives before `expiresAt`, `ApprovalTimeoutException` is thrown
inside `action()` and routed to `BaseBehavior.onError()`. Override `onError()` to
implement fallback or escalation logic:

```java
var checkpoint = new HumanCheckpointBehavior<>(...) {
    @Override
    protected void onError(Exception e) {
        if (e.getCause() instanceof ApprovalTimeoutException t) {
            log.error("Approval timed out for request {}", t.getRequestId());
            alertOncall(t);
        }
    }
};
```

---

## Implementing a custom ApprovalNotifier

Implement `ApprovalNotifier` and use it with `@RequiresApproval` (no-arg constructor
required) or pass it directly to `HumanCheckpointBehavior`:

```java
// Slack notifier example
public class SlackApprovalNotifier implements ApprovalNotifier {

    private final SlackClient slack;

    public SlackApprovalNotifier() {
        this.slack = SlackClient.fromEnv(); // reads SLACK_TOKEN env var
    }

    @Override
    public void notify(ApprovalRequest request) {
        // fire-and-forget — must not throw
        try {
            slack.postMessage("#approvals",
                    "Approval needed: " + request.action()
                    + " (id=" + request.requestId() + ")");
        } catch (Exception e) {
            log.warn("Slack notification failed: {}", e.getMessage());
        }
    }
}
```

---

## HTTP endpoint integration

Expose `ApprovalService` via an HTTP endpoint so external systems can submit decisions:

```java
// Example: Spring Boot controller
@RestController
@RequestMapping("/hitl")
public class ApprovalController {

    private final ApprovalService approvalService;

    @PostMapping("/approve/{requestId}")
    public void approve(@PathVariable String requestId) {
        approvalService.approve(requestId);
    }

    @PostMapping("/reject/{requestId}")
    public void reject(@PathVariable String requestId,
                       @RequestBody RejectionRequest body) {
        approvalService.reject(requestId, body.reason());
    }

    @GetMapping("/pending")
    public List<ApprovalRequest> pending() {
        return approvalService.getPendingRequests();
    }
}
```

---

## Running the example

```bash
mvn exec:java -pl jentic-examples -Dexec.mainClass=dev.jentic.examples.HitlExample
```

The example runs three scenarios: approval, rejection, and payload modification.
No external services required.

---

## See also

- `ADR-015` — architectural decisions: `CompletableFuture` vs `SynchronousQueue` vs Reactor
- `InMemoryApprovalGate` — virtual thread park/unpark implementation
- `WebhookApprovalNotifier` — HTTP POST with retry and exponential backoff
