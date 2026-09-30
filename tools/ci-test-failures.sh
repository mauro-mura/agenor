#!/usr/bin/env bash
#
# Says, in a form a reader without admin rights can see, which tests failed.
#
# A red CI job is opaque from outside the repository: the job log answers 403 and the report
# artifacts 401. The one thing that stays public is the check-run annotations
# (GET /repos/{owner}/{repo}/check-runs/{job_id}/annotations), and the only annotation the
# nightly run carried was "Unable to connect to localhost/<unresolved>:32771" — which named
# neither the test nor the cause, and was not the failure. So this script makes the job emit
# the annotation itself: one `::error` per failed test, plus a row in the step summary.
#
# It reads the plain-text reports, `*/target/{surefire,failsafe}-reports/*.txt`, where a failed
# test is a line ending in `<<< FAILURE!` or `<<< ERROR!` followed by the exception. The
# per-class summary line also ends that way and starts with `Tests run:`; it is skipped.
#
# It always exits 0. It runs in an `if: failure()` step inside a job that is already red, and
# must not replace Maven's verdict with its own. Finding nothing prints nothing: a job can be
# red for a reason that is not a test, and inventing a failure would be worse than silence.
#
# Reads the tree only. No build, no network.
#
#   bash tools/ci-test-failures.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
export LC_ALL=C

# GitHub's workflow-command escaping: without it a '%' or a newline in an exception message
# truncates the annotation.
escape() {
    local s=$1
    s=${s//'%'/'%25'}
    s=${s//$'\r'/'%0D'}
    s=${s//$'\n'/'%0A'}
    printf '%s' "$s"
}

summary="${GITHUB_STEP_SUMMARY:-/dev/null}"
found=0

mapfile -t reports < <(
    find . \( -path '*/target/surefire-reports/*.txt' -o -path '*/target/failsafe-reports/*.txt' \) \
        -type f -print | sort
)

for report in "${reports[@]}"; do
    class=""
    expect_cause=0
    method=""
    while IFS= read -r line || [ -n "$line" ]; do
        if [[ $line == "Test set: "* ]]; then
            class=${line#Test set: }
            expect_cause=0
        elif [[ $line == *"<<< FAILURE!" || $line == *"<<< ERROR!" ]]; then
            if [[ $line == "Tests run:"* ]]; then
                expect_cause=0
            else
                method=${line%%[[:space:]]*}
                expect_cause=1
            fi
        elif [ "$expect_cause" = 1 ] && [ -n "$line" ]; then
            expect_cause=0
            if [ "$found" = 0 ]; then
                {
                    echo "## Failed tests"
                    echo
                    echo "| Test | Cause |"
                    echo "|------|-------|"
                } >>"$summary"
            fi
            found=$(( found + 1 ))
            name="${class##*.}#${method##*.}"
            cause=${line//|/\\|}
            echo "| \`$name\` | \`$cause\` |" >>"$summary"
            echo "::error title=$(escape "$name")::$(escape "$line")"
        fi
    done <"$report"
done
