# ADR-006: Annotation-Based Agent Configuration

**Status**: Accepted  
**Date**: 2025-09-16  
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
jentic:
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
