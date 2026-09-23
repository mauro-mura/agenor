#!/usr/bin/env bash
#
# Move the twelve POMs to a version. Nothing else.
#
# The version bump is asymmetric, and this is the half that is the same in both directions:
# cutting a release moves the POMs *and* every documented coordinate (`tools/doc-versions.sh
# --set`), while reopening main moves the POMs *only* — documentation keeps the released version,
# because a coordinate in the README is an installation snippet a reader copies. A reopen that
# touches 17 files is the bug, not the fix.
#
# Three traps, all of which this avoids by construction:
#
#   - `mvn versions:set` silently skips agenor-bom/pom.xml, which declares its own <version> with
#     no parent. Enumerating `pom.xml */pom.xml` is what catches it, and the count below fails
#     loudly if the tree ever stops having twelve.
#
#   - tools/central-smoke/pom.xml is a thirteenth POM and is not one of the twelve. Its own
#     version is never published; its agenor.version property names the *released* version under
#     test, so it moves with the documentation and stays there on the way back to -SNAPSHOT. The
#     `pom.xml */pom.xml` glob excludes it; a recursive find would not.
#
#   - a bare `sed` across the tree would rewrite the just-released number where it legitimately
#     survives: @since tags, @deprecated(since = ...), and prose. This touches one line per POM,
#     and prints those counts before and after so you can see they did not move.
#
#   bash tools/bump-poms.sh 0.36.0-SNAPSHOT
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

usage() {
    sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d' | sed 's/^# \{0,1\}//'
    exit 2
}

[ $# -eq 1 ] || usage
NEW="$1"
case "$NEW" in [0-9]*) ;; *) usage ;; esac

shopt -s nullglob
poms=(pom.xml */pom.xml)
shopt -u nullglob

if [ "${#poms[@]}" -ne 12 ]; then
    printf 'Expected 12 POMs, found %d:\n' "${#poms[@]}" >&2
    printf '  %s\n' "${poms[@]}" >&2
    printf 'A module was added or removed. Decide what that means before bumping.\n' >&2
    exit 1
fi

CURRENT="$(sed -n 's|.*<version>\(.*\)</version>.*|\1|p' pom.xml | head -1)"
[ -n "$CURRENT" ] || { printf 'No <version> in the root POM.\n' >&2; exit 1; }

if [ "$CURRENT" = "$NEW" ]; then
    printf 'The POMs already name %s. Nothing to do.\n' "$NEW"
    exit 0
fi

# The number that must NOT move: the released version, where it legitimately survives.
released="${CURRENT%-SNAPSHOT}"
count_survivors() {
    local java md
    java=$( { grep -rlF "$released" --include='*.java' */src/main 2>/dev/null || true; } | wc -l)
    md=$( { grep -rlF --include='*.md' "$released" . 2>/dev/null || true; } | grep -v '/target/' | wc -l)
    printf '%s java, %s markdown' "$java" "$md"
}
before="$(count_survivors)"

changed=0
for pom in "${poms[@]}"; do
    # The first <version> in each file is the one that moves: the project's own in the root POM
    # and in agenor-bom, the <parent> version in every module.
    set +e
    python3 - "$pom" "$CURRENT" "$NEW" <<'PY'
import pathlib, sys
path, current, new = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
text = path.read_text()
needle = f"<version>{current}</version>"
i = text.find(needle)
if i == -1:
    sys.exit(3)
path.write_text(text[:i] + f"<version>{new}</version>" + text[i + len(needle):])
PY
    rc=$?
    set -e
    case $rc in
        0) changed=$((changed + 1)) ;;
        3) printf '%s: no <version>%s</version> — left untouched\n' "$pom" "$CURRENT" >&2 ;;
        *) exit 1 ;;
    esac
done

after="$(count_survivors)"

printf '%s -> %s in %d of %d POMs\n' "$CURRENT" "$NEW" "$changed" "${#poms[@]}"
printf 'Files still naming %s: %s (before: %s)\n' "$released" "$after" "$before"
[ "$before" = "$after" ] || printf 'Those two differ. Something rewrote the tree, not just the POMs.\n' >&2
[ "$changed" -eq 12 ] || { printf 'Not all twelve moved.\n' >&2; exit 1; }
printf 'Documentation coordinates are NOT touched by this. On a release, run:\n'
printf '  bash tools/doc-versions.sh --set %s\n' "${NEW%-SNAPSHOT}"
