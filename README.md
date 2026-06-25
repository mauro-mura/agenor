
![agenor](docs/assets/agenor-wordmark.svg)

[![Java](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Maven](https://img.shields.io/badge/Maven-3.9%2B-blue.svg)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)
[![Build Status](https://github.com/mauro-mura/agenor/actions/workflows/build.yml/badge.svg)](https://github.com/mauro-mura/agenor/actions/workflows/build.yml)

> **Java meets Agentic.** The enterprise-grade agent framework for the JVM.

Agenor is a contemporary multi-agent framework that modernizes the concepts pioneered by JADE, bringing them into the cloud-native era with Java 21, virtual threads, and a pragmatic approach to distributed systems.

## 🚀 Vision

Agenor reimagines multi-agent systems with modern Java practices:

- **Start Simple, Scale Smart**: Begin with in-memory implementations, evolve to enterprise solutions
- **Interface-First Design**: Clean abstractions that enable seamless technology transitions
- **Cloud-Native Ready**: Container-friendly, microservices-oriented, Kubernetes-native
- **Developer Experience**: Hot reload, clear APIs, minimal configuration
- **Virtual Threads**: Leverage Java 21's Project Loom for efficient concurrency

## ⚡ Quick Start

### Prerequisites

- Java 21+ (LTS recommended)
- Maven 3.9+

### Installation

#### Option 1: Clone and Build from Source

```bash
git clone https://github.com/mauro-mura/agenor.git
cd agenor
mvn clean install
```

#### Option 2: Add as Maven Dependency (Recommended)

Use the Agenor BOM (Bill of Materials) to manage module versions consistently:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>dev.agenor</groupId>
            <artifactId>agenor-bom</artifactId>
            <version>0.24.0-SNAPSHOT</version>
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

**Benefits of using the BOM:**
- ✅ No need to specify versions for each Agenor module
- ✅ Guaranteed compatibility between modules
- ✅ Simplified dependency management
- ✅ Easy upgrades - change one version, update all modules

#### Option 3: Without BOM

If you prefer explicit version management:

```xml
<dependencies>
    <dependency>
        <groupId>dev.agenor</groupId>
        <artifactId>agenor-runtime</artifactId>
        <version>0.24.0-SNAPSHOT</version>
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
        var runtime = AgenorRuntime.builder()
                .scanPackage("com.example.agents")
                .build();

        runtime.start();
    }
}
```

## 🏗️ Architecture

Agenor follows a modular, interface-first architecture:

For details, read the Architecture Guide at docs/architecture.md.

```
┌──────────────────┬─────────────────┬──────────────────────┐
│   agenor-core    │ agenor-runtime  │  agenor-adapters     │
│   (interfaces)   │ (basic impls)   │  (integrations)      │
├──────────────────┼─────────────────┼──────────────────────┤
│ Agent            │ BaseAgent       │ OpenAIProvider       │
│ MessageDispatcher│ LLMAgent        │ AnthropicProvider    │
│ AgentDirectory   │ InMemoryDispatch│ OllamaProvider       │
│ BehaviorScheduler│ InMemoryDir     │ A2A Adapter          │
│ LLMProvider      │ SimpleScheduler │ extensible           │
│ MemoryStore      │ InMemoryStore   │                      │
└──────────────────┴─────────────────┴──────────────────────┘
```

### Core Components

- **Agent**: Autonomous entity with behaviors and message handling
- **MessageDispatcher**: Asynchronous communication between agents (topic pub/sub + direct messaging)
- **AgentDirectory**: Service discovery, registration, and endpoint resolution
- **BehaviorScheduler**: Execution management for agent behaviors

### Evolution Path

```
All components are interfaces — swap any implementation without changing agent code.
```

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

## 📦 Modules

### agenor-core
Core interfaces and abstractions. No implementations, just contracts.

```xml
<dependency>
    <groupId>dev.agenor</groupId>
    <artifactId>agenor-core</artifactId>
    <version>0.24.0-SNAPSHOT</version>
</dependency>
```

### agenor-runtime
Basic implementations for getting started quickly.

```xml
<dependency>
    <groupId>dev.agenor</groupId>
    <artifactId>agenor-runtime</artifactId>
    <version>0.24.0-SNAPSHOT</version>
</dependency>
```

### agenor-adapters
Implementation for LLMs (OpenAI, Anthropic, Ollama) and Dialogue Protocol (A2A).

```xml
<dependency>
    <groupId>dev.agenor</groupId>
    <artifactId>agenor-adapters</artifactId>
    <version>0.24.0-SNAPSHOT</version>
</dependency>
```

### agenor-tools
Web Console and CLI tools.

```xml
<dependency>
    <groupId>dev.agenor</groupId>
    <artifactId>agenor-tools</artifactId>
    <version>0.24.0-SNAPSHOT</version>
</dependency>
```

### agenor-spring-boot-starter
 
Zero-configuration Spring Boot 4.0.x integration. Auto-wires `AgenorRuntime` and
optionally an `LLMProvider` from `application.yml`. Includes Actuator health indicator.
 
```xml
<dependency>
    <groupId>dev.agenor</groupId>
    <artifactId>agenor-spring-boot-starter</artifactId>
    <version>0.24.0-SNAPSHOT</version>
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

## 🚀 Features

### Current (MVP)
- [x] Agent lifecycle management
- [x] In-memory message passing
- [x] Local agent directory
- [x] Annotation-based configuration (agents, behaviors, handlers)
- [x] Behavior types: Cyclic, One-shot, Event-driven, Waker
- [x] Composite behaviors: Sequential, Parallel, FSM
- [x] Advanced behaviors: Conditional, Throttled
- [x] Message filtering (topic, header, content, predicate, composite)
- [x] Rate limiting (token bucket, sliding window)
- [x] File-based persistence utilities
- [x] YAML configuration support
- [x] Web management console
- [x] CLI tools
- [x] A2A (Agent-to-Agent) protocol support
- [x] LLM integration (OpenAI, Anthropic, Ollama)
- [x] LLM integration with memory management (context window strategies)
- [x] Dialogue protocol (Request, Query, Contract-Net)
- [x] Conditions system (AgentCondition, SystemCondition, TimeCondition)
- [x] Reflection Pattern
- [x] Human-in-the-Loop Checkpoint
- [x] Guardrail layer
- [x] MCP adapter
- [x] Spring Boot 4.0.x autoconfiguration (agenor-spring-boot-starter)


## 📚 Examples

The `agenor-examples` module contains a structured **learning path** from first steps to
production systems. See **[agenor-examples/README.md](agenor-examples/README.md)** for the
full guide.

Quick-start examples to run immediately:

```bash
# Level 0 — first agent exchange
mvn exec:java -pl agenor-examples \
  -Dexec.mainClass="dev.agenor.examples.PingPongExample"

# Level 1 — retry behavior with backoff strategies
mvn exec:java -pl agenor-examples \
  -Dexec.mainClass="dev.agenor.examples.behaviors.RetryExample"

# Level 5 — e-commerce FSM + parallel validators
mvn exec:java -pl agenor-examples \
  -Dexec.mainClass="dev.agenor.examples.ecommerce.ECommerceApplication"

# Level 4 — LLM multi-agent (requires OPENAI_API_KEY)
mvn exec:java -pl agenor-examples \
  -Dexec.mainClass="dev.agenor.examples.llm.LLMDirectMessagingExample"
```

## 🤝 Contributing

We welcome contributions! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

### Development Setup

```bash
# Clone and setup
git clone https://github.com/mauro-mura/agenor.git
cd agenor

# Build and test
mvn clean test

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

**Base Types**
- [OneShotBehavior](docs/behaviors/OneShotBehavior.md) - Execute once and stop
- [CyclicBehavior](docs/behaviors/CyclicBehavior.md) - Repeat at a fixed interval
- [EventDrivenBehavior](docs/behaviors/EventDrivenBehavior.md) - React to incoming messages
- [WakerBehavior](docs/behaviors/WakerBehavior.md) - Wake on condition or time
- [ScheduledBehavior](docs/behaviors/ScheduledBehavior.md) - Cron-based time scheduling

**Composite / Control Flow**
- [SequentialBehavior](docs/behaviors/SequentialBehavior.md) - Step-by-step execution
- [ParallelBehavior](docs/behaviors/ParallelBehavior.md) - Concurrent child behaviors
- [FSMBehavior](docs/behaviors/FSMBehavior.md) - Finite State Machine with guarded transitions

**Advanced Patterns**
- [ConditionalBehavior](docs/behaviors/ConditionalBehavior.md) - Gate execution on a condition
- [ThrottledBehavior](docs/behaviors/ThrottledBehavior.md) - Rate-limited execution
- [BatchBehavior](docs/behaviors/BatchBehavior.md) - Process items in bulk batches
- [CircuitBreakerBehavior](docs/behaviors/CircuitBreakerBehavior.md) - Fault tolerance circuit breaker
- [PipelineBehavior](docs/behaviors/PipelineBehavior.md) - Multi-stage data transformation
- [RetryBehavior](docs/behaviors/RetryBehavior.md) - Automatic retry with back-off
- [ReflectionBehavior](docs/behaviors/ReflectionBehavior.md) - LLM Generate → Critique → Revise loop
- [Human-In-The-Loop](docs/behaviors/hitl.md) - Human-In-The-Loop Checkpoint

See [docs/behaviors/README.md](docs/behaviors/README.md) for a full overview.

## 💡 Why Agenor?

**vs. JADE:**
- Modern Java (21 vs 8)
- Virtual threads vs traditional threading
- Cloud-native vs desktop-oriented
- Interface-first vs monolithic
- Reactive patterns vs blocking I/O

**vs. Building from Scratch:**
- Proven multi-agent patterns
- Gradual complexity adoption
- Clear migration paths
- Extensible by design — plug in any infrastructure

## 📄 License

Apache License 2.0 - see [LICENSE](LICENSE) file for details.

## 🙋 Support

- 🐛 Issues: [GitHub Issues](https://github.com/mauro-mura/agenor/issues)

## 🤖 Development

Developed with AI-assisted code generation and design using Claude AI.
