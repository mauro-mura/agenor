# ADR-025: Agenor Rebrand — Annotation Naming, Backward Compatibility, and Versioning

**Status**: Accepted  
**Date**: 2026-05-28  
**Last Modified**: 2026-09-13 (see second Amendment to D3 below)  
**Authors**: Project Team  
**References**: ADR-002 (Interface-First Architecture), ADR-003 (Maven Multi-Module Structure),
ADR-006 (Annotation-Based Agent Configuration), ADR-016 (Spring Boot Starter),
ADR-020 (Core API Refactor)

---

## Context

The project was developed under the name **Jentic** with Maven groupId `dev.jentic`. Before
the first public release on Maven Central, the decision was made to rebrand to **Agenor**
(`dev.agenor`). The rename touches every layer of the stack: package declarations, class names,
annotation names, Maven coordinates, Spring Boot auto-configuration keys, and documentation.

Three cross-cutting decisions must be made consistently and documented canonically before
the migration executes:

1. **Annotation naming** — which annotations drop their `Jentic`/`Agenor` prefix and which keep it.
2. **Backward compatibility** — whether to provide a migration bridge for existing consumers.
3. **Versioning** — how the rename maps to the public version sequence and what gates `1.0.0`.

No artifact under `dev.jentic` has been published to Maven Central. There are no external
consumers to break.

---

## Decision

### D1 — Annotation Naming

Annotations fall into two categories based on collision risk with common framework annotations.

**Structural annotations** — names are unambiguous in the Java ecosystem; the `Agenor` prefix
is dropped to reduce verbosity at the call site:

| Old name | New name |
|---|---|
| `@JenticAgent` | `@Agent` |
| `@JenticBehavior` | `@Behavior` |
| `@JenticPersist` | `@Persist` |
| `@JenticPersistenceConfig` | `@PersistenceConfig` |

**Collision-risk annotation** — `@MessageHandler` conflicts concretely with Spring's
`@MessageMapping` and related annotations in projects that import both frameworks. The
`Agenor` prefix is retained as a namespace guard:

| Old name | New name |
|---|---|
| `@JenticMessageHandler` | `@AgenorMessageHandler` |

**Unchanged annotations** — already unprefixed and conflict-free:

| Annotation | Reason |
|---|---|
| `@DialogueHandler` | Domain-specific name, no known framework conflict |
| `@WithGuardrails` | Domain-specific name, no known framework conflict |
| `@RequiresApproval` | Domain-specific name, no known framework conflict |

Java resolves annotation ambiguity by fully-qualified name. The risk of collision for
`@Agent` and `@Behavior` is accepted as low: these names do not appear in Spring,
Jakarta EE, MicroProfile, or other mainstream frameworks as top-level annotations in
the same compilation unit.

### D2 — Backward Compatibility

**Clean cut. No migration bridge.**

No shim jar, no deprecated re-exports, no `@Deprecated`-annotated aliases are provided.
Rationale:

- No artifact under `dev.jentic` has been published to Maven Central; there are no external
  consumers.
- A deprecation bridge would impose ongoing maintenance cost, pollute the public API surface,
  and signal instability to new adopters from day one.
- The rename is a single, well-scoped operation with a clear upgrade path documented in the
  `v0.24.0` release notes.

Consumer migration path (documented in the GitHub Release `v0.24.0`):
- Update Maven coordinates: `dev.jentic:jentic-* → dev.agenor:agenor-*`
- Update Spring Boot properties: `jentic.* → agenor.*`
- Update imports and annotation names per the D1 table above.

### D3 — Versioning

The rebrand **continues the existing version sequence**. The first public release under
`dev.agenor` is **`0.24.0`**, immediately following `0.23.0` (the last release under
`dev.jentic`).

Rationale: the version number carries a maturity signal. Restarting at `0.1.0` would
discard the signal accumulated across 23 prior releases. Jumping to `1.0.0` at rebrand
time would be premature — the distributed backend story is not yet validated in production.

**Promotion criteria for `1.0.0`** (observable, not time-based):

> **Scoped note, 0.32.0.** Criterion 2's list of core interfaces is one shorter: `Condition`
> was removed in 0.32.0, having been deprecated in 0.30.0. It was designed as a core contract
> and never became one. The criterion itself is unchanged.

1. At least one distributed backend (Redis transport, JDBC directory, or JDBC HITL gate)
   validated in a real deployment outside of the project's own test suite.
2. No breaking change to any core interface (`Agent`, `MessageDispatcher`, `AgentDirectory`,
   `BehaviorScheduler`, `LLMProvider`, `MemoryStore`, `Condition`) in the last two or three
   consecutive releases.
3. No ADR in the pipeline that requires a modification to a public interface or a change to
   Maven coordinates.

Until these criteria are met, the project signals active development via the `0.x` prefix.

#### Amendment — 2026-09-11: criterion 1 was unreachable, and it measured the wrong thing

The three criteria above are **superseded** by the four below. They are kept in place because
the reasoning that produced them is worth reading; they are no longer the gate.

**What was wrong with criterion 1.** It required a distributed backend "validated in a real
deployment outside of the project's own test suite". Three defects, in order of severity:

- **It is the only criterion outside the project's control.** Criteria 2 and 3 describe things
  the project does. This one requires a third party to choose to deploy. There is no action that
  advances it, which makes it a wish rather than a criterion.
- **It is circular.** Nobody puts a `0.x` library into production *because* it is `0.x`. So
  `1.0.0` required production use, and production use was discouraged by the absence of `1.0.0`.
  A lock whose key is inside it.
- **It measures the wrong property.** In semantic versioning `1.0.0` is not a maturity
  certificate; it is a commitment to a compatibility discipline — from here, the surface
  declared stable does not break without a major. That is a promise about the project's own
  behaviour. Tying it to field evidence made the version number carry a claim it does not make.

**What was wrong with criterion 2.** Two smaller things. It said "the last two or three
consecutive releases", which cannot be checked — a criterion that admits either answer settles
nothing. And its hardcoded list still names `Condition`, removed in 0.32.0, alongside
`LLMProvider` and `MemoryStore`, which the README's *API stability* section lists among the
types most likely to move. A gate cannot require stability from surface the project publicly
says is unsettled.

**The concern underneath both is kept, and moved to where it belongs.** The original worry was
claiming maturity the project did not have. That was right. The mistake was encoding the answer
as *wait for outside validation* instead of *commit to a discipline and state maturity
separately*. Backend maturity is now a per-backend statement in the documentation, where it can
be specific: `docs/adapters/redis.md` says plainly that the transport authenticates nothing
crossing between nodes, and that limit is Redis's to carry, not the version number's.

**Promotion criteria for `1.0.0`, superseding the three above:**

1. **The stable surface is enumerated in a published document**, type by type, with everything
   not on it explicitly outside the commitment. An unwritten stable surface cannot be promised.
2. **That enumerated surface has gone three consecutive releases with no breaking change**,
   counted from the release that first published the enumeration. Any break resets the count.
3. **No ADR in the pipeline requires a change to it**, or a change to the Maven coordinates.
   (Unchanged from the original criterion 3.)
4. **The documentation states, per backend, what is and is not production-ready**, so that
   `1.0.0` is read as a compatibility promise and not as a claim of field maturity.

All four are things the project can act on, and each can be checked by reading the tree.

**What this deliberately gives up.** `1.0.0` will no longer assert that the distributed story
has been proven anywhere. It asserts that the enumerated API will not break without a major.
Those are different claims, and conflating them is what made the original gate unreachable.
Criterion 4 exists so that the claim not being made is still written down somewhere.

**Open, and left to the release that adopts this**: at the cadence of 0.30.0 through 0.33.0 —
three releases in eight days — a criterion counted in releases is a weak soak. Whether
criterion 2 should also require a minimum elapsed span is a real question, and it sits against
this ADR's own "observable, not time-based" preference. Recorded rather than silently decided.

#### Amendment — 2026-09-13: criterion 1 replaced by a retrospective measure, not an enumeration

The four criteria above are **superseded** by the three below, six weeks after being written.
The four are kept in place because the reasoning is worth reading; they stopped being the gate
the day this was written, before a single release had been cut under them.

**What was wrong with criterion 1.** It required "the stable surface enumerated in a published
document" — writing down, while still `0.x`, which types would not be allowed to break. Two
defects:

- **It is not what SemVer asks for.** Under SemVer, `0.x` means anything may change in any
  release, with no promise attached, and `1.0.0` is the act of freezing whatever is public at
  that moment and committing to the major/minor/patch discipline from there on. Nothing in the
  spec asks a project to pre-negotiate a stable subset before cutting `1.0.0`; the stability is
  what `1.0.0` grants, not a precondition engineered in advance. Enumerating a subset ahead of
  time is a voluntary tiering discipline *on top of* SemVer — legitimate in the abstract, used by
  some projects, but not required, and this project had no mechanism to enforce it (no
  `japicmp`, no annotation anyone reads) and no request for it from anyone outside the project.
- **The timing was wrong on the project's own evidence.** The project already marks every
  breaking change with `BREAKING` in `CHANGELOG.md`. Read against the last five releases:

  | Release | `BREAKING` entries |
  |---|---|
  | 0.34.0 | 1 (Logback off the consumer classpath — a root-POM change, not a peripheral one) |
  | 0.33.0 | 3 |
  | 0.32.0 | 2 |
  | 0.31.0 | 0 |
  | 0.30.0 | 1 |

  Four of the last five broke something, the most recent — 0.34.0, the release that put Agenor
  on Maven Central — included. There is no evidence of a plateau. Enumerating a stable subset at
  this point would have been premature twice over: against what SemVer asks, and against what
  the project's own surface is still doing.

**What criterion 2 becomes.** It is folded into the new criterion 1, below: instead of measuring
a hand-picked subset, it measures the whole public surface, retrospectively, using a discipline
the project already practises.

**Promotion criteria for `1.0.0`, superseding the four above:**

1. **Three consecutive releases with no `BREAKING` entry in `CHANGELOG.md`**, across the whole
   public surface — not a curated subset. Any `BREAKING` entry resets the count to zero, counted
   from the release after the reset. **As of this amendment the count is zero**, reset by
   0.34.0's own Logback change.
2. **No ADR in the pipeline requires a change to the current public surface**, or to Maven
   coordinates. (Unchanged in substance from the old criterion 3.)
3. **The documentation states, per backend, what is and is not production-ready.** (Unchanged
   from the old criterion 4.)

When criterion 1 is met, `1.0.0` is cut. It freezes whatever is public at that moment; no
enumeration document is written first, and none is needed — `docs/api-stability.md` was
considered and **not** written for exactly this reason. The README's informal *API stability*
table ("Expect this to move") stays as guidance for adopters, not as a criterion.

**What this does not solve.** All eleven modules share one version, inherited from the reactor
(`${project.version}`) via the BOM, whose own stated purpose is that "every module shares one
version". If the least mature modules (`agenor-runtime-llm`, the adapters, `agenor-tools` — the
ones the README's table already names as most likely to move) keep changing indefinitely, they
hold the whole project's count at zero even once `agenor-core` and `agenor-runtime` have settled.
**Independent per-module versioning was considered and set aside**, not on principle but on
cost against a solo maintainer's capacity: it would require the BOM to manage several versions
instead of one (against its own value proposition), `release.yml` and `tools/doc-versions.sh`
would need to track which coordinate takes which version, and every release would require
deciding which modules to bump plus holding a compatibility matrix between interdependent
modules in mind. It is also not yet evidenced as the right fix — 0.34.0's own break came from the
root POM, not from a peripheral module, so the "immature modules alone are blocking the count"
premise has no case to point to yet. If that evidence appears, the cheaper middle ground is
scoping *which modules' `BREAKING` entries count toward criterion 1*, not giving each module its
own version number — recorded here for a future amendment, not actioned now.

---

## Consequences

### Positive

- The public API launches under a clean brand with no legacy naming debt.
- Annotation names at the call site are shorter for the common case (`@Agent`, `@Behavior`)
  and namespaced where collision risk is real (`@AgenorMessageHandler`).
- Version continuity (`0.23.0 → 0.24.0`) preserves the maturity signal for adopters
  evaluating the library.
- The `1.0.0` gate is defined by observable criteria, not by a calendar date, removing
  ambiguity about API stability commitments.

### Negative / trade-offs

- Any internal early adopter of `dev.agenor` artifacts must update coordinates, imports, and
  property keys. The `v0.24.0` release notes provide a complete migration table.
- `@Agent` and `@Behavior` are short, generic names. In projects that import multiple
  frameworks, developers must verify there is no annotation shadowing at the import level.
  The risk is assessed as low (see D1 rationale) but is not zero.

