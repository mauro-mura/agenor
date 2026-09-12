# Central smoke test

A standalone consumer project — **not** a module of the Agenor build — that answers one
question about a release: *can somebody who has never seen this repository depend on it?*

It imports `dev.agenor:agenor-bom` at a version you pass in, resolves from Maven Central
only, compiles the README's `HelloAgent` verbatim, and runs it until a message arrives on
the topic the agent publishes to. A release that resolves but does not run fails here.

## Running it

```bash
mvn -f tools/central-smoke/pom.xml \
    -Dagenor.version=0.34.0 \
    -Dmaven.repo.local=/tmp/agenor-smoke-repo \
    test
```

`-Dmaven.repo.local` pointing at an empty directory is not optional in spirit: without it
a stale `mvn install` in `~/.m2` answers every resolution and the run proves nothing about
Central. The `Central smoke test` workflow does the same thing on a clean runner, with a
`version` input, and additionally fails if a logging backend reached the classpath.

## What it deliberately does not have

- **No logging backend.** SLF4J prints its "no providers" warning and stays quiet. That
  warning is the expected output: it is what a consumer sees who has not chosen a backend,
  and it is the evidence that no published module chose one for them.
- **No test framework.** The assertion is an exit code, so the only dependencies are the
  two Agenor modules under test.
- **No place in `<modules>`.** The reactor must not build it, or it would resolve Agenor
  from the reactor instead of from Central.
