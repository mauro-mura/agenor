# ADR-008: WebConsole Interface-First Design

**Status**: Accepted
**Date**: 2025-11-26  
**Authors**: Project Team

### Context

The WebConsole in `agenor-tools` was tightly coupled to Jetty and directly dependent on `AgenorRuntime`. We wanted to:

1. Allow alternative implementations (Spring Boot, Netty)
2. Keep it simple to use
3. Avoid over-engineering with too many abstractions

### Decision

**Minimal interface-first** approach:

### Interfaces in agenor-core

```
dev.agenor.core.console/
├── WebConsole.java           # start/stop/isRunning/getPort
└── ConsoleEventListener.java # Events for WebSocket
```

We **do not** create `AgentInfoProvider` or `MetricsProvider` because:
- `AgenorRuntime` already has all necessary methods
- It would add complexity without immediate benefit
- Can be added later if remote/multi-runtime consoles are needed

### Implementation in agenor-tools

```
dev.agenor.tools.console/
├── JettyWebConsole.java      # Main implementation
├── RestAPIHandler.java       # REST API
├── WebSocketHandler.java    # WebSocket
└── StaticResourceHandler.java
```

`WebConsoleServer.java` sat in that list as `@Deprecated, backward-compatible` from 0.4.0
until 0.33.0 removed it — see the amendment below.

### Usage

```java
WebConsole console = JettyWebConsole.builder()
    .port(8080)
    .runtime(runtime)  // Direct, simple
    .build();
console.start().join();
```

## Consequences

### Positive
- Simplicity: few interfaces, easy to understand
- `WebConsole` allows alternative implementations
- Zero breaking changes at the time (`WebConsoleServer` deprecated but still working)

### Negative
- Console coupled to AgenorRuntime (acceptable for now)

---

## Amendment, 2026-09-09 (0.33.0) — the alias is gone, and what kept it alive

`WebConsoleServer` is removed. It had been `@Deprecated(since = "0.4.0")` for the better part
of the project's public history, with zero uses anywhere outside its own test.

**Why it survived that long is the part worth recording.** The deprecation carried
`since` and nothing else: no `forRemoval`, no target release. `tools/api-census.sh --check`
audits the removal schedule by reading `forRemoval = true` sites, so a deprecation that never
promised a removal could not be overdue, could not be undated, and could not be flagged. The
tool built to stop exactly this — a deprecation outliving its own date, as
`dev.agenor.core.AgentDirectory` did by four releases — was blind to the weaker case of a
deprecation that never had a date to outlive.

`--check` now fails on a `@Deprecated` carrying no `forRemoval`, as its own failure shape. It
is the least visible of the three and the one that announces nothing enforceable: a permanent,
silent "don't use this", indistinguishable by tooling from a type nobody got around to
finishing.

The deprecated two-argument `RestAPIHandler` constructor, undated on the same footing and with
no callers, went with it. `RestAPIHandler` itself is unchanged and is what `JettyWebConsole`
builds.
