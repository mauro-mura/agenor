# ADR-016: Spring Boot Starter Module

**Status**: Proposed  
**Date**: 2026-03-26  
**Authors**: Jentic Team  
**Replaces**: —  
**Replaced By**: —  

## Context

Integrating Jentic into a Spring Boot 3.x application currently requires explicit `@Configuration`
boilerplate: manually constructing `JenticRuntime` via its builder, declaring lifecycle methods,
and optionally wiring an `LLMProvider`. This friction conflicts with the progressive-complexity
principle established in ADR-004 and discourages adoption in the Spring ecosystem.

Spring Boot's auto-configuration mechanism provides a standard, well-understood contract for
zero-configuration library integration. Adopting it for Jentic aligns with how every major
Spring ecosystem library (data, security, actuator, messaging) exposes itself to application
developers.

At the time of writing (March 2026), Spring Boot 4.0 is GA and Spring Boot 3.5.x is the latest
actively supported 3.x line. Spring Boot 4.0 introduces breaking changes (Spring Framework 7,
Jakarta EE 11 / Servlet 6.1 baseline, new modular jar layout) that would cut off all users
still on the 3.x line, which is a significant portion of the ecosystem. Spring Boot 3.5.x
remains under open-source support and is the widest-coverage stable target.

Forces in tension:
- **Convenience vs. purity**: adding Spring Boot autoconfigure must not leak Spring dependencies
  into projects that do not use Spring (ADR-003 optional dependencies rule).
- **Flexibility vs. defaults**: auto-configured beans must be overridable without complex
  exclusion mechanisms.
- **Monorepo alignment vs. release overhead**: hosting the starter in the same repo ensures
  version alignment with core but couples the release cycle.
- **Coverage vs. currency**: targeting Spring Boot 4.x maximises access to the latest features
  but excludes the still-supported 3.x user base; targeting 3.5.x provides broader coverage
  while remaining forward-compatible with 4.x (auto-configuration API is unchanged between
  the two major versions).

## Decision

Add a `jentic-spring-boot-starter` Maven module to the monorepo that provides automatic
wiring of `JenticRuntime` (and optionally an `LLMProvider`) into any Spring Boot **3.5.x**
application via Spring Boot's standard auto-configuration mechanism.

**Spring Boot 4.x is explicitly out of scope for v1.0.0.** A follow-up ADR (ADR-016b or a
revision of this document) will govern the upgrade once the 3.x user base has had sufficient
time to migrate, or when Spring Boot 3.x approaches end of open-source support.

Key constraints:
1. All Spring Boot dependencies declared as `optional=true` — no Spring Boot on the transitive
   classpath of non-Spring consumers.
2. Every auto-configured bean guarded by `@ConditionalOnMissingBean` — user-declared beans
   always win.
3. No new interfaces or abstractions introduced in `jentic-core` or `jentic-runtime`; the
   starter is a pure glue layer over the existing `JenticRuntime.Builder` API.
4. `jentic.llm.provider=none` (default) — no `LLMProvider` bean is created unless explicitly
   configured, avoiding a hard dependency on `jentic-adapters`.
5. The `AutoConfiguration.imports` mechanism (introduced in Spring Boot 2.7, mandatory in 3.x)
   is also the correct mechanism in Spring Boot 4.x — no migration work required on this front.

## Rationale

### Pros
- Eliminates boilerplate for the most common integration scenario.
- Follows the standard Spring Boot auto-configuration contract — familiar to any Spring developer.
- `optional=true` dependencies ensure zero impact on non-Spring consumers.
- `@ConditionalOnMissingBean` pattern gives full escape hatch without needing `spring.autoconfigure.exclude`.
- Actuator health integration provides operational visibility at no cost to the developer.
- Module lives in the monorepo: versions always aligned with core, no coordination overhead.
- Spring Boot 3.5.x covers the largest active user base while Spring Boot 4.x adoption ramps up.
- The `AutoConfiguration.imports` API is unchanged between 3.x and 4.x — upgrade cost is low
  when the time comes (mainly dependency coordinates and Jakarta EE namespace).

### Cons
- Spring Boot 4.x users must wait for a follow-up release or shim the starter themselves.
- Spring Boot 3.x / Jakarta EE 10 baseline excludes Spring Boot 2.x users (EOL).
- Adds a Spring Boot release dependency to the monorepo build (compile-scope in the starter only).

### Alternatives Considered
- **Target Spring Boot 4.x from the start**: rejected — cuts off the still-supported and
  widely-used 3.x user base prematurely; the auto-configuration API is compatible so there
  is no technical benefit to jumping now.
- **Support both 3.x and 4.x in the same artifact**: rejected — would require multi-release
  jars or complex shading to handle the `javax` → `jakarta` split; complexity not justified
  at v1.0.0.
- **Separate repository for the starter**: rejected — version drift between starter and core
  would require constant coordination (ADR-003 rationale).
- **Spring XML / legacy `spring.factories`**: rejected — deprecated since Spring Boot 2.7,
  removed in 3.x; `AutoConfiguration.imports` is the correct mechanism.
- **Auto-configure inside `jentic-runtime`**: rejected — would force Spring Boot onto all
  runtime consumers, violating the optional-dependency rule.

## Implementation

### Module location

```
jentic/
├── jentic-core/
├── jentic-runtime/
├── jentic-adapters/
├── jentic-spring-boot-starter/   ← new
├── jentic-examples/
└── jentic-tools/
```

### Auto-configuration registration

```
src/main/resources/META-INF/spring/
  org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

Content:
```
dev.jentic.autoconfigure.JenticAutoConfiguration
```

### Core beans

```java
@AutoConfiguration
@ConditionalOnClass(JenticRuntime.class)
@EnableConfigurationProperties(JenticProperties.class)
public class JenticAutoConfiguration {

    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnMissingBean
    public JenticRuntime jenticRuntime(JenticProperties props,
                                       ObjectProvider<LLMProvider> llmProvider) {
        JenticRuntime.Builder builder = JenticRuntime.builder()
            .configuration(props.toJenticConfiguration());
        llmProvider.ifAvailable(builder::service);
        return builder.build();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "jentic.llm", name = "provider",
                           havingValue = "openai")
    public LLMProvider openAiLlmProvider(JenticProperties props) {
        return LLMProviderFactory.openai()
            .apiKey(props.llm().apiKey())
            .modelName(props.llm().model())
            .build();
    }

    // anthropic and ollama variants follow the same pattern
}
```

### Minimal usage

`application.yml`:
```yaml
jentic:
  agents:
    base-package: com.example.agents
```

Application class — no additional configuration required:
```java
@SpringBootApplication
public class MyApp { public static void main(String[] args) { SpringApplication.run(MyApp.class, args); } }
```

### Actuator health

```json
{
  "status": "UP",
  "components": {
    "jentic": {
      "status": "UP",
      "details": {
        "runtime.name": "my-system",
        "agents.total": 3,
        "agents.running": 3
      }
    }
  }
}
```

## Consequences

### Positive
- Spring Boot 3.5.x developers can integrate Jentic with one dependency and one YAML property.
- Actuator health endpoint makes runtime status observable out of the box.
- `@ConditionalOnMissingBean` ensures full override capability without exclusion lists.
- Versioning always consistent with core — released together from the monorepo.
- Broad ecosystem coverage during the Spring Boot 3.x → 4.x transition period.

### Negative
- Spring Boot 4.x users are not supported until a follow-up release.
- Spring Boot 2.x users are excluded (EOL — acceptable).

### Neutral
- `jentic-examples` may add a new Spring Boot 3.5.x example sub-module demonstrating the starter.
- **Spring Boot 4.x upgrade path**: the auto-configuration mechanism is identical; the main
  migration effort will be updating Spring Boot BOM coordinates and switching from
  `javax.*` to `jakarta.*` imports if any are used directly. No Jentic core changes are
  expected. Target milestone: after Spring Boot 3.x open-source support ends or when 4.x
  adoption reaches a threshold agreed by the team.
- GraalVM native image hints are deferred to a post-1.0.0 milestone.

## Compliance

- CI build includes `jentic-spring-boot-starter` — compilation and test failures block merges.
- `@ConditionalOnMissingBean` usage reviewed in code review to ensure no bean bypasses the pattern.
- Dependency `optional=true` enforced via Maven Enforcer plugin rule (no Spring Boot on
  transitive classpath of `jentic-runtime` consumers).

## Notes

- Related ADRs: ADR-003 (Maven multi-module structure), ADR-004 (progressive complexity),
  ADR-007 (LLMProvider as core interface).
- Spring Boot autoconfigure reference: https://docs.spring.io/spring-boot/reference/using/auto-configuration.html
- Out of scope: Spring WebFlux / reactive support, `@ConditionalOnWebApplication` variants,
  Kafka / Consul adapter auto-wiring.
