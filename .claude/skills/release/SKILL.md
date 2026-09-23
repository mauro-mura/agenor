---
name: release
description: Cut an Agenor release, or reopen main afterwards. Use when asked to release, tag, publish a version to Maven Central, or bump back to -SNAPSHOT.
---

# Releasing Agenor

CONTRIBUTING § Release Process is authoritative; this is the order of operations and the checks
that are easy to skip. Read that section before a first release — it explains *why* each step is
here, and a step whose reason you do not know is a step you will drop under pressure.

**Every git command in here is handed to the maintainer, never run.** A hook enforces this, but
the reason matters more than the hook: they sign, and they decide what goes out. Hand over **one**
command at a time and stop editing the tree until it has been run — `git add` stages a file's
whole content, so an edit made in between lands in that commit.

---

## Which one is this?

| Ask | Mode |
|---|---|
| "release 0.36.0", "cut a release", "publish to Central" | **Cut**, below |
| "reopen main", "back to SNAPSHOT", "next development version" | **Reopen**, below |

The two are **not symmetric** and that is the single most important fact here: a cut moves the
twelve POMs *and* every documented coordinate; a reopen moves the twelve POMs *only*.

---

## Cut a release `x.y.z`

### 1. Preflight, before anything is edited

```bash
bash tools/preflight.sh x.y.z
```

Every row must be `PASS`. A `SKIP` is not a pass — in particular the integration tests, which are
opt-in and which a plain `mvn verify` silently skips:

```bash
mvn verify -Dintegration.tests.enabled=true    # needs Docker
bash tools/preflight.sh x.y.z                  # now the IT row can be PASS
```

Two checklist items no script covers, both of which have shipped broken:

- **Examples run, not just compile.** Start the ones this release touched and read their output
  against what their own Javadoc claims.
- **Migration notes reach the CHANGELOG before the code goes.** A deprecation names its
  replacement in Javadoc that the removal then deletes. Copy it out first.

### 2. Versions

```bash
bash tools/bump-poms.sh x.y.z          # the twelve POMs
bash tools/doc-versions.sh --set x.y.z # every documented coordinate, and central-smoke
bash tools/doc-versions.sh --list      # read what moved
```

### 3. CHANGELOG

Open `## [x.y.z] - <date>` under an empty `## [Unreleased]`, mark breaking changes, add the two
link rows pointing at `mauro-mura/agenor`. Then:

```bash
bash tools/changelog-budget.sh --check
```

An entry is a bold lead sentence and at most one paragraph. When it needs more, link the ADR.

### 4. Hand over, one at a time

Write the commit message to a file under the scratchpad and hand over the commands separately:
the release commit, then the tag, then the merge, then the push. Do not batch them. After the
maintainer reports each one, `git status` — a new file left untracked is the failure mode here.

### 5. Publish

Publishing the **GitHub release** is what triggers everything: `release.yml` (Central) and
`deploy-docs.yml` (the site) both fire on that one event. Nothing is deployed by a local
`mvn deploy`.

Then, and these are the maintainer's to do:

1. **Press Publish in the Central Portal** — `autoPublish` is false, so the deployment waits
   there, validated but unpublished. It is the only place the bundle can be inspected: confirm no
   `agenor-examples` artifact, and that every jar module has `.pom`, `.jar`, `-sources.jar`,
   `-javadoc.jar`, an `.asc` for each, and checksums.
2. **Wait for the sync.** Central first, `repo.maven.apache.org` after a delay with no SLA. A
   smoke failure in the first half hour is more likely the sync than the release.
3. **Run the smoke test** — the `Central smoke test` workflow with the published version, or:
   ```bash
   mvn -f tools/central-smoke/pom.xml -Dagenor.version=x.y.z \
       -Dmaven.repo.local=/tmp/agenor-smoke-repo test
   ```
   The empty local repository is the point; `~/.m2` would answer every resolution.

**A release is immutable.** Central does not allow overwriting a published version.

---

## Reopen main

```bash
bash tools/bump-poms.sh x.y.z-SNAPSHOT
```

That is the whole edit. **Do not run `doc-versions.sh`** — documentation keeps the released
version, because every coordinate in it is an installation snippet a reader copies, and a
`-SNAPSHOT` there tells a newcomer to depend on something that names nothing. The script refuses
a `-SNAPSHOT` for exactly this reason.

Then verify the three traps, which `bump-poms.sh` reports for you:

- twelve POMs moved, `agenor-bom` among them;
- `tools/central-smoke/pom.xml` did **not** move — its `agenor.version` names the released
  version under test, and a smoke test pointed at a snapshot resolves nothing from Central;
- the counts of files still naming the released version are unchanged before and after. That
  number legitimately survives in `@since`, `@deprecated(since = …)` and prose. At 0.30.0 it was
  33 java and 22 markdown, and the reopen left both alone.

A reopen that touches 17 files is the bug, not the fix.
