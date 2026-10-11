# ADR-003: Maven Multi-Module Structure

**Status**: Accepted  
**Date**: 2025-09-16  
**Last Modified**: 2026-10-11 (Amendment: no JPMS module descriptors yet)  
**Authors**: Project Team  

### Context

We need a build structure that supports modular development, clear dependency management, and progressive feature adoption.

### Decision

We will use a **Maven Multi-Module structure** with clear module boundaries and dependency rules.

### Module Structure

```
agenor/
├── agenor-bom/                          # Bill of Materials — version management
├── agenor-core/                         # Core interfaces only
├── agenor-runtime/                      # In-memory implementations (BaseAgent, messaging, directory, scheduler, dialogue)
├── agenor-runtime-llm/                  # LLM-aware runtime pieces, split from agenor-runtime (see ADR-027)
├── agenor-runtime-ext/                  # Extended runtime pieces, split from agenor-runtime (see ADR-027)
├── agenor-runtime-scanning/             # Classpath scanning / agent discovery, split from agenor-runtime (see ADR-027)
├── agenor-adapters/                     # LLM + A2A + MCP + OTel* + Redis*
├── agenor-adapters-persistence/         # JDBC directory + JDBC HITL (see ADR-022)
├── agenor-spring-boot-starter/          # Spring Boot 4.0.x auto-configuration
├── agenor-examples/                     # Runnable examples (6-level learning path)
└── agenor-tools/                        # Web console + CLI

* = optional dependency, opt-in at consumer POM level (see ADR-018)
```

### Dependency Rules

1. **agenor-core**: No dependencies (except Jackson for serialization and SLF4J API).
2. **agenor-runtime**: Depends only on `agenor-core`; no third-party framework deps.
3. **agenor-runtime-llm**: Depends only on `agenor-runtime`. Holds `LLMAgent`, LLM memory
   management, guardrails, and the default reflection strategy. See ADR-027.
4. **agenor-runtime-ext**: Depends only on `agenor-runtime`. Holds `InMemoryStore`, filters,
   rate limiting, conditions, file persistence, composite/advanced behaviors, and HITL. See
   ADR-027.
5. **agenor-runtime-scanning**: Depends only on `agenor-runtime` — explicitly **not** on
   `agenor-runtime-ext`, so scanning stays usable (and GraalVM native-image friendly) without
   pulling in the extended behavior stack. See ADR-027.
6. **agenor-adapters**: Depends on `agenor-core`; brings in LLM/A2A/MCP libraries.
   Heavy optional backends (OTel, Lettuce/Redis) are declared `optional=true` per ADR-018.
7. **agenor-adapters-persistence**: Depends on `agenor-core`; contains HikariCP, Flyway,
   and JDBC drivers. Placed in a dedicated sub-module per ADR-018 because the persistence
   stack is heavyweight and operationally distinct from the agentic toolkit. See ADR-022.
8. **agenor-examples**: Can depend on any module.
9. **agenor-tools**: Depends on `agenor-runtime`.
10. **agenor-spring-boot-starter**: Depends on `agenor-runtime` (mandatory) and
    `agenor-adapters` / `agenor-adapters-persistence` (both `optional=true`). All Spring Boot
    dependencies declared `optional=true` — no Spring Boot on the transitive classpath of
    non-Spring consumers. See ADR-016.

**Rule for placing new adapter dependencies**: see **ADR-018 — Optional Adapter Dependencies
Pattern**. In brief: lightweight libs go in `agenor-adapters` compile scope; optional/heavy
libs go in `agenor-adapters` as `optional=true`; heavyweight infrastructure with mutually
exclusive alternatives gets its own `agenor-adapters-<concern>` sub-module.

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
    <module>agenor-bom</module>
    <module>agenor-core</module>
    <module>agenor-runtime</module>
    <module>agenor-runtime-llm</module>        <!-- see ADR-027 -->
    <module>agenor-runtime-ext</module>        <!-- see ADR-027 -->
    <module>agenor-runtime-scanning</module>   <!-- see ADR-027 -->
    <module>agenor-adapters</module>
    <module>agenor-adapters-persistence</module>  <!-- see ADR-022 -->
    <module>agenor-spring-boot-starter</module>
    <module>agenor-examples</module>
    <module>agenor-tools</module>
</modules>
```

### Consequences

- **Positive**: Clear module boundaries and responsibilities
- **Positive**: Easier testing and CI/CD
- **Positive**: Users can choose their complexity level (ADR-004)
- **Positive**: ADR-018 provides a stable rule for placing new adapters — no per-adapter debate
- **Negative**: Initial setup complexity
- **Negative**: More files to maintain as the module count grows

---

## Amendment — 2026-10-11: no JPMS module descriptors yet

**Status of this amendment**: Accepted. A decision *not* to change the build, recorded with its
measurements so that it is reopened by new evidence rather than re-argued.

### Context

No module ships a `module-info.java`; all nine published modules declare an
`Automatic-Module-Name` (`dev.agenor.core`, `dev.agenor.runtime`, …), since 0.7.0. The nine
modules hold 76 packages, none split across two JARs. JPMS offers natively what
`tools/api-census.sh` approximates: a package left out of `exports` is invisible to other
modules, and `javac -Xlint:exports` reports every public signature that leaks a type from a
package that is not exported — the check the census leaves to a human in its 91
*documented, unnamed — check getters* rows. Partitioning packages by who imports them suggested
27 packages in the six eligible modules could stop being public surface.

### What was measured

A pilot compiled the real sources of the six modules whose dependencies are all named modules
against draft descriptors, outside the source tree, promoting every package `-Xlint:exports`
flagged until none was left. A minimal user module with an `@Agent`, an `@Behavior` and an
`@AgenorMessageHandler` was then run against the result.

1. **Most of the candidates must stay public.** javac forced **13** of the 27 back to an
   unqualified export: `AgenorRuntime` returns `AgentContext`, `DeadLetterQueue` and
   `LifecycleManager`; `BaseAgent` exposes `AgentMailbox`; every public exception extends
   `AgenorException`; public provider classes implement the `core.spi` interfaces.

   | Module | Packages | Exported | Only to Agenor modules | Hidden |
   |---|---|---|---|---|
   | `agenor-core` | 25 | 23 | 2 (`config`, `mcp`) | **0** |
   | `agenor-runtime` | 15 | 8 | 3 (`scheduler`, `support`, `telemetry`) | 4 (`annotation`, `config`, `deadletter`, `mailbox`) |
   | `agenor-runtime-llm` | 4 | 3 | 0 | 1 (`reflection`) |
   | `agenor-runtime-ext` | 7 | 6 | 0 | 1 (`persistence`) |
   | `agenor-runtime-scanning` | 1 | 0 | 1 (`discovery`) | 0 |
   | `agenor-tools` | 9 | 7 | 0 | 2 (`cli.commands`, `cli.output`) |
   | **Total** | **61** | **47** | **6** | **8** |

   Of the 91 *check getters* types, 58 must stay public, 15 become invisible to user code, and 18
   live in modules that cannot carry a descriptor (point 3).
2. **Two hidden packages are taught as surface.** The user docs construct `PersistenceManager`,
   `FilePersistenceService` (`runtime.persistence`) and `DefaultConfigurationLoader`
   (`runtime.config`) with `new`. Without contradicting them, **six** packages could be hidden.
3. **Three modules cannot carry a descriptor, and the classpath ignores it.** `agenor-adapters`
   requires LangChain4j, the A2A SDK, the MCP SDK and jtokkit, and `agenor-adapters-persistence`
   requires Flyway; none declares a module name, so a `requires` on them names one derived from
   the JAR's file name, which is unstable and which the compiler warns against publishing. The
   Spring Boot starter, Maven `exec` and every example run on the classpath, where
   `module-info.class` has no effect.
4. **The `opens` cost already exists.** On the module path a user module needs one line:

   ```java
   opens com.example.agents to dev.agenor.runtime, dev.agenor.runtime.scanning,
                               com.fasterxml.jackson.databind;
   ```

   With it, manual registration and `scanPackage` both work and `ServiceLoader` resolves every
   optional module through `uses`/`provides`. Without it, `start()` succeeds and the agent does
   nothing: behaviours and handlers fail with `InaccessibleObjectException`, visible only as a log
   line. **The published JARs, as automatic modules, behave identically today.** No page under
   `docs/` mentions the module path.
5. **Build cost.** Qualified exports to downstream modules warn `module not found` when the
   reactor compiles upstream first, and Surefire moves 157 test files (22 using Mockito) onto the
   module path unless `useModulePath` is turned off.

### Decision

**No `module-info.java` is added, in any module, for now.** The decision rule was fixed before
the pilot: adopt if at least ten implementation packages could be hidden and a module-path user
needed at most one `opens` line. Only the second condition holds.

- **Rejected: descriptors in all nine modules** — point 3.
- **Rejected: descriptors in the six eligible modules** — six to eight packages hidden, from
  module-path applications only and none in `agenor-core`, against point 5.
- **Rejected: an `internal` package convention** — renaming packages breaks source on the
  classpath, where every user is; a descriptor would not.
- **Not decided here:** documenting the `opens` line. Point 4 is a gap users can hit today and
  belongs in the user documentation as its own change.

**Cost at 1.0.** ADR-025 freezes at `1.0.0` whatever is public then; for a module-path
application that is every package, since an automatic module exports all of them. Hiding any of
the packages above afterwards would be breaking for those applications. That cost is accepted,
and it matches ADR-025's 2026-09-13 amendment, which declined to enumerate a stable subset
before `1.0.0` — an `exports` list is such an enumeration.

### When to reopen

On new evidence, not on its own: a user or downstream project on the module path; LangChain4j,
A2A, MCP and Flyway all declaring stable module names; or a refactor that takes the leaking types
out of public signatures, so that re-running the measurement hides substantially more — in
particular any package of `agenor-core`.

### How to reproduce the numbers

- **91 check-getters rows**: `bash tools/api-census.sh > census.md`, then
  `` grep -c '^| `.*check getters' census.md ``.
- **76 packages, none split**: `grep -rh --include='*.java' -m1 '^package ' <module>/src/main/java | sort -u`
  over the nine modules; `uniq -d` on the names prints nothing.
- **Unstable module names**: `mvn dependency:list -pl <module>` marks a file-name-derived name
  `(auto)`.
- **The table**: `mvn package -DskipTests` for the six modules; a descriptor per module with the
  names above and the `uses`/`provides` pairs their `META-INF/services` files declare; then
  `javac -Xlint:exports --module-path <dependencies and upstream outputs>` over each module's
  `src/main/java`, promoting every package an `[exports]` warning names until none is left.
- **The `opens` line**: run an `@Agent` from a module of your own against the published JARs on
  the module path, with and without it.
- **Test counts**: `find <module>/src/test -name '*.java' | wc -l` and
  `grep -rl org.mockito <module>/src/test | wc -l` over the six modules.
