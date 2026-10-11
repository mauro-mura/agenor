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

**Status of this amendment**: Accepted. It records a decision *not* to change the build, with
the measurements behind it, so that the question is reopened by new evidence rather than
re-argued from scratch.

### Context

No module ships a `module-info.java`. Each of the nine published modules declares an
`Automatic-Module-Name` (`dev.agenor.core`, `dev.agenor.runtime`, …), in place since 0.7.0, and
no ADR had ever weighed the alternative. ADR-027's package-rename note mentions the absence and
avoids split packages by hand.

The question arrived from outside the code. A Java reader can fairly object that Agenor polices
its own public surface with project tooling — `tools/api-census.sh` and its verdicts — where the
platform offers a native mechanism: a package left out of `exports` is invisible to other
modules, and `javac -Xlint:exports` mechanically reports every public signature that leaks a
type from a package that is not exported. That objection deserved a measured answer.

The census, at the commit this amendment was written against, counts **264** public top-level
types: 101 named by user code, 94 documented but never named (91 of them flagged
*check getters*: a getter path may reach them without the user writing the name), 68 plumbing,
1 entry point. The nine modules hold **76** packages, none split across two JARs. A first
partition by who imports each package — user code and examples, only other Agenor modules, or
nobody outside its own module — suggested **27** packages in the six modules discussed below
could stop being public surface.

### What was measured

A pilot compiled the real sources of the six modules whose dependencies are all named modules —
`agenor-core`, `agenor-runtime`, `agenor-runtime-llm`, `agenor-runtime-ext`,
`agenor-runtime-scanning`, `agenor-tools` — against draft descriptors, outside the source tree.
The drafts started from that partition: user-named packages exported, packages used only by
other Agenor modules exported `to` them, the rest hidden. Every package `-Xlint:exports` flagged
was promoted to an unqualified export, and the six were recompiled until no `[exports]` warning
was left. A minimal user module (an `@Agent` with an `@Behavior` and an `@AgenorMessageHandler`,
reading a record payload delivered as a `Map`) was then run against the result, registered both
by hand and by `scanPackage`.

**1. Most of the candidate surface cannot be hidden.** Of the 27 candidate packages, javac forced
**13** back to an unqualified export, because a type in them appears in a public signature:
`AgenorRuntime` returns `AgentContext`, `DeadLetterQueue` and `LifecycleManager`; `BaseAgent`
exposes `AgentMailbox` and `MailboxConfig`; every public exception extends `AgenorException`;
public provider classes implement the `core.spi` interfaces and `ConsoleEventListener`. The
converged descriptors, over the six modules' 61 packages:

| Module | Packages | Exported | Exported only to Agenor modules | Hidden |
|---|---|---|---|---|
| `agenor-core` | 25 | 23 | 2 (`config`, `mcp`) | **0** |
| `agenor-runtime` | 15 | 8 | 3 (`scheduler`, `support`, `telemetry`) | 4 (`annotation`, `config`, `deadletter`, `mailbox`) |
| `agenor-runtime-llm` | 4 | 3 | 0 | 1 (`reflection`) |
| `agenor-runtime-ext` | 7 | 6 | 0 | 1 (`persistence`) |
| `agenor-runtime-scanning` | 1 | 0 | 1 (`discovery`, to the starter) | 0 |
| `agenor-tools` | 9 | 7 | 0 | 2 (`cli.commands`, `cli.output`) |
| **Total** | **61** | **47** | **6** (12 types) | **8** (18 types) |

`agenor-core`, the module whose contracts 1.0 would freeze, hides nothing.

**2. Two of the eight hidden packages are taught as surface.** `docs/persistence.md` and three
other pages construct `PersistenceManager` and `FilePersistenceService` with `new`;
`docs/configuration.md` does the same with `DefaultConfigurationLoader`. Hiding
`runtime.persistence` and `runtime.config` would break the documentation, so what a descriptor
could hide without contradicting it is **six** packages of implementation detail.

**3. The objection, answered with numbers.** Of the 91 *check getters* types, the compiler
settles **58** as "must stay public", makes **15** invisible to user code (6 hidden, 9 exported
only to Agenor modules), and cannot speak for the other **18**, which live in modules that cannot
carry a descriptor (below). So the native mechanism does answer a question the census leaves to a
human — and its answer is mostly "this is surface". What the census measures beyond that — whether
anything uses a type, whether a page documents it, when a deprecation is due, how many `BREAKING`
entries a release carries — has no JPMS counterpart.

**4. Three modules cannot carry a descriptor today.** `agenor-adapters` requires LangChain4j, the
A2A SDK, the MCP SDK and jtokkit; `agenor-adapters-persistence` requires Flyway. None of them
declares a module name, so a `requires` on them names a module derived from the JAR's file name —
a name that is not stable across releases, and which the compiler warns against publishing.
`agenor-spring-boot-starter` runs on the classpath, where Spring Boot runs. These three keep
their `Automatic-Module-Name`, and the LLM integration — the part of Agenor most readers come
for — stays outside any descriptor.

**5. On the classpath a descriptor encapsulates nothing.** Maven `exec`, Spring Boot and every
example in this repository run on the classpath, where `module-info.class` is ignored. The
encapsulation in point 1 reaches only applications that are themselves modules.

**6. The `opens` cost already exists, and is undocumented.** On the module path the user module
had to declare one line:

```java
opens com.example.agents to dev.agenor.runtime, dev.agenor.runtime.scanning,
                            com.fasterxml.jackson.databind;
```

With it, both registration paths worked and `ServiceLoader` resolved every optional module
through `uses`/`provides`. Without it, `start()` succeeded and the agent did nothing: behaviours
and handlers fail with `InaccessibleObjectException`, reported only as a log line — and nothing at
all when no SLF4J backend is present. **The same line is required today**, against the published
JARs as automatic modules, with identical failures. Adopting descriptors would neither add this
cost nor remove it. No page under `docs/` mentions the module path.

**7. Build cost.** Qualified exports to downstream modules emit `module not found` warnings when
the reactor compiles the upstream module first. Surefire 3.5.1 moves a module's tests onto the
module path as soon as a descriptor exists: 157 test files across the six modules, 22 of them
using Mockito, unless `useModulePath` is turned off.

### Decision

**No `module-info.java` is added, in any module, for now.** The nine modules keep their
`Automatic-Module-Name`.

- **Rejected: descriptors in all nine modules.** Point 4: three of them would `requires` modules
  with unstable names.
- **Rejected: descriptors in the six eligible modules.** The gain is six to eight implementation
  packages hidden from module-path applications only, none of them in `agenor-core`, against six
  descriptors to maintain, a test-runner change, and qualified-export warnings in every build.
  The decision rule was fixed before the pilot ran — adopt if at least ten implementation packages
  could be hidden and module-path users needed at most one `opens` line — and it is met on the
  second condition only.
- **Rejected: an `internal` package convention.** Renaming packages to `….internal` is a
  source-breaking change on the classpath, where every user is, which a descriptor is not.
- **Not decided here: whether the `opens` line is documented.** Point 6 is a gap a user can hit
  today, independently of this decision, and belongs in the user documentation as its own change.

**What this costs at 1.0.** Under ADR-025, `1.0.0` freezes whatever is public at that moment.
For a module-path application, today that is *every* package, because an automatic module
exports all of them. Hiding any of the packages above after `1.0.0` would therefore be a
breaking change for those applications. This amendment accepts that cost: at six to eight
packages it is small, and it falls on a set of users the project has no evidence of. It is
consistent with ADR-025's 2026-09-13 amendment, which declined to enumerate a stable subset
before `1.0.0` — an `exports` list is exactly such an enumeration.

### When to reopen

Any one of these is new evidence, and none is a reason on its own to adopt:

- a user, issue or downstream project building on the module path;
- LangChain4j, the A2A SDK, the MCP SDK and Flyway all publishing stable module names, which
  removes point 4;
- a refactor that takes the leaking types out of public signatures, so that a re-run of the
  measurement below hides substantially more than eight packages — and in particular any package
  of `agenor-core`.

### How to reproduce the numbers

- **Census figures**: `bash tools/api-census.sh > census.md`, then the *Summary* table, and
  `` grep -c '^| `.*check getters' census.md `` for the 91 (table rows only; the
  *Method* section uses the phrase too).
- **Packages and split packages**: for each of the nine modules,
  `grep -rh --include='*.java' -m1 '^package ' <module>/src/main/java | sort -u`; 76 lines in
  total, and `uniq -d` over the package names prints nothing.
- **Third-party module names**: `mvn dependency:list -pl <module>` prints each dependency's module
  name; a name derived from the file name rather than declared is flagged `(auto)`.
- **The partition**: build the six modules (`mvn package -DskipTests -pl <six modules> -am`),
  write a descriptor for each using the module names above and the `uses`/`provides` pairs their
  `META-INF/services` files already declare, then compile each module's `src/main/java` against
  it with `javac -Xlint:exports --module-path <its dependencies and the upstream outputs>`.
  Promote each package named in an `[exports]` warning and repeat until none is left. The
  converged descriptors differ from "export everything" only in the fourteen packages in the table.
- **The `opens` line**: run any `@Agent` from a module of your own against the published JARs on
  the module path, with and without it.
- **Test counts**: `find <module>/src/test -name '*.java' | wc -l` and
  `grep -rl org.mockito <module>/src/test | wc -l` over the six modules.
