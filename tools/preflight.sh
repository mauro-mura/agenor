#!/usr/bin/env bash
#
# The pre-release checklist, as something you can run.
#
# CONTRIBUTING's checklist says why this exists better than this header can: "an item you cannot
# check against something is a formality". 0.28.0 shipped with broken Javadoc and with no
# integration test having run, while a shorter version of that list was satisfied. Every item
# below already had a command; what was missing was one command that runs them all and says, in
# one table, which ones hold.
#
# It reimplements nothing. Each row is one of the scripts in this folder, or one Maven goal.
#
# Three states, and the difference between two of them is the whole point:
#
#   PASS   the check ran and holds
#   FAIL   the check ran and does not hold
#   SKIP   the check did not run — it is not satisfied, it is unknown
#
# A SKIP is not a pass. `--fast` skips the two Maven rows, and a tree where the integration
# tests were never run skips that row rather than failing it, because "you have not run them
# here" and "they ran and were skipped" are different facts. The closing line says whether the
# run satisfies the release checklist, and only a run with no SKIP can.
#
# The version argument is what the *documentation* must name, which is not the POM's version:
# a coordinate in the README is an installation snippet, so between releases it names the last
# released version while the POMs are already on the next -SNAPSHOT.
#
#   bash tools/preflight.sh                # docs checked against the latest tag
#   bash tools/preflight.sh 0.35.0         # docs checked against the release being cut
#   bash tools/preflight.sh --fast         # everything except the two Maven rows
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
export LC_ALL=C

usage() {
    sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d' | sed 's/^# \{0,1\}//'
    exit 2
}

FAST=0
VERSION=""
while [ $# -gt 0 ]; do
    case "$1" in
        --fast) FAST=1 ;;
        -h|--help) usage ;;
        [0-9]*) VERSION="$1" ;;
        *) usage ;;
    esac
    shift
done

if [ -z "$VERSION" ]; then
    # Between releases the documentation names the last released version, not the POM's.
    VERSION="$(git describe --tags --abbrev=0 2>/dev/null | sed 's/^v//')"
fi

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

fails=0
skips=0

row() { printf '%-4s %-22s %s\n' "$1" "$2" "${3:-}"; }

# run <label> <note-on-pass> <command...>
run() {
    local label="$1" note="$2"; shift 2
    local log="$OUT/$label.log"
    if "$@" >"$log" 2>&1; then
        row PASS "$label" "$note"
    else
        row FAIL "$label" "$(tail -n 1 "$log")"
        sed 's/^/       /' "$log" >>"$OUT/failures"
        fails=$((fails + 1))
    fi
}

skip() { row SKIP "$1" "$2"; skips=$((skips + 1)); }

printf '%-4s %-22s %s\n' "----" "----------------------" "--------------------------------------"

# Cheap rows first: all four together cost under a second, so a breach is reported before
# Maven has finished starting.
run "removal schedule" "no deprecation overdue or undeclared" bash tools/api-census.sh --check
run "changelog budget" "every entry within its prose budget"  bash tools/changelog-budget.sh --check
run "public refs"      "nothing points at unpublished material" bash tools/public-refs-check.sh

if [ -n "$VERSION" ]; then
    run "doc coordinates" "every snippet names $VERSION" bash tools/doc-versions.sh --check "$VERSION"
else
    skip "doc coordinates" "no tag found and no version given"
fi

if [ "$FAST" -eq 1 ]; then
    skip "build"   "--fast"
    skip "javadoc" "--fast"
else
    run "build"   "mvn clean install"    mvn -q clean install
    run "javadoc" "mvn javadoc:javadoc"  mvn -q javadoc:javadoc
fi

# Three states, not two, because the reports being present does not mean the tests ran: a plain
# `mvn verify` writes a full set of failsafe reports in which every test is skipped, and that is
# the ordinary state of a development tree. Failing on it would train you to ignore the row —
# the defect this script exists to avoid. So:
#
#   all skipped        the last verify had no -Dintegration.tests.enabled=true  -> SKIP
#   some ran, some not a class opted out while its neighbours ran               -> FAIL
#   all ran            -> PASS
#
# The dangerous case is still caught: a SKIP does not satisfy the checklist, and the closing
# line says so.
if compgen -G "*/target/failsafe-reports/TEST-*.xml" >/dev/null; then
    if bash tools/assert-integration-tests-ran.sh >"$OUT/it.log" 2>&1; then
        row PASS "integration tests" "every IT class ran, none skipped"
    else
        it_total=""; it_skipped=""
        read -r it_total it_skipped < <(
            sed -n 's/^\([0-9]*\) integration tests in [0-9]* classes, \([0-9]*\) skipped$/\1 \2/p' \
                "$OUT/it.log"
        ) || true
        if [ -n "$it_total" ] && [ "$it_total" = "$it_skipped" ]; then
            skip "integration tests" \
                 "all $it_total skipped — last verify had no -Dintegration.tests.enabled=true"
        else
            row FAIL "integration tests" "$(tail -n 1 "$OUT/it.log")"
            sed 's/^/       /' "$OUT/it.log" >>"$OUT/failures"
            fails=$((fails + 1))
        fi
    fi
else
    skip "integration tests" "no failsafe report — mvn verify -Dintegration.tests.enabled=true"
fi

# Informational: a measurement, never a gate. See tools/coverage-report.sh.
if coverage="$(bash tools/coverage-report.sh 2>/dev/null)"; then
    overall="$(printf '%s' "$coverage" | sed -n 's/^(all measured modules) *\([0-9.]*\)%.*/\1/p')"
    under="$(printf '%s' "$coverage" | grep -c 'under 80%' || true)"
    stale="$(printf '%s' "$coverage" | grep -c 'stale' || true)"
    if [ -n "$overall" ]; then
        row INFO "coverage" "${overall}% overall, $under module(s) under 80%, $stale stale"
    else
        row INFO "coverage" "no report yet — run mvn verify"
    fi
fi

printf '%-4s %-22s %s\n' "----" "----------------------" "--------------------------------------"

if [ -s "$OUT/failures" ]; then
    printf '\n'
    cat "$OUT/failures"
fi

printf '\n'
if [ "$fails" -gt 0 ]; then
    printf '%d check(s) failed. The release checklist is not satisfied.\n' "$fails"
    exit 1
fi
if [ "$skips" -gt 0 ]; then
    printf 'No failures, but %d check(s) did not run. A SKIP is not a PASS: this run does not\n' "$skips"
    printf 'satisfy the release checklist.\n'
    exit 0
fi
printf 'Every check ran and holds. The release checklist is satisfied for %s.\n' "${VERSION:-this tree}"
