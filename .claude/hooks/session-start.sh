#!/usr/bin/env bash
#
# SessionStart: the four facts every session in this repository begins by reconstructing.
#
# Branch, version, what the documentation's coordinates name, and whether the two cheap checks
# hold. Each was previously four commands into a session, which meant they were run when
# something already looked wrong rather than before anything was decided.
#
# Measured cost of the whole thing: well under half a second, almost all of it git. If it ever
# grows past that, take something out — a session opener that is slow gets disabled, and then
# none of it is there when it matters.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
export LC_ALL=C

branch="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo '?')"
version="$(sed -n 's|.*<version>\(.*\)</version>.*|\1|p' pom.xml | head -1)"

dirty="$(git status --porcelain 2>/dev/null | grep -c '^ *[MARD]' || true)"
untracked="$(git status --porcelain 2>/dev/null | grep -c '^??' || true)"

docs="$(bash tools/doc-versions.sh --list 2>/dev/null | awk 'NF > 2 { print $NF }' | sort -u | paste -sd, -)"

printf 'Agenor: %s' "$branch"
[ "$branch" = "main" ] && printf '  (CONTRIBUTING asks for a branch per change)'
printf ' — POMs %s, documented coordinates %s\n' "$version" "${docs:-none found}"
printf 'Tree: %s modified, %s untracked\n' "$dirty" "$untracked"

# Only the two that cost nothing. The public-references check walks every tracked file and
# is worth 0.8s in preflight and in CI, not on every session opener.
for check in "removal schedule:api-census.sh --check" "changelog budget:changelog-budget.sh --check"; do
    label="${check%%:*}"; cmd="${check#*:}"
    if bash tools/${cmd} >/dev/null 2>&1; then
        printf '%s: clean  ' "$label"
    else
        printf '%s: FAILS  ' "$label"
    fi
done
printf '\n'
printf 'CONTRIBUTING.md is binding: branch per change, and a behaviour verified only against\n'
printf 'InMemoryMessageDispatcher is not verified. Run `bash tools/preflight.sh --fast` to see more.\n'
