# ADR-003: Maven Multi-Module Structure

**Status**: Accepted  
**Date**: 2025-09-16  
**Last Modified**: 2026-03-26 
**Authors**: Project Team  

### Context

We need a build structure that supports modular development, clear dependency management, and progressive feature adoption.

### Decision

We will use a **Maven Multi-Module structure** with clear module boundaries and dependency rules.

### Module Structure

```
jentic/
├── jentic-core/          # Core interfaces only
├── jentic-runtime/       # Basic implementations  
├── jentic-adapters/      # Enterprise implementations
├── jentic-spring-boot-starter/    # Spring Boot 3.5.x auto-configuration
├── jentic-examples/      # Usage examples
└── jentic-tools/         # CLI and utilities
```

### Dependency Rules

1. **jentic-core**: No dependencies (except Jackson for serialization)
2. **jentic-runtime**: Depends only on jentic-core + minimal Spring
3. **jentic-adapters**: Depends on jentic-core + external systems
4. **jentic-examples**: Can depend on any module
5. **jentic-tools**: Depends on jentic-runtime
6. **jentic-spring-boot-starter**: Depends on jentic-runtime (mandatory) and
   jentic-adapters (optional). All Spring Boot dependencies declared optional=true —
   no Spring Boot on the transitive classpath of non-Spring consumers.
   See ADR-016.

### Benefits

- **Clear Boundaries**: Each module has specific responsibility
- **Dependency Control**: Prevents circular dependencies
- **Selective Usage**: Users can include only needed modules
- **Development Efficiency**: Team can work on modules independently

### Build Configuration

```xml
<!-- Parent POM manages versions -->
<properties>
    <maven.compiler.source>21</maven.compiler.source>
    <maven.compiler.target>21</maven.compiler.target>
    <spring.boot.version>3.2.2</spring.boot.version>
</properties>

<!-- Modules inherit consistent configuration -->
<modules>
    <module>jentic-core</module>
    <module>jentic-runtime</module>
    <module>jentic-adapters</module>
    <module>jentic-spring-boot-starter</module>
    <module>jentic-examples</module>
    <module>jentic-tools</module>
</modules>
```

### Consequences

- **Positive**: Clear module boundaries and responsibilities
- **Positive**: Easier testing and CI/CD
- **Positive**: Users can choose their complexity level
- **Negative**: Initial setup complexity
- **Negative**: More files to maintain
