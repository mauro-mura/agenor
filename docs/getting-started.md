# Getting Started with Jentic

Get Jentic running in 5 minutes — no code to write yet.

## Prerequisites

- Java 21+
- Maven 3.9+

## 1. Clone and Build

```bash
git clone https://github.com/mauro-mura/jentic.git
cd jentic
mvn clean install -DskipTests
```

## 2. Run Your First Example

```bash
mvn exec:java -pl jentic-examples \
  -Dexec.mainClass="dev.jentic.examples.PingPongExample"
```

You should see two agents exchanging messages in the console. That's the runtime up and running.

## 3. Try More Examples

```bash
# Cyclic behavior + topic pub/sub
mvn exec:java -pl jentic-examples \
  -Dexec.mainClass="dev.jentic.examples.WeatherStationExample"

# FSM + parallel validators (production-style orchestration)
mvn exec:java -pl jentic-examples \
  -Dexec.mainClass="dev.jentic.examples.ecommerce.ECommerceApplication"

# LLM multi-agent (requires OPENAI_API_KEY env var)
mvn exec:java -pl jentic-examples \
  -Dexec.mainClass="dev.jentic.examples.llm.LLMDirectMessagingExample"
```

The full learning path (Level 0 → Level 5) is in `jentic-examples/README.md`.

## 4. Add Jentic to Your Project

> Until Jentic is published to Maven Central, run `mvn install` locally first (step 1 above).

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>dev.jentic</groupId>
            <artifactId>jentic-bom</artifactId>
            <version>0.20.0-SNAPSHOT</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>dev.jentic</groupId>
        <artifactId>jentic-runtime</artifactId>
    </dependency>
</dependencies>
```

## Next Steps

| I want to… | Go to |
|------------|-------|
| Write my first agent | [Agent Development Guide](agent-development.md) |
| Understand the module structure | [Architecture Guide](architecture.md) |
| Browse all behavior types | [Behaviors Overview](behaviors/README.md) |
| Integrate an LLM provider | [LLM Integration Guide](llm-integration.md) |
| Configure Jentic via YAML | [Configuration Guide](configuration.md) |
