#!/usr/bin/env bash
#
# Fails if the integration tests did not actually run.
#
# `mvn verify` reports BUILD SUCCESS whether or not a single *IT executed: the
# classes carry @EnabledIfSystemProperty(named = "integration.tests.enabled"), so
# without that property JUnit *skips* them — which is how four IT classes in this
# repository were compiled and never executed for the project's whole life, and how
# 0.28.0 shipped with the pre-release checklist's "integration tests" box ticked.
#
# Two things are checked, both of which a green build can violate:
#   1. at least one failsafe report exists (something ran at all);
#   2. no report contains a skipped test (CONTRIBUTING's "Skipped: 0").
#
# Usage: bash tools/assert-integration-tests-ran.sh [module-dir ...]
# With no arguments it looks at every module in the tree.

set -euo pipefail

roots=("$@")
if [ ${#roots[@]} -eq 0 ]; then
    roots=(.)
fi

mapfile -t reports < <(
    for root in "${roots[@]}"; do
        find "$root" -path '*/target/failsafe-reports/TEST-*.xml' -print
    done | sort -u
)

if [ ${#reports[@]} -eq 0 ]; then
    echo "FAIL: no failsafe reports found — no integration test ran." >&2
    echo "      Did the run pass -Dintegration.tests.enabled=true?" >&2
    exit 1
fi

total_tests=0
total_skipped=0
failed=0

for report in "${reports[@]}"; do
    # The testsuite element carries the counts as attributes; it is the first
    # <testsuite ...> in the file, and awk is enough to read them.
    line=$(grep -m1 -o '<testsuite [^>]*>' "$report" || true)
    name=$(sed -n 's/.*name="\([^"]*\)".*/\1/p' <<<"$line")
    tests=$(sed -n 's/.*[^-]tests="\([0-9]*\)".*/\1/p' <<<"$line")
    skipped=$(sed -n 's/.*skipped="\([0-9]*\)".*/\1/p' <<<"$line")
    tests=${tests:-0}
    skipped=${skipped:-0}

    total_tests=$(( total_tests + tests ))
    total_skipped=$(( total_skipped + skipped ))

    if [ "$skipped" != "0" ]; then
        echo "FAIL: ${name:-$report}: Skipped: $skipped (of $tests)" >&2
        failed=1
    else
        echo "ok:   ${name:-$report}: $tests run, 0 skipped"
    fi
done

echo "---"
echo "$total_tests integration tests in ${#reports[@]} classes, $total_skipped skipped"

if [ "$failed" != "0" ]; then
    echo "FAIL: an integration test was skipped. A skipped IT verifies nothing." >&2
    exit 1
fi
