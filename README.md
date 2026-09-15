
![agenor](docs/assets/agenor-wordmark.svg)

[![Maven Central](https://img.shields.io/maven-central/v/dev.agenor/agenor-bom.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/dev.agenor/agenor-bom)
[![Java](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Maven](https://img.shields.io/badge/Maven-3.9%2B-blue.svg)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)
[![Build Status](https://github.com/mauro-mura/agenor/actions/workflows/build.yml/badge.svg)](https://github.com/mauro-mura/agenor/actions/workflows/build.yml)

> **Multi-agent coordination for the JVM.** Autonomous agents that negotiate, delegate, and execute — with pluggable LLM reasoning when you need it.

**For Java developers who have a job to split across several independent workers, and need them to talk.** You write a class, annotate it, and start it; the framework handles discovery, addressing, delivery and lifecycle. It is a plain Java 21 library — no broker to install and no server to run before you see something work, and Redis or JDBC only when you outgrow a single process. LLM-backed reasoning (`agenor-runtime-llm`) is an optional module, not a prerequisite.

Agenor draws on the concepts JADE pioneered — performatives, interaction protocols, a directory — carried forward on modern Java.

## ⚡ Quick Start

### Prerequisites

- Java 21+
- Maven 3.9+

### Installation

Agenor is published on [Maven Central](https://central.sonatype.com/artifact/dev.agenor/agenor-bom).
Import the BOM (Bill of Materials) so that every Agenor module you add shares one version:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>dev.agenor</groupId>
            <artifactId>agenor-bom</artifactId>
            <version>0.34.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
<!-- Core + Runtime for basic agent applications -->
<dependency>
    <groupId>dev.agenor</groupId>
    <artifactId>agenor-runtime</artifactId>
</dependency>

<!-- Optional: Add adapters for external integrations -->
<dependency>
    <groupId>dev.agenor</groupId>
    <artifactId>agenor-adapters</artifactId>
</dependency>
</dependencies>
```

Agenor logs through SLF4J and **ships no logging backend** — choosing one is your
application's call. Until you add one, SLF4J prints a "no providers" warning and the
`log.info` in the agent below prints nothing. Logback, for example:

```xml
<dependency>
    <groupId>ch.qos.logback</groupId>
    <artifactId>logback-classic</artifactId>
    <version>1.5.32</version>
    <scope>runtime</scope>
</dependency>
```

Spring Boot applications already have one and need nothing extra.

#### Without the BOM

Every module takes the same version:

```xml
<dependencies>
    <dependency>
        <groupId>dev.agenor</groupId>
        <artifactId>agenor-runtime</artifactId>
        <version>0.34.0</version>
    </dependency>
</dependencies>
```

### Your First Agent

```java
@Agent("hello-agent")
public class HelloAgent extends BaseAgent {

    @Behavior(type = CYCLIC, interval = "5s")
    public void sayHello() {
        getMessageDispatcher().publish(Message.builder()
                .topic("greetings")
                .content("Hello from " + getAgentId())
                .build());
    }

    @AgenorMessageHandler("greetings")
    public void handleGreeting(Message message) {
        log.info("Received: {}", message.content());
    }
}
```

### Running

```java
public class HelloWorld {
    public static void main(String[] args) {
        var runtime = AgenorRuntime.builder().build();
        runtime.registerAgent(new HelloAgent());
        runtime.start().join();
    }
}
```

> `registerAgent(...)` works with just `agenor-core` + `agenor-runtime` — no extra module
> needed. For automatic classpath discovery instead of manual registration, use
> `.scanPackage(...)` on the builder, which requires adding `agenor-runtime-scanning` to
> the classpath (ADR-027).

## 🏗️ Architecture

Agenor follows a modular, interface-first architecture:

For details, read the Architecture Guide at docs/architecture.md.

| agenor-core (interfaces) | agenor-runtime (basic impls) | agenor-runtime-llm (LLM-aware) | agenor-runtime-ext (extensions) | agenor-runtime-scanning (classpath scanning) | agenor-adapters (integrations) |
|---|---|---|---|---|---|
| Agent | BaseAgent | LLMAgent | InMemoryStore | AgentScanner | OpenAIProvider |
| MessageDispatcher | InMemoryDispatcher | DefaultLLMMemoryManager | Filters | AgentFactory | AnthropicProvider |
| AgentDirectory | InMemoryDirectory | Guardrails | HITL | | OllamaProvider |
| BehaviorScheduler | SimpleScheduler | | | | A2A Adapter |
| LLMProvider | | | | | extensible |
| MemoryStore | | | | | |

`agenor-runtime-llm`, `agenor-runtime-ext`, and `agenor-runtime-scanning` were split out of
`agenor-runtime` per ADR-027 — a pure multi-agent-system consumer with no LLM, extended
behavior, or classpath-scanning needs can depend on `agenor-core` + `agenor-runtime` alone.
See docs/architecture.md for the full breakdown.

### Core Components

- **Agent**: Autonomous entity with behaviors and message handling
- **MessageDispatcher**: Asynchronous communication between agents (topic pub/sub + direct messaging)
- **AgentDirectory**: Service discovery, registration, and endpoint resolution
- **BehaviorScheduler**: Execution management for agent behaviors

### Evolution Path

All components are interfaces — swap any implementation without changing agent code. See the
[Architecture Guide](docs/architecture.md) for how a deployment grows from single-JVM to
distributed.

## 🔧 Configuration

Simple YAML configuration:

```yaml
agenor:
  runtime:
    name: my-agent-system

  agents:
    autoDiscovery: true
    basePackage: "com.example.agents"

  messaging:
    provider: inmemory 

  directory:
    provider: local      
```

## 🧭 API stability

**The whole API is `0.x`, and that is a statement rather than a formality.** A minor release may
break a public type. The criteria that would move this project to `1.0.0` are written down in
[ADR-025 §D3](docs/adr/ADR-025-agenor-rebrand.md): three consecutive releases with no `BREAKING`
entry in `CHANGELOG.md` across the whole public API, no pending ADR that would change it, and a
per-backend statement of what is production-ready. **The count is currently zero** — reset by
0.34.0's own Logback change — and nothing here is enumerated in advance: `1.0.0` freezes
whatever is public the day the count reaches three, it doesn't wait on a document written first.

When it arrives, `1.0.0` will mean **the public API does not break without a major release**. It
will not mean the distributed story has been proven in the field; that is a separate claim, made
per backend in the documentation, and
[the Redis transport's own limits](docs/adapters/redis.md#trust-model-the-transport-authenticates-nothing)
are stated there rather than implied by a version number.

Within that, some parts are more settled than others. The list below is informal guidance, not a
criterion — it is **not** a promise that everything unlisted is stable. It is the honest answer
to "which of this is most likely to move under me", each entry with the reason it is on the list:

| Expect this to move | Why |
|---|---|
| `agenor-runtime-llm`, `LLMProvider` and the LLM types in core | split out of `agenor-runtime` only in 0.25.0 (ADR-027, August 2026); the seam is new |
| Knowledge and embeddings (`KnowledgeStore`, `EmbeddingProvider`) | the one example that needed embeddings had bypassed the abstraction and reimplemented it; the shape is not settled by use yet |
| The commitment model (`Commitment`, `CommitmentTracker`) | the record is one-sided and two of four committing performatives never commit — both named as known limitations in ADR-009's amendments, both open |
| The MCP and A2A adapters | their surface tracks external SDKs that are themselves moving: A2A is at `0.3.2.Final` |
| `agenor-tools` (CLI, web console) | the console lost a public type as recently as 0.33.0 |
| The `agenor-core` test-jar (contract suites) | published for adapter authors; a test contract, not a runtime API |

The parts a first agent actually touches — `@Agent`, `@Behavior`, `@AgenorMessageHandler`,
`Agent`, `Message`, `AgenorRuntime`, `BaseAgent`, the `MessageDispatcher` and directory
interfaces, and the Request / Query / Contract-Net protocols — are the ones under the most
pressure to stay put, and where a break would be reported in the changelog with a replacement
named. That is the current commitment. It is weaker than semver, and saying so is the point.

## 📦 Modules

Agenor is an 11-module Maven project.

### agenor-bom
Bill of Materials — manages dependency versions across all Agenor modules. See
[Installation](#installation) above for the import snippet.

### agenor-core
Core interfaces and abstractions. No implementations, just contracts.

```xml
<dependency>
    <groupId>dev.agenor</groupId>
    <artifactId>agenor-core</artifactId>
    <version>0.34.0</version>
</dependency>
```

### agenor-runtime
Basic implementations for getting started quickly.

```xml
<dependency>
    <groupId>dev.agenor</groupId>
    <artifactId>agenor-runtime</artifactId>
    <version>0.34.0</version>
</dependency>
```

### agenor-runtime-llm
LLM-aware runtime pieces (ADR-027): `LLMAgent`, LLM memory management, guardrails, reflection
strategy. Depends on `agenor-runtime`.

```xml
<dependency>
    <groupId>dev.agenor</groupId>
    <artifactId>agenor-runtime-llm</artifactId>
    <version>0.34.0</version>
</dependency>
```

### agenor-runtime-ext
Extended runtime pieces (ADR-027): `InMemoryStore`, filters, file persistence, composite
behaviors, HITL, knowledge. Depends on `agenor-runtime`.

```xml
<dependency>
    <groupId>dev.agenor</groupId>
    <artifactId>agenor-runtime-ext</artifactId>
    <version>0.34.0</version>
</dependency>
```

### agenor-runtime-scanning
Classpath scanning and DI-based agent discovery (ADR-027), isolated so `agenor-runtime` stays
GraalVM native-image friendly. Required for `scanPackage(...)`. Depends on `agenor-runtime`.

```xml
<dependency>
    <groupId>dev.agenor</groupId>
    <artifactId>agenor-runtime-scanning</artifactId>
    <version>0.34.0</version>
</dependency>
```

### agenor-adapters
Implementation for LLMs (OpenAI, Anthropic, Ollama) and Dialogue Protocol (A2A).

```xml
<dependency>
    <groupId>dev.agenor</groupId>
    <artifactId>agenor-adapters</artifactId>
    <version>0.34.0</version>
</dependency>
```

### agenor-adapters-persistence
JDBC-backed agent directory and persistent Human-in-the-Loop checkpoints (ADR-022, ADR-023).

```xml
<dependency>
    <groupId>dev.agenor</groupId>
    <artifactId>agenor-adapters-persistence</artifactId>
    <version>0.34.0</version>
</dependency>
```

### agenor-tools
Web Console and CLI tools.

```xml
<dependency>
    <groupId>dev.agenor</groupId>
    <artifactId>agenor-tools</artifactId>
    <version>0.34.0</version>
</dependency>
```

### agenor-spring-boot-starter
 
Spring Boot 4.0.x auto-configuration: wires `AgenorRuntime` and optionally an `LLMProvider`
from `application.yml`. Includes an Actuator health indicator.
 
```xml
<dependency>
    <groupId>dev.agenor</groupId>
    <artifactId>agenor-spring-boot-starter</artifactId>
    <version>0.34.0</version>
</dependency>
```
 
`application.yml`:
```yaml
agenor:
  agents:
    base-package: com.example.agents
  llm:
    provider: openai          # none | openai | anthropic | ollama
    api-key: ${OPENAI_API_KEY}
```
 
That's it — `AgenorRuntime` is started and stopped automatically by the Spring lifecycle.

### agenor-examples
Runnable examples organized as a 6-level learning path (Level 0–5), from a first agent
exchange to a multi-node, multi-behavior application. Not published as a dependency — see the
[📚 Examples](#-examples) section below.

## 🚀 Features

### Core — multi-agent coordination (`agenor-core` + `agenor-runtime`)
- [x] Agent lifecycle management
- [x] In-memory message passing
- [x] Local agent directory
- [x] Annotation-based agent/behavior/handler declarations (`@Agent`, `@Behavior`, `@AgenorMessageHandler`)
- [x] Behavior types: Cyclic, One-shot, Event-driven, Waker
- [x] Dialogue protocol (Request, Query, Contract-Net) — negotiation with no LLM required
- [x] YAML configuration support

### Optional: LLM integration (`agenor-runtime-llm`)
- [x] LLM-aware agent (`LLMAgent`) — OpenAI, Anthropic, Ollama providers via `agenor-adapters`
- [x] Memory management with context window strategies (fixed, sliding, summarization)
- [x] Reflection pattern (Generate → Critique → Revise)
- [x] Guardrail layer (content policy, PII redaction, JSON schema, max tokens)

### Optional: extended runtime behaviors & utilities (`agenor-runtime-ext`)
- [x] Composite behaviors: Sequential, Parallel, FSM
- [x] Message filtering (topic, header, content, predicate, composite)
- [x] File-based persistence utilities
- [x] Human-in-the-Loop checkpoint

### Optional: classpath scanning & discovery (`agenor-runtime-scanning`)
- [x] Automatic agent discovery via classpath scanning (`scanPackage(...)`)
- [x] Isolated from `agenor-runtime` for GraalVM native-image friendliness

### Integrations & tooling (`agenor-adapters`, `agenor-tools`, `agenor-spring-boot-starter`)
- [x] A2A (Agent-to-Agent) protocol support
- [x] MCP adapter
- [x] Web management console
- [x] CLI tools
- [x] Spring Boot 4.0.x autoconfiguration


## 📚 Examples

The `agenor-examples` module contains a structured **learning path** from first steps to a
multi-node, multi-behavior application. See **[agenor-examples/README.md](agenor-examples/README.md)**
for the full guide.

The examples are not published to Maven Central, so they run from a clone: follow
[Development Setup](#development-setup) first — `mvn exec:java` needs the modules installed.

```bash
# Level 0 — first agent exchange
mvn exec:java -pl agenor-examples \
  -Dexec.mainClass="dev.agenor.examples.PingPongExample"

# Level 1 — the three behavior types in one agent
mvn exec:java -pl agenor-examples \
  -Dexec.mainClass="dev.agenor.examples.behaviors.CoreBehaviorsExample"

# Level 2 — Contract-Net negotiation (CFP → propose → accept)
mvn exec:java -pl agenor-examples \
  -Dexec.mainClass="dev.agenor.examples.dialogue.ContractNetExample"

# Level 5 — e-commerce FSM + parallel validators
mvn exec:java -pl agenor-examples \
  -Dexec.mainClass="dev.agenor.examples.ecommerce.ECommerceApplication"

# Level 4 — LLM multi-agent (free by default: local Ollama, no API key)
mvn exec:java -pl agenor-examples \
  -Dexec.mainClass="dev.agenor.examples.llm.LLMDirectMessagingExample"
```

## 🤝 Contributing

We welcome contributions! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

### Development Setup

Building from source is for working on Agenor itself — to use it, depend on it from Maven
Central as shown in [Installation](#installation).

Building needs a **JDK** 21+, not a JRE: the build runs `javac`. Several distributions ship
a headless JRE as the default `java`, and the build fails with
`release version 21 not supported` if `JAVA_HOME` points at one.

```bash
# Clone and setup
git clone https://github.com/mauro-mura/agenor.git
cd agenor

# Build, test and install into ~/.m2
mvn clean install

# Run examples
mvn exec:java -pl agenor-examples \
  -Dexec.mainClass="dev.agenor.examples.PingPongExample"
```

## 📖 Documentation

### Core Documentation
- [Documentation Index](docs/index.md)
- [Architecture Guide](docs/architecture.md)
- [Agent Development Guide](docs/agent-development.md)
- [Configuration Guide](docs/configuration.md)
- [Dialogue Protocol](docs/dialog-protocol.md)
- [LLM Integration Guide](docs/llm-integration.md)
- [Memory Guide](docs/memory.md)
- [Agent State Persistence Guide](docs/persistence.md)
- [Message Filtering Guide](docs/message-filtering.md)
- [Architecture Decision Records](docs/adr/README.md)

### Behaviors

**When work runs**
- [OneShotBehavior](docs/behaviors/OneShotBehavior.md) - Execute once, immediately or after a delay
- [CyclicBehavior](docs/behaviors/CyclicBehavior.md) - Repeat at a fixed interval
- [FSMBehavior](docs/behaviors/FSMBehavior.md) - Finite State Machine with guarded transitions

**Composing steps**
- [SequentialBehavior](docs/behaviors/SequentialBehavior.md) - Step-by-step execution
- [Human-In-The-Loop](docs/behaviors/hitl.md) - Human-In-The-Loop Checkpoint

See [docs/behaviors/README.md](docs/behaviors/README.md) for a full overview.

## 💡 Why Agenor?

**Built for coordination, not just generation:**
- Agents are independent, addressable, long-lived processes — each with its own
  lifecycle and behavior scheduler, registered in an `AgentDirectory` for discovery
- Asynchronous messaging (pub/sub topics + direct messaging) is a core primitive, not a
  side effect of an LLM call
- Dialogue protocols (Request, Query, Contract-Net) let agents negotiate and delegate
  tasks to each other — no LLM required to run a negotiation
- LLM-backed reasoning (`agenor-runtime-llm`) is one optional capability an agent can
  have, not the mechanism coordination is built on

**Reach for Agenor when:**
- You have several independent, long-lived workers that need to discover each other,
  exchange messages and negotiate — not one call orchestrating tools in sequence
- You want agents that can delegate and bid on work (Contract-Net) with or without an
  LLM in the loop
- You want to start on a single JVM, no broker or server to stand up first, and grow
  into a Redis- or JDBC-backed multi-node deployment only when you need to

**Look elsewhere when:**
- Your whole system is one LLM call chaining tools in sequence, with nothing that needs
  its own address, lifecycle or negotiation
- Durable, replayable long-running workflow execution is the core guarantee you need
- Fine-grained supervision and actor-level control is the primitive you're building on

## 📄 License

Apache License 2.0 - see [LICENSE](LICENSE) file for details.

## 🙋 Support

- 🐛 Issues: [GitHub Issues](https://github.com/mauro-mura/agenor/issues)
- 💬 Questions and ideas: [GitHub Discussions](https://github.com/mauro-mura/agenor/discussions)

## 🤖 Development

Developed with AI-assisted code generation and design using Claude AI.
