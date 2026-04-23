# ADR-003: Maven Multi-Module Structure

**Status**: Accepted  
**Date**: 2025-09-16  
**Last Modified**: 2026-04-23  
**Authors**: Project Team  

### Context

We need a build structure that supports modular development, clear dependency management, and progressive feature adoption.

### Decision

We will use a **Maven Multi-Module structure** with clear module boundaries and dependency rules.

### Module Structure

```
jentic/
├── jentic-bom/                          # Bill of Materials — version management
├── jentic-core/                         # Core interfaces only
├── jentic-runtime/                      # In-memory implementations
├── jentic-adapters/                     # LLM + A2A + MCP + OTel* + Redis*
├── jentic-adapters-persistence/         # JDBC directory + JDBC HITL (planned — see ADR-018)
├── jentic-spring-boot-starter/          # Spring Boot 4.0.x auto-configuration
├── jentic-examples/                     # Runnable examples (6-level learning path)
└── jentic-tools/                        # Web console + CLI

* = optional dependency, opt-in at consumer POM level (see ADR-018)
```

### Dependency Rules

1. **jentic-core**: No dependencies (except Jackson for serialization and SLF4J API).
2. **jentic-runtime**: Depends only on `jentic-core`; no third-party framework deps.
3. **jentic-adapters**: Depends on `jentic-core`; brings in LLM/A2A/MCP libraries.
   Heavy optional backends (OTel, Lettuce/Redis) are declared `optional=true` per ADR-018.
4. **jentic-adapters-persistence**: Depends on `jentic-core`; contains HikariCP, Flyway,
   and JDBC drivers. Placed in a dedicated sub-module per ADR-018 because the persistence
   stack is heavyweight and operationally distinct from the agentic toolkit.
5. **jentic-examples**: Can depend on any module.
6. **jentic-tools**: Depends on `jentic-runtime`.
7. **jentic-spring-boot-starter**: Depends on `jentic-runtime` (mandatory) and
   `jentic-adapters` / `jentic-adapters-persistence` (both `optional=true`). All Spring Boot
   dependencies declared `optional=true` — no Spring Boot on the transitive classpath of
   non-Spring consumers. See ADR-016.

**Rule for placing new adapter dependencies**: see **ADR-018 — Optional Adapter Dependencies
Pattern**. In brief: lightweight libs go in `jentic-adapters` compile scope; optional/heavy
libs go in `jentic-adapters` as `optional=true`; heavyweight infrastructure with mutually
exclusive alternatives gets its own `jentic-adapters-<concern>` sub-module.

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
    <spring.boot.version>4.0.5</spring.boot.version>
</properties>

<!-- Modules inherit consistent configuration -->
<modules>
    <module>jentic-bom</module>
    <module>jentic-core</module>
    <module>jentic-runtime</module>
    <module>jentic-adapters</module>
    <module>jentic-adapters-persistence</module>  <!-- planned — see ADR-018 -->
    <module>jentic-spring-boot-starter</module>
    <module>jentic-examples</module>
    <module>jentic-tools</module>
</modules>
```

### Consequences

- **Positive**: Clear module boundaries and responsibilities
- **Positive**: Easier testing and CI/CD
- **Positive**: Users can choose their complexity level (ADR-004)
- **Positive**: ADR-018 provides a stable rule for placing new adapters — no per-adapter debate
- **Negative**: Initial setup complexity
- **Negative**: More files to maintain as the module count grows
