# Getting Started with Agenor

Get Agenor running in 5 minutes — no code to write yet.

## Prerequisites

- Java 21+
- Maven 3.9+

## 1. Clone and Build

```bash
git clone https://github.com/mauro-mura/agenor.git
cd agenor
mvn clean install -DskipTests
```

## 2. Run Your First Example

```bash
mvn exec:java -pl agenor-examples \
  -Dexec.mainClass="dev.agenor.examples.PingPongExample"
```

You should see two agents exchanging messages in the console. That's the runtime up and running.

## 3. Write One Yourself

Running someone else's example only shows you that it works. The next fifteen minutes are the
ones that matter: **[Your First Agent](first-agent.md)** — two agents, one asking and one
answering, six concepts, every line written by you.

```bash
mvn exec:java -pl agenor-examples   -Dexec.mainClass="dev.agenor.examples.QuickStartExample"
```

## 4. Try More Examples

```bash
# Cyclic behavior + topic pub/sub
mvn exec:java -pl agenor-examples \
  -Dexec.mainClass="dev.agenor.examples.WeatherStationExample"

# FSM + parallel validators (production-style orchestration)
mvn exec:java -pl agenor-examples \
  -Dexec.mainClass="dev.agenor.examples.ecommerce.ECommerceApplication"

# LLM multi-agent (requires OPENAI_API_KEY env var)
mvn exec:java -pl agenor-examples \
  -Dexec.mainClass="dev.agenor.examples.llm.LLMDirectMessagingExample"
```

The full learning path (Level 0 → Level 5) is in `agenor-examples/README.md`.

## 5. Add Agenor to Your Project

> Until Agenor is published to Maven Central, run `mvn install` locally first (step 1 above).

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>dev.agenor</groupId>
            <artifactId>agenor-bom</artifactId>
            <version>0.27.0-SNAPSHOT</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>dev.agenor</groupId>
        <artifactId>agenor-runtime</artifactId>
    </dependency>
</dependencies>
```

`agenor-runtime` alone gives you the core behavior types (`CYCLIC`, `ONE_SHOT`, `EVENT_DRIVEN`,
`WAKER`). Composite, advanced, and Human-in-the-Loop behaviors require an extra `agenor-runtime-ext`
dependency (ADR-027) — see the [Behaviors Overview](behaviors/README.md#extended-behaviors-agenor-runtime-ext-adr-027).

## Next Steps

| I want to… | Go to |
|------------|-------|
| Write my first agent | [Your First Agent](first-agent.md) |
| Go deeper on agent structure | [Agent Development Guide](agent-development.md) |
| Understand the module structure | [Architecture Guide](architecture.md) |
| Browse all behavior types | [Behaviors Overview](behaviors/README.md) |
| Integrate an LLM provider | [LLM Integration Guide](llm-integration.md) |
| Configure Agenor via YAML | [Configuration Guide](configuration.md) |
