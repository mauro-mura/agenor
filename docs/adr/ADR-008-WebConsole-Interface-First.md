# ADR-008: WebConsole Interface-First Design

**Status**: Accepted
**Date**: 2025-11-26  
**Authors**: Project Team

### Context

The WebConsole in `jentic-tools` was tightly coupled to Jetty and directly dependent on `AgenorRuntime`. We wanted to:

1. Allow alternative implementations (Spring Boot, Netty)
2. Keep it simple to use
3. Avoid over-engineering with too many abstractions

### Decision

**Minimal interface-first** approach:

### Interfaces in jentic-core

```
dev.agenor.core.console/
├── WebConsole.java           # start/stop/isRunning/getPort
└── ConsoleEventListener.java # Events for WebSocket
```

We **do not** create `AgentInfoProvider` or `MetricsProvider` because:
- `AgenorRuntime` already has all necessary methods
- It would add complexity without immediate benefit
- Can be added later if remote/multi-runtime consoles are needed

### Implementation in jentic-tools

```
dev.agenor.tools.console/
├── JettyWebConsole.java      # Main implementation
├── RestAPIHandler.java       # REST API
├── WebSocketHandler.java    # WebSocket
├── StaticResourceHandler.java
└── WebConsoleServer.java     # @Deprecated, backward-compatible
```

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
- Zero breaking changes (WebConsoleServer deprecated but still works)

### Negative
- Console coupled to AgenorRuntime (acceptable for now)
