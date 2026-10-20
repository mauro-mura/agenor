#!/usr/bin/env bash
#
# Documented versions of the optional adapter libraries check.
#
# Lettuce, OpenTelemetry and Flyway's database modules are optional dependencies (ADR-018), kept
# out of agenor-bom on purpose, so a consumer declares them with a version. The snippet that tells
# them to is then a claim about the POMs, and it has gone wrong in the form this script looks for:
# three snippets named ${lettuce.version} or ${opentelemetry.version}, properties that exist only
# inside Agenor's own build, and one named no version at all. Copied into an application that
# does not use Spring Boot, none of them resolved.
#
# The expected versions are read from the POMs, not written here:
#
#   - lettuce.version and opentelemetry.version from agenor-adapters/pom.xml;
#   - flyway.version from agenor-adapters-persistence/pom.xml;
#   - jackson.version from the root pom.xml.
#
# Every <dependency> on io.lettuce:lettuce-core, io.opentelemetry:opentelemetry-* or
# org.flywaydb:flyway-*, inside an xml fence of docs/**/*.md (ADRs excepted: they are a record),
# README.md or a module README.md, must carry exactly that version. So must the two properties
# a Spring Boot application sets to override what Boot manages below Agenor's needs:
# <lettuce.version> and <jackson-2-bom.version>.
#
# What it cannot prove: it reads Maven XML only. A Gradle coordinate or a version named in prose
# is out of scope by design. It is a tripwire for the form of claim that has already gone wrong,
# not a proof the page is right.
#
# Reads the tree. No build, no network.
#
#   bash tools/docs-versions-check.sh        # exits 1 when a snippet and the POMs disagree
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

usage() {
    sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d' | sed 's/^# \{0,1\}//'
    exit 2
}

[ $# -eq 0 ] || usage

python3 - <<'PY'
import pathlib, re, sys

def prop(pom, name):
    m = re.search(rf"<{re.escape(name)}>([^<]+)</{re.escape(name)}>", pathlib.Path(pom).read_text())
    if not m:
        sys.exit(f"{pom}: no <{name}> property; the source moved, so this check would pass vacuously")
    return m.group(1).strip()

lettuce = prop("agenor-adapters/pom.xml", "lettuce.version")
otel = prop("agenor-adapters/pom.xml", "opentelemetry.version")
flyway = prop("agenor-adapters-persistence/pom.xml", "flyway.version")
jackson = prop("pom.xml", "jackson.version")

# Spring Boot property overrides: the snippet sets the property Boot's BOM reads
OVERRIDES = {"lettuce.version": lettuce, "jackson-2-bom.version": jackson}

def expected(group, artifact):
    if group == "io.lettuce" and artifact == "lettuce-core":
        return lettuce, "lettuce.version"
    if group == "io.opentelemetry" and artifact.startswith("opentelemetry-"):
        return otel, "opentelemetry.version"
    if group == "org.flywaydb" and artifact.startswith("flyway-"):
        return flyway, "flyway.version"
    return None

pages = [p for p in pathlib.Path("docs").rglob("*.md") if "adr" not in p.parts]
pages += [pathlib.Path("README.md"), *pathlib.Path(".").glob("*/README.md")]

FENCE = re.compile(r"^[ \t]*```xml[^\n]*\n(.*?)^[ \t]*```", re.S | re.M)
DEP = re.compile(r"<dependency>(.*?)</dependency>", re.S)
OVERRIDE = re.compile(r"<(lettuce\.version|jackson-2-bom\.version)>\s*([^<]*?)\s*</\1>")

def tag(body, name):
    m = re.search(rf"<{name}>\s*([^<]*?)\s*</{name}>", body)
    return m.group(1) if m else None

checked = breaches = 0
for page in sorted(set(pages)):
    text = page.read_text()
    for fence in FENCE.finditer(text):
        for dep in DEP.finditer(fence.group(1)):
            body = dep.group(1)
            want = expected(tag(body, "groupId"), tag(body, "artifactId") or "")
            if want is None:
                continue
            checked += 1
            line = text.count("\n", 0, fence.start(1) + dep.start()) + 1
            version = tag(body, "version")
            coordinate = f"{tag(body, 'groupId')}:{tag(body, 'artifactId')}"
            if version != want[0]:
                print(f"{page}:{line}: {coordinate} names version {version or '(none)'}, "
                      f"the POMs build against {want[0]} ({want[1]})", file=sys.stderr)
                breaches += 1
        for override in OVERRIDE.finditer(fence.group(1)):
            checked += 1
            name, version = override.group(1), override.group(2)
            line = text.count("\n", 0, fence.start(1) + override.start()) + 1
            if version != OVERRIDES[name]:
                print(f"{page}:{line}: <{name}> is {version}, the POMs build against "
                      f"{OVERRIDES[name]}", file=sys.stderr)
                breaches += 1

if breaches:
    sys.exit(1)
if checked == 0:
    sys.exit("no snippet declares Lettuce, OpenTelemetry or Flyway; the pages moved, "
             "so this check would pass vacuously")
print(f"documented versions agree with the POMs ({checked} snippets: lettuce {lettuce}, "
      f"opentelemetry {otel}, flyway {flyway}, jackson {jackson})")
PY
