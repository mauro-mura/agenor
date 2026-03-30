
![jentic](docs/assets/jentic-wordmark.svg)

[![Java](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Maven](https://img.shields.io/badge/Maven-3.9%2B-blue.svg)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)
[![Build Status](https://github.com/mauro-mura/jentic/actions/workflows/build.yml/badge.svg)](https://github.com/mauro-mura/jentic/actions/workflows/build.yml)

> **Jentic** - Modern Multi-Agent Framework for Java

Jentic is a contemporary multi-agent framework that modernizes the concepts pioneered by JADE, bringing them into the cloud-native era with Java 21, virtual threads, and a pragmatic approach to distributed systems.

## 🚀 Vision

Jentic reimagines multi-agent systems with modern Java practices:

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
git clone https://github.com/mauro-mura/jentic.git
cd jentic
mvn clean install
```

#### Option 2: Add as Maven Dependency (Recommended)

Use the Jentic BOM (Bill of Materials) to manage module versions consistently:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>dev.jentic</groupId>
            <artifactId>jentic-bom</artifactId>
            <version>0.14.1</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
<!-- Core + Runtime for basic agent applications -->
<dependency>
    <groupId>dev.jentic</groupId>
    <artifactId>jentic-runtime</artifactId>
</dependency>

<!-- Optional: Add adapters for external integrations -->
<dependency>
    <groupId>dev.jentic</groupId>
    <artifactId>jentic-adapters</artifactId>
</dependency>
</dependencies>
```

**Benefits of using the BOM:**
- ✅ No need to specify versions for each Jentic module
- ✅ Guaranteed compatibility between modules
- ✅ Simplified dependency management
- ✅ Easy upgrades - change one version, update all modules

#### Option 3: Without BOM

If you prefer explicit version management:

```xml
<dependencies>
    <dependency>
        <groupId>dev.jentic</groupId>
        <artifactId>jentic-runtime</artifactId>
        <version>0.14.1</version>
    </dependency>
</dependencies>
```

### Your First Agent

```java
@JenticAgent("hello-agent")
public class HelloAgent extends BaseAgent {

    @JenticBehavior(type = CYCLIC, interval = "5s")
    public void sayHello() {
        messageService.send(Message.builder()
                .topic("greetings")
                .content("Hello from " + getAgentId())
                .build());
    }

    @JenticMessageHandler("greetings")
    public void handleGreeting(Message message) {
        log.info("Received: {}", message.getContent());
    }
}
```

### Running

```java
public class HelloWorld {
    public static void main(String[] args) {
        var runtime = JenticRuntime.builder()
                .scanPackage("com.example.agents")
                .build();

        runtime.start();
    }
}
```

## 🏗️ Architecture

Jentic follows a modular, interface-first architecture:

For details, read the Architecture Guide at docs/architecture.md.

```
┌──────────────────┬─────────────────┬──────────────────────┐
│   jentic-core    │ jentic-runtime  │  jentic-adapters     │
│   (interfaces)   │ (basic impls)   │  (integrations)      │
├──────────────────┼─────────────────┼──────────────────────┤
│ Agent            │ BaseAgent       │ OpenAIProvider       │
│ MessageService   │ LLMAgent        │ AnthropicProvider    │
│ AgentDirectory   │ InMemoryMessage │ OllamaProvider       │
│ BehaviorScheduler│ LocalDirectory  │ A2A Adapter          │
│ LLMProvider      │ SimpleScheduler │ extensible           │
│ MemoryStore      │ InMemoryStore   │                      │
└──────────────────┴─────────────────┴──────────────────────┘
```

### Core Components

- **Agent**: Autonomous entity with behaviors and message handling
- **MessageService**: Asynchronous communication between agents
- **AgentDirectory**: Service discovery and registration
- **BehaviorScheduler**: Execution management for agent behaviors

### Evolution Path

```
All components are interfaces — swap any implementation without changing agent code.
```

## 🔧 Configuration

Simple YAML configuration:

```yaml
jentic:
  runtime:
    name: my-agent-system

  agents:
    autoDiscovery: true
    basePackage: "com.example.agents"

  messaging:
    provider: in-memory 

  directory:
    provider: local      
```

## 📦 Modules

### jentic-core
Core interfaces and abstractions. No implementations, just contracts.

```xml
<dependency>
    <groupId>dev.jentic</groupId>
    <artifactId>jentic-core</artifactId>
    <version>0.14.1</version>
</dependency>
```

### jentic-runtime
Basic implementations for getting started quickly.

```xml
<dependency>
    <groupId>dev.jentic</groupId>
    <artifactId>jentic-runtime</artifactId>
    <version>0.14.1</version>
</dependency>
```

### jentic-adapters
Implementation for LLMs (OpenAI, Anthropic, Ollama) and Dialogue Protocol (A2A).

```xml
<dependency>
    <groupId>dev.jentic</groupId>
    <artifactId>jentic-adapters</artifactId>
    <version>0.14.1</version>
</dependency>
```

### jentic-tools
Web Console and CLI tools.

```xml
<dependency>
    <groupId>dev.jentic</groupId>
    <artifactId>jentic-tools</artifactId>
    <version>0.14.1</version>
</dependency>
```

### jentic-spring-boot-starter
 
Zero-configuration Spring Boot 3.5.x integration. Auto-wires `JenticRuntime` and
optionally an `LLMProvider` from `application.yml`. Includes Actuator health indicator.
 
```xml
<dependency>
    <groupId>dev.jentic</groupId>
    <artifactId>jentic-spring-boot-starter</artifactId>
    <version>0.14.1</version>
</dependency>
```
 
`application.yml`:
```yaml
jentic:
  agents:
    base-package: com.example.agents
  llm:
    provider: openai          # none | openai | anthropic | ollama
    api-key: ${OPENAI_API_KEY}
```
 
That's it — `JenticRuntime` is started and stopped automatically by the Spring lifecycle.

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
- [x] Spring Boot 3.5.x autoconfiguration (jentic-spring-boot-starter)


## 📚 Examples

The `jentic-examples` module contains a structured **learning path** from first steps to
production systems. See **[jentic-examples/README.md](jentic-examples/README.md)** for the
full guide.

Quick-start examples to run immediately:

```bash
# Level 0 — first agent exchange
mvn exec:java -pl jentic-examples \
  -Dexec.mainClass="dev.jentic.examples.PingPongExample"

# Level 1 — retry behavior with backoff strategies
mvn exec:java -pl jentic-examples \
  -Dexec.mainClass="dev.jentic.examples.behaviors.RetryExample"

# Level 5 — e-commerce FSM + parallel validators
mvn exec:java -pl jentic-examples \
  -Dexec.mainClass="dev.jentic.examples.ecommerce.ECommerceApplication"

# Level 4 — LLM multi-agent (requires OPENAI_API_KEY)
mvn exec:java -pl jentic-examples \
  -Dexec.mainClass="dev.jentic.examples.llm.LLMDirectMessagingExample"
```

## 🤝 Contributing

We welcome contributions! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

### Development Setup

```bash
# Clone and setup
git clone https://github.com/mauro-mura/jentic.git
cd jentic

# Build and test
mvn clean test

# Run examples
mvn exec:java -pl jentic-examples \
  -Dexec.mainClass="dev.jentic.examples.PingPongExample"
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

## 💡 Why Jentic?

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

- 🐛 Issues: [GitHub Issues](https://github.com/mauro-mura/jentic/issues)

## 🤖 Development

Developed with AI-assisted code generation and design using Claude AI.