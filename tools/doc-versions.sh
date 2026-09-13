#!/usr/bin/env bash
#
# The Agenor version that documentation tells readers to depend on.
#
# Every Maven coordinate in the Markdown is an installation snippet a reader copies into their
# own build, so on a release all of them must name the version being released. They used to be
# kept by hand from a list in CONTRIBUTING ("the five documents"), and the list went stale the
# first time a page gained a coordinate. This finds them by pattern instead, so a new page needs
# no one to remember it:
#
#   - a <version> directly after <groupId>dev.agenor</groupId><artifactId>agenor-...</artifactId>
#   - a Gradle coordinate dev.agenor:agenor-...:<version>
#   - the agenor.version property of tools/central-smoke/pom.xml, which names the released
#     version under test and behaves like a document, not like one of the twelve POMs
#
# Prose is never matched: "as recently as 0.33.0" names a version without being a coordinate.
# CHANGELOG.md and docs/adr/ are excluded, because they are history.
#
#   bash tools/doc-versions.sh --list            every coordinate, file:line and version
#   bash tools/doc-versions.sh --set 0.35.0      rewrite them all (the release bump)
#   bash tools/doc-versions.sh --check 0.35.0    exit 1 unless they all name 0.35.0
#
# --check also verifies that the logback-classic version the README tells readers to add is the
# one the build itself uses (logback.version in the root POM), because that snippet drifts on its
# own schedule rather than with releases. release.yml runs --check before deploying.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

usage() {
    sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d' | sed 's/^# \{0,1\}//'
    exit 2
}

MODE="${1:-}"
VERSION="${2:-}"
case "$MODE" in
    --list) ;;
    --set|--check)
        [ -n "$VERSION" ] || usage
        case "$VERSION" in
            *-SNAPSHOT)
                # A snapshot names nothing a reader can resolve; documentation keeps the last
                # released version between releases, which is why a reopen never runs this.
                echo "refusing $VERSION: documentation never names a -SNAPSHOT" >&2
                exit 2
                ;;
        esac
        ;;
    *) usage ;;
esac

python3 - "$MODE" "$VERSION" <<'PY'
import pathlib, re, subprocess, sys

mode, target = sys.argv[1], sys.argv[2]

XML = re.compile(
    r"(<groupId>dev\.agenor</groupId>\s*<artifactId>(agenor-[a-z-]+)</artifactId>\s*<version>)"
    r"([^<]+)(</version>)")
GRADLE = re.compile(r"(dev\.agenor:(agenor-[a-z-]+):)([0-9][^'\"\s)]*)")
SMOKE = pathlib.Path("tools/central-smoke/pom.xml")
SMOKE_PROP = re.compile(r"(<agenor\.version>)([^<]+)(</agenor\.version>)")

tracked = subprocess.run(["git", "ls-files", "*.md"], capture_output=True, text=True,
                         check=True).stdout.split()
docs = [pathlib.Path(f) for f in tracked
        if f != "CHANGELOG.md" and not f.startswith("docs/adr/")]

found = []      # (path, line, artifact, version)
rewrites = {}   # path -> new text

def scan(path, text, patterns):
    new = text
    for pattern, version_group, artifact_group in patterns:
        for m in pattern.finditer(text):
            line = text.count("\n", 0, m.start(version_group)) + 1
            artifact = m.group(artifact_group) if artifact_group else "central-smoke"
            found.append((path, line, artifact, m.group(version_group)))
        if mode == "--set":
            # Replace only the version's own span, right to left so earlier offsets stay valid.
            # Rebuilding the match from its groups instead would duplicate any group nested in
            # another — the artifactId sits inside the prefix group.
            for m in reversed(list(pattern.finditer(new))):
                new = new[:m.start(version_group)] + target + new[m.end(version_group):]
    if new != text:
        rewrites[path] = new

for path in docs:
    scan(path, path.read_text(encoding="utf-8"), [(XML, 3, 2), (GRADLE, 3, 2)])
scan(SMOKE, SMOKE.read_text(encoding="utf-8"), [(SMOKE_PROP, 2, None)])

if not any(p != SMOKE for p, *_ in found):
    # Zero matches means the pattern broke, not that the documentation is clean.
    print("found no Agenor coordinate in any document; the patterns no longer match", file=sys.stderr)
    sys.exit(1)

if mode == "--list":
    for path, line, artifact, version in found:
        print(f"{path}:{line}\t{artifact}\t{version}")
    files = len({p for p, *_ in found})
    print(f"{len(found)} coordinates in {files} files", file=sys.stderr)

elif mode == "--set":
    for path, text in rewrites.items():
        path.write_text(text, encoding="utf-8")
    moved = [f for f in found if f[3] != target]
    for path, line, artifact, version in moved:
        print(f"{path}:{line}\t{artifact}\t{version} -> {target}")
    print(f"{len(moved)} of {len(found)} coordinates moved to {target}, "
          f"in {len(rewrites)} files", file=sys.stderr)

else:  # --check
    failures = [f"{p}:{l}\t{a} names {v}, not {target}" for p, l, a, v in found if v != target]

    pom = pathlib.Path("pom.xml").read_text(encoding="utf-8")
    logback = re.search(r"<logback\.version>([^<]+)</logback\.version>", pom).group(1)
    readme = pathlib.Path("README.md").read_text(encoding="utf-8")
    for m in re.finditer(r"<artifactId>logback-classic</artifactId>\s*<version>([^<]+)</version>",
                         readme):
        if m.group(1) != logback:
            line = readme.count("\n", 0, m.start(1)) + 1
            failures.append(f"README.md:{line}\tlogback-classic names {m.group(1)}, "
                            f"but the build uses {logback} (logback.version in pom.xml)")

    if failures:
        print("\n".join(failures), file=sys.stderr)
        if any("logback-classic" not in f for f in failures):
            print(f"coordinates: run  bash tools/doc-versions.sh --set {target}", file=sys.stderr)
        if any("logback-classic" in f for f in failures):
            print("logback: edit the README snippet by hand — --set moves Agenor coordinates only",
                  file=sys.stderr)
        sys.exit(1)
    print(f"all {len(found)} documented coordinates name {target}; "
          f"README logback matches the build ({logback})")
PY
