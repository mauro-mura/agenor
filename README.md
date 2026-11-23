
![agenor](docs/assets/agenor-wordmark.svg)

[![Maven Central](https://img.shields.io/maven-central/v/dev.agenor/agenor-bom.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/dev.agenor/agenor-bom)
[![Java](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Maven](https://img.shields.io/badge/Maven-3.9%2B-blue.svg)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)
[![Build Status](https://github.com/mauro-mura/agenor/actions/workflows/build.yml/badge.svg)](https://github.com/mauro-mura/agenor/actions/workflows/build.yml)

> **Multi-agent coordination for the JVM.** Autonomous agents that negotiate, delegate, and execute — with pluggable LLM reasoning when you need it.

Agenor is a Java 21 library and a public lab. Its purpose is to work out how agent systems,
classic and LLM-based, are built and how they actually behave: what an interaction protocol
guarantees, what happens when an agent dies halfway through a conversation, what a small local
model does with the prompt a large one handled. Every concept has a runnable example, and every
experiment ends up as an article with its code at a tagged release and numbers you can
reproduce.

**It is for Java developers who want to understand these systems by running them**, not for
teams choosing a framework for production — see [What Agenor is not](#-what-agenor-is-not).
Experiments run on local models through Ollama by default, and on remote ones through the
adapters when an experiment calls for it.

Agenor draws on the concepts JADE pioneered — performatives, interaction protocols, a directory — carried forward on modern Java.

## 📚 Learning path

**[agenor-examples](agenor-examples/README.md) is the path**: one concept per level, each with the
examples to run and the guide to read, from a first agent to LLM agents with tools, memory and
guardrails, MCP and A2A, and agents in separate processes over Redis and a shared directory.

The examples are not published to Maven Central, so they run from a clone: follow
[Development Setup](#development-setup) first — `mvn exec:java` needs the modules installed.
Then start where the path starts:

```bash
mvn exec:java -pl agenor-examples \
  -Dexec.mainClass="dev.agenor.examples.QuickStartExample"
```

## 📝 Articles

Every experiment ends up as an article: it starts from a question, answers it with code at a
tagged release of Agenor, and says what did not work as well as what did. They are published on
[Foojay](https://foojay.io/today/author/mauro-mura/) and announced on
[{bit Autonomi}](https://bitautonomi.substack.com), the maintainer's blog.

## 🔍 Applications built on it

Each is its own repository, built against a released version of Agenor from Maven Central, and
each has a role in the lab:

- **[agenor-example-job-dispatcher](https://github.com/mauro-mura/agenor-example-job-dispatcher)** —
  the reference example, updated with each release. A manager hands jobs to workers by Contract
  Net and keeps every job in PostgreSQL; when a worker dies, its job goes to another one.
  `demo/demo.sh` kills a worker with `kill -9` while it holds a job, and exits 0 only if all
  twelve jobs still end done. No LLM.
- **[agenor-example-helpdesk](https://github.com/mauro-mura/agenor-example-helpdesk)** — the test
  bench for experiments with LLM agents. An LLM agent sorts incoming tickets, a specialist drafts
  each reply from a knowledge base, and no draft reaches the customer until a person approves it.
  Spring Boot, in one process or on two nodes over Redis.
- **[agenor-sentinel](https://github.com/mauro-mura/agenor-sentinel)** — a distributed uptime
  monitor whose nodes come and go, run for 24 hours under injected faults, and kept at Agenor
  0.39.0 as the record of that run.
  [`FINDINGS.md`](https://github.com/mauro-mura/agenor-sentinel/blob/main/FINDINGS.md) lists what
  it found in Agenor.

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
            <version>0.42.0</version>
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
        <version>0.42.0</version>
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

**The whole API is `0.x`, and it will stay there.** Agenor is a learning lab, not a library
looking for production users, so there is no `1.0.0` on the roadmap:
[ADR-025 §D3](docs/adr/ADR-025-agenor-rebrand.md) records why the criteria that used to lead
there were retired. A minor release may break a public type. When one does, `CHANGELOG.md` marks
the entry `BREAKING` and says what to do instead on a line starting `Migration:`, and a
deprecation names the release that removes it.

The version number has never claimed field maturity. What a backend does and does not do is
stated on its own page —
[the Redis transport's own limits](docs/adapters/redis.md#trust-model-the-transport-authenticates-nothing),
for example — rather than implied by a version number.

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

Agenor is an 11-module Maven project. Every published module takes the version of the BOM shown
in [Installation](#installation); the [Architecture Guide](docs/architecture.md) explains how they
fit together.

| Module | What it holds |
|---|---|
| `agenor-bom` | Bill of Materials: one version for every Agenor module |
| `agenor-core` | Interfaces and records only — every contract, no implementation |
| `agenor-runtime` | In-memory implementations: `AgenorRuntime`, `BaseAgent`, messaging, directory, scheduler, dialogue protocols |
| `agenor-runtime-llm` | `LLMAgent`, LLM memory management, guardrails, reflection, and `ScriptedLLMProvider` for tests and offline runs |
| `agenor-runtime-ext` | `InMemoryStore`, filters, file persistence, composite behaviors, human-in-the-loop, knowledge |
| `agenor-runtime-scanning` | Classpath scanning for `scanPackage(...)`, kept apart so `agenor-runtime` stays GraalVM native-image friendly |
| `agenor-adapters` | LLM providers (OpenAI, Anthropic, Ollama), the Redis transport, MCP and A2A |
| `agenor-adapters-persistence` | JDBC-backed agent directory and human-in-the-loop approvals |
| `agenor-spring-boot-starter` | Spring Boot 4.0.x auto-configuration, with an Actuator health indicator ([guide](docs/spring-boot-starter.md)) |
| `agenor-tools` | Web console and CLI |
| `agenor-examples` | The learning path; not published |

`agenor-core` and `agenor-runtime` are enough for agents that coordinate without an LLM; the
other runtime modules were split out of `agenor-runtime` by ADR-027 so that each is added only
when it is used.

## 🤝 Contributing

Contributions are welcome, and so are questions about why something behaves as it does. See
[CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

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

- [Documentation Index](docs/index.md)
- [Getting Started](docs/getting-started.md)
- [Architecture Guide](docs/architecture.md)
- [Configuration Guide](docs/configuration.md)
- [Agent State Persistence Guide](docs/persistence.md)
- [Message Filtering Guide](docs/message-filtering.md)
- [Architecture Decision Records](docs/adr/README.md) — why the code is the way it is, including
  what was tried and dropped

## 🧩 Behaviors

**When work runs**
- [OneShotBehavior](docs/behaviors/OneShotBehavior.md) - Execute once, immediately or after a delay
- [CyclicBehavior](docs/behaviors/CyclicBehavior.md) - Repeat at a fixed interval
- [FSMBehavior](docs/behaviors/FSMBehavior.md) - Finite State Machine with guarded transitions

**Composing steps**
- [SequentialBehavior](docs/behaviors/SequentialBehavior.md) - Step-by-step execution
- [Human-In-The-Loop](docs/behaviors/hitl.md) - Human-In-The-Loop Checkpoint

See [docs/behaviors/README.md](docs/behaviors/README.md) for a full overview.

## 🚧 What Agenor is not

- **Not a production framework.** There is no supervision: no restart policy, no backoff, no
  escalation when an agent fails. The Redis transport
  [authenticates nothing](docs/adapters/redis.md#trust-model-the-transport-authenticates-nothing),
  so it belongs on a network you trust.
- **Not a stable API.** It stays `0.x`, and a release may break a public type when an experiment
  needs it — always marked in the changelog, with the way out. See [API stability](#-api-stability).
- **Not a wrapper around an LLM.** Coordination is built on messages, protocols and a directory;
  an LLM is one capability an agent can have, and a negotiation runs without one.
- **Not a team.** One maintainer, in spare time. Issues and discussions are read; answers can take
  a few days.

## 📄 License

Apache License 2.0 - see [LICENSE](LICENSE) file for details.

## 🙋 Support

- 🐛 Issues: [GitHub Issues](https://github.com/mauro-mura/agenor/issues)
- 💬 Questions and ideas: [GitHub Discussions](https://github.com/mauro-mura/agenor/discussions)
- 📝 Articles: [{bit Autonomi}](https://bitautonomi.substack.com), the maintainer's blog

## 🤖 Development

Developed with AI-assisted code generation and design using Claude AI.
