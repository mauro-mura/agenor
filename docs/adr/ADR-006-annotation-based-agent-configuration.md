# ADR-006: Annotation-Based Agent Configuration

**Status**: Accepted  
**Date**: 2025-09-16  
**Last Modified**: 2026-11-17 (see Amendment below)  
**Authors**: Project Team  

### Context

We want agent configuration to be declarative, close to the code, and easy to understand, while maintaining the flexibility for external configuration.

### Decision

We will use **Annotation-Based Configuration** as the primary mechanism for defining agent behavior, supplemented by external YAML configuration.

### Annotation Design

```java
@Agent(value = "weather-station", 
             type = "sensor", 
             capabilities = {"data-collection", "weather-monitoring"},
             autoStart = true)
public class WeatherStationAgent extends BaseAgent {
    
    @Behavior(type = CYCLIC, 
                    interval = "30s", 
                    initialDelay = "10s",
                    autoStart = true)
    public void collectWeatherData() {
        // Periodic data collection
    }
    
    @AgenorMessageHandler("weather.request")
    public void handleWeatherRequest(Message message) {
        // Handle incoming requests
    }
}
```

### Configuration Hierarchy

1. **Annotations**: Default behavior, close to code
2. **YAML Config**: Override annotations, environment-specific
3. **System Properties**: Override YAML, deployment-specific
4. **Environment Variables**: Override system properties, container-friendly

### Benefits

- **Co-location**: Configuration lives with the code
- **Type Safety**: Compile-time validation of configuration
- **IDE Support**: Auto-completion and refactoring support
- **Self-Documenting**: Configuration is visible in the code
- **Flexibility**: Can be overridden externally

### External Override Example

```yaml
agenor:
  agents:
    weather-station:
      behaviors:
        collectWeatherData:
          interval: "60s"  # Override annotation default
      message-handlers:
        weather.request:
          auto-subscribe: false
```

### Discovery Mechanism

```java
// Runtime scans for @Agent annotations
@Component
public class AnnotationAgentScanner {
    
    public Set<Class<?>> scanForAgents(String basePackage) {
        // Use reflection to find annotated classes
        return reflections.getTypesAnnotatedWith(AgenorAgent.class);
    }
}
```

### Consequences

- **Positive**: Configuration close to code reduces mistakes
- **Positive**: IDE support improves developer experience
- **Positive**: Type-safe configuration
- **Positive**: Self-documenting code
- **Negative**: Requires recompilation for configuration changes
- **Negative**: May encourage hard-coding of environment-specific values
- **Mitigation**: External configuration can override annotations

---

## Amendment — 2026-11-17: the external override was never built

**Status**: Accepted. Corrects *Configuration Hierarchy*, *External Override Example*,
*Discovery Mechanism* and the *Mitigation* line above; the annotation design stands.

### What is so

**No external configuration overrides an annotation.** `AgentAnnotationProcessor` reads
`@Behavior`'s `interval` and `initialDelay` literally, with no placeholder resolution, and neither
`agenor.yml` nor the Spring Boot starter has a per-agent section: the YAML under *External Override
Example* (`agenor.agents.<id>.behaviors…`, `message-handlers…`) maps to nothing and is silently
ignored. The *Mitigation* bullet therefore does not hold, and the two negatives above it are the
whole cost: a value in an annotation changes only by recompiling.

What external configuration does exist is narrower than the four-level hierarchy:

- `agenor.yml`, read by `DefaultConfigurationLoader`, configures the runtime, discovery
  (`agents.autoDiscovery`, `basePackage`, `scanPackages`), messaging, directory and scheduler —
  see `docs/configuration.md`. It says nothing about an individual agent.
- Inside that file, `${NAME:default}` takes an environment variable first and a system property
  second. That is substitution into the YAML, not a layer above it.
- Under Spring Boot, the `agenor.*` properties are the starter's own and follow Spring's
  precedence; they do not reach annotation values either.

The *Discovery Mechanism* sketch names types that do not exist (`AnnotationAgentScanner`, a
`reflections` library, `AgenorAgent.class`). Discovery lives in `agenor-runtime-scanning`
(ADR-027): `AgentScanner` walks the classpath itself, without a scanning library, behind the
`AgentDiscoveryEngine` SPI. `AgentFactory.scanForAgents` is deprecated for removal at 1.0.0.

### What is not decided here

Whether annotation values should become overridable. Nothing built on Agenor has asked for it: an
agent that needs an interval from the environment builds its behavior programmatically in
`onStart()` instead of declaring it. This amendment only stops the ADR promising a mechanism the
tree does not have.
