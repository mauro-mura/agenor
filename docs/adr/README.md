# Architecture Decision Records

This directory contains Architecture Decision Records (ADRs) for the Agenor project. ADRs are documents that capture important architectural decisions made during the development of the project, along with their context and consequences.

## ADR Index

| #                                                                   | Title                                    | Status   | Date       |
|---------------------------------------------------------------------|------------------------------------------|----------|------------|
| [ADR-001](ADR-001-use-java-21-with-virtual-threads.md)              | Use Java 21 with Virtual Threads         | Accepted | 2025-09-16 |
| [ADR-002](ADR-002-interface-first-architecture.md)                  | Interface-First Architecture             | Accepted | 2025-09-16 |
| [ADR-003](ADR-003-maven-multi-module-structure.md)                  | Maven Multi-Module Structure             | Accepted | 2025-09-16 |
| [ADR-004](ADR-004-progressive-complexity-strategy.md)               | Progressive Complexity Strategy          | Accepted | 2025-09-16 |
| [ADR-005](ADR-005-json-message-format-with-records.md)              | JSON Message Format with Records         | Accepted | 2025-09-16 |
| [ADR-006](ADR-006-annotation-based-agent-configuration.md)          | Annotation-Based Agent Configuration     | Accepted | 2025-09-16 |
| [ADR-007](ADR-007-llm-core.md)                                      | LLMProvider as Core Interface            | Accepted | 2025-11-04 |
| [ADR-008](ADR-008-WebConsole-Interface-First.md)                    | WebConsole Interface-First Design        | Accepted | 2025-11-26 |
| [ADR-009](ADR-009-agent-dialogue-protocol.md)                       | Agent Dialogue Protocol                  | Accepted | 2025-12-13 |
| [ADR-010](ADR-010-llm-memory-management.md)                         | LLM Memory Management                    | Accepted | 2025-12-23 |
| [ADR-011](ADR-011-knowledge-store-core.md)                          | KnowledgeStore as Core Interface         | Accepted | 2026-03-05 |
| [ADR-012](ADR-012-reflection-behavior.md)                           | ReflectionStrategy as Core Interface     | Accepted | 2026-03-12 |
| [ADR-013](ADR-013-mcp-adapter.md)                                   | MCP Adapter                              | Accepted | 2026-03-16 |
| [ADR-014](ADR-014-guardrails-layer.md)                              | Guardrails Layer                         | Accepted | 2026-03-23 |
| [ADR-015](ADR-015-hitl-checkpoint.md)                               | Human-in-the-Loop Checkpoint             | Accepted | 2026-03-24 |
| [ADR-016](ADR-016-spring-boot-starter.md)                           | Spring Boot Starter Module               | Proposed | 2026-03-26 |
| [ADR-017](ADR-017-llmrequest-model-optional.md)                     | LLMRequest model field — optional        | Accepted | 2026-04-12 |
| [ADR-018](ADR-018-optional-adapter-dependencies-pattern.md)         | Optional Adapter Dependencies Pattern    | Accepted | 2026-04-23 |
| [ADR-019](ADR-019-opentelemetry-instrumentation.md)                 | OpenTelemetry Instrumentation            | Accepted | 2026-04-23 |
| [ADR-020](ADR-020-core-api-refactor.md)                             | Core API Refactor for Distributed Backends | Accepted | 2026-04-26 |
| [ADR-021](ADR-021-redis-message-transport.md)                       | Redis MessageTransport — Pub/Sub vs Streams | Accepted | 2026-05-10 |
| [ADR-022](ADR-022-adapters-persistence-module-split.md)             | `agenor-adapters-persistence` Module Split  | Accepted | 2026-05-17 |
| [ADR-023](ADR-023-persistent-agent-directory-jdbc.md)               | Persistent Agent Directory with JDBC        | Accepted | 2026-05-17 |
| [ADR-024](ADR-024-persistent-hitl-approval-queue.md)               | Persistent HITL Approval Queue (JDBC)       | Accepted | 2026-05-22 |
| [ADR-025](ADR-025-agenor-rebrand.md)                                | Agenor Rebrand — Naming, Compat, Versioning | Accepted | 2026-05-28 |
| [ADR-026](ADR-026-request-protocol-final-resolution.md)            | REQUEST Protocol Final-Resolution Semantics | Accepted | 2026-07-23 |
| [ADR-027](ADR-027-minimal-runtime-llm-generic-split.md)             | Minimal Runtime Core — LLM / Generic-MAS Module Split | Accepted | 2026-08-03 |
| [ADR-028](ADR-028-agent-presence-jdbc-and-redis.md)                 | Agent Presence — a Driven Liveness Signal, JDBC First | Accepted | 2026-08-19 |
| [ADR-029](ADR-029-protocol-validation-and-dialogue-message-classification.md) | Protocol Validation Enforcement and Dialogue Message Classification | Accepted | 2026-08-15 |
| [ADR-030](ADR-030-message-content-typing-across-transports.md)      | `Message.content` Typing Across Transports | Accepted | 2026-08-16 |
| [ADR-031](ADR-031-conversation-state-durability.md)                 | Conversation-State Durability            | Accepted | 2026-08-16 |
| [ADR-032](ADR-032-agent-mailbox-single-inbound-path.md)             | Agent Mailbox — a Single Inbound Path per Agent | Accepted | 2026-08-23 |
| [ADR-033](ADR-033-mailbox-delivery-semantics.md)                    | Mailbox Delivery Semantics — the Mailbox Carries the Chain | Accepted | 2026-08-24 |

> **Rows without a link are claimed numbers, not written documents.** They are listed so that two
> parallel efforts cannot both pick "the next free number in `docs/adr/`" and collide. There are
> none at present: ADR-028, the last reserved number, was written on 2026-08-19.

---

## How to Use This ADR Collection

### For Developers

1. **Before Making Architectural Changes**: Read relevant ADRs to understand current decisions
2. **When Proposing Changes**: Create new ADR or update existing one
3. **During Code Reviews**: Reference ADRs to justify architectural choices
4. **When Onboarding**: ADRs provide context for why things are built this way

### For Contributors

1. **Understanding Decisions**: ADRs explain the "why" behind architectural choices
2. **Proposing Alternatives**: Create ADR to document alternative approaches
3. **Maintaining Consistency**: Use ADRs to ensure consistent decision-making

### ADR Lifecycle

1. **Proposed**: New ADR under discussion
2. **Accepted**: Decision made and implemented
3. **Deprecated**: Superseded by newer decision
4. **Rejected**: Considered but not adopted

## ADR Template

When creating new ADRs, use this template:

```markdown
# ADR-XXX: [Title]

**Status**: [Proposed/Accepted/Deprecated/Rejected]  
**Date**: YYYY-MM-DD  
**Last Modified**: YYYY-MM-DD
**Authors**: [List of authors]  
**Replaces**: [ADR number if replacing an existing decision]  
**Replaced By**: [ADR number if this decision was superseded]  

## Context

[Describe the forces at play, including technological, political, social, and project local.]

## Decision

[State the architecture decision and provide rationale.]

## Rationale

### Pros
- [List benefits of this approach]

### Cons  
- [List drawbacks and trade-offs]

### Alternatives Considered
- **Alternative 1**: [Brief description and why it was rejected]
- **Alternative 2**: [Brief description and why it was rejected]

## Implementation

[Provide concrete examples, code snippets, or configuration that demonstrates the decision.]

## Consequences

### Positive
- [List positive consequences]

### Negative
- [List negative consequences]

### Neutral
- [List neutral consequences]

## Compliance

[How will adherence to this decision be monitored and enforced?]

## Notes

[Any additional notes, references, or related information]
```

---

## Future ADRs

New ADRs will be created when architectural decisions are needed. ADRs are created on-demand,
not planned in advance, to maintain flexibility and avoid premature commitments.

When a new architectural decision is required:
1. Assess if it warrants an ADR (significant impact, affects multiple components, long-term implications)
2. Create the ADR using the template above
3. Discuss with the team and stakeholders
4. Update this index when the ADR is accepted

---

## Decision History

### Major Architectural Phases

**Phase 1 - Foundation**
- Established core technology choices
- Defined modular architecture
- Set development principles

**Phase 2 - Implementation**
- Define implementation standards
- Establish quality practices
- Set operational guidelines

**Phase 3 - Enterprise**
- Advanced features and extensibility
- Production-ready capabilities
- Enterprise integration patterns (Spring Boot starter, HITL, Guardrails)

### Technology Evolution

```mermaid
graph TD
    A[Java 21 + Virtual Threads] --> B[Interface-First Design]
    B --> C[Maven Multi-Module]
    C --> D[Progressive Complexity]
    D --> E[JSON + Records]
    E --> F[Annotation Config]
    F --> G[LLMProvider Core]
    G --> H[MCP Adapter]
    H --> I[Guardrails + HITL]
    I --> J[Spring Boot Starter]
```

### Decision Dependencies

- **ADR-001** (Java 21) enables **ADR-005** (Records)
- **ADR-002** (Interfaces) enables **ADR-004** (Progressive Complexity)
- **ADR-003** (Maven Modules) supports **ADR-002** (Interface Architecture)
- **ADR-006** (Annotations) builds on **ADR-005** (Message Format)
- **ADR-015** (HITL) builds on ADR-001, ADR-005, ADR-006, ADR-014
- **ADR-016** (Spring Boot Starter) builds on ADR-003, ADR-004, ADR-007
- **ADR-017** (LLMRequest optional model) refines ADR-007 (LLMProvider as Core Interface)
- **ADR-018** (Optional Adapter Deps) governs ADR-019 and future adapter ADRs
- **ADR-019** (OTel Instrumentation) builds on ADR-002, ADR-003, ADR-018
- **ADR-020** (Core API Refactor) builds on ADR-002, ADR-004; prerequisite for ADR-021, ADR-022, ADR-023
- **ADR-021** (Redis MessageTransport) builds on ADR-001, ADR-018, ADR-020
- **ADR-022** (`agenor-adapters-persistence` Module Split) builds on ADR-003, ADR-004, ADR-018, ADR-020; prerequisite for ADR-023, ADR-024
- **ADR-023** (Persistent Agent Directory with JDBC) builds on ADR-001, ADR-002, ADR-019, ADR-020, ADR-022
- **ADR-024** (Persistent HITL Approval Queue) builds on ADR-001, ADR-004, ADR-015, ADR-022, ADR-023
- **ADR-025** (Agenor Rebrand) builds on ADR-002, ADR-003, ADR-006, ADR-016, ADR-020 — affects naming and Maven coordinates for the entire project
- **ADR-009** (Agent Dialogue Protocol) was amended on 2026-09-01: a commitment's performer and requester derive from the `Protocol`, not from the performative alone. The amendment applies **ADR-029** D5's rule — direction is a property of the protocol and is derivable rather than guessable — to a second consumer, and records two limitations it deliberately leaves open: the commitment record is one-sided, and two of the four committing performatives never create one
- **ADR-026** (REQUEST Protocol Final-Resolution Semantics) resolves the "Known Limitations" gap left open by ADR-009
- **ADR-027** (Minimal Runtime Core — LLM/Generic-MAS Module Split) builds on ADR-002, ADR-004, ADR-018, ADR-020
- **ADR-028** (Agent Presence — a Driven Liveness Signal, JDBC First) builds on **ADR-020**, which established `AgentPresence` as a capability, and on **ADR-022** / **ADR-023**, whose module and schema it reuses without adding a table, column or migration. It amends ADR-023's presence exclusion in scope, having found that the write-volume objection was quantified against a heartbeat interval that was never implemented — nothing in the tree ever called `heartbeat()` on a schedule, which is why this ADR adds an opt-in driver rather than only a backend. It gives presence the rung **ADR-004**'s ladder was missing, and discharges **ADR-027**'s C1 and C2 by adding neither a runtime accessor nor a sixth SPI. Phase B is designed against **ADR-021**'s Redis dependency under **ADR-018**'s optional-adapter pattern, and deliberately not built here
- **ADR-029** (Protocol Validation Enforcement and Dialogue Message Classification) builds on ADR-002 and ADR-009, and generalises the sender-perspective reading of `allowedPerformatives()` that ADR-026 introduced
- **ADR-030** (`Message.content` Typing Across Transports) amends **ADR-005** with a scoped note on receiving-side typing; the defect it fixes manifests only on ADR-021's transport, and the silent break it removes is the one ADR-004 promises cannot happen
- **ADR-031** (Conversation-State Durability) fills the dialogue rung ADR-004's ladder was missing — deliberately at the first step — and bounds ADR-009's model; it depends on ADR-030, because affirming cross-runtime dialogue is only honest once non-`String` payloads survive the transport
- **ADR-032** (Agent Mailbox — a Single Inbound Path per Agent) gives the inbound path an owner: one mailbox holds the only recipient subscription per agent, so `BaseAgent` dispatch and ADR-009's `DialogueCapability` become consumers of one drain rather than rival subscribers. It removes the double subscription **ADR-029** identified but left in place, reuses that ADR's pure classifier as its routing predicate, and amends its D3 in scope. It defers delivery semantics to **ADR-021**'s at-least-once transport and discharges **ADR-027**'s C1 and C2 conventions by adding neither a runtime accessor nor a sixth SPI. The claim ordering, backpressure and receive-side interception point it establishes are the **ADR-004** promise that dispatcher-dependent behaviour was breaking. It deliberately adds no pull API — see its *Alternatives considered*
- **ADR-033** (Mailbox Delivery Semantics) closes what **ADR-032** left implicit. ADR-032's D-3 placed delivery semantics out of scope on the reading that the mailbox added none; what it did was *terminate* the at-least-once chain **ADR-021** provides, by acknowledging at enqueue. The mailbox now reports the outcome of processing, so a failing handler is redelivered and dead-lettered as `docs/adapters/redis.md` always promised. It bounds concurrent handlers — the backpressure ADR-032 claimed but did not deliver, since its queue bound sat in front of an unbounded thread spawn — without reintroducing the head-of-line blocking ADR-032 refused. It restores the **ADR-004** promise that moving to a durable backend needs no code change, which option (b), best-effort past the mailbox boundary, would have quietly broken
- **ADR-033** was amended on 2026-09-04: a failed message has somewhere to go on both transports. The original **Consequences** listed the in-memory dispatcher as *Unchanged* because it "has no redelivery to preserve" — true, and not a reason to lose the message silently. The amendment adds the `DeadLetterQueue` port, its in-memory and Redis implementations, and the cross-transport cases that were previously inexpressible; it deliberately adds no supervision, and amends **ADR-021**'s trade-off that a dead-letter stream is operational surface in-memory messaging did not have

---

## References and Further Reading

### External Resources

- [Architecture Decision Records (ADRs)](https://adr.github.io/)
- [Java 21 Documentation](https://docs.oracle.com/en/java/javase/21/)
- [Project Loom Documentation](https://openjdk.org/projects/loom/)
- [Maven Multi-Module Best Practices](https://maven.apache.org/guides/mini/guide-multiple-modules.html)
- [Spring Boot Auto-configuration](https://docs.spring.io/spring-boot/reference/using/auto-configuration.html)

### Related Projects

- [JADE Framework](https://jade.tilab.com/) - Original inspiration
- [Spring Framework](https://spring.io/) - Architecture patterns
- [Akka](https://akka.io/) - Actor model implementation
- [Vert.x](https://vertx.io/) - Reactive applications

### Internal Documentation

- [Architecture Overview](../architecture.md)
- [Agent Development Guide](../agent-development.md)
- [Configuration Reference](../configuration.md)

---

> **Note**: ADRs are living documents. As Agenor evolves, these decisions may be revisited and updated.
> Always check the status and date of each ADR to ensure you're working with current architectural decisions.
