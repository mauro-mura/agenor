#!/usr/bin/env bash
#
# Line coverage per module, from the reports JaCoCo has already written.
#
# CONTRIBUTING asks for more than 80% line coverage and nothing enforces it: the build binds
# `prepare-agent` and `report` with no `check` rule, and CI publishes the reports as an artifact
# rather than failing on a threshold. That is a deliberate position, not an oversight — four
# modules are under the line today and a threshold would fail the build before anyone had
# decided which of them is worth raising.
#
# What was missing is the number itself, in a form you can read without opening ten HTML pages.
# This reads the CSV JaCoCo writes beside them and prints the table, worst first. It never
# fails: it is a measurement, and the decision it feeds is a human one.
#
# Two things it refuses to do quietly:
#
#   - report a stale number. A CSV older than the newest source file in its own module is
#     measuring code that no longer exists, which on this tree has already looked like a defect
#     in the code rather than in the build. Such a row is marked, not printed as fact.
#
#   - drop a module without saying so. agenor-examples is excluded because it is demonstration
#     material with no tests, and silently omitting it would be the same move as deleting a
#     page to improve a verdict. It gets a line of its own below the table.
#
#   bash tools/coverage-report.sh        # always exits 0; run `mvn verify` first
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# A decimal comma is a locale away, and this table is read in CI as well as here.
export LC_ALL=C

usage() {
    sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d' | sed 's/^# \{0,1\}//'
    exit 2
}

[ $# -eq 0 ] || usage

THRESHOLD=80
EXCLUDED="agenor-examples"      # demonstration material, deliberately untested

shopt -s nullglob
reports=(*/target/site/jacoco/jacoco.csv)
shopt -u nullglob

if [ ${#reports[@]} -eq 0 ]; then
    printf 'No JaCoCo report found. Run `mvn verify` first — `mvn test` writes none.\n'
    exit 0
fi

rows=""
total_missed=0
total_covered=0

for csv in "${reports[@]}"; do
    module="${csv%%/*}"

    read -r missed covered < <(
        awk -F, 'NR > 1 { m += $8; c += $9 } END { printf "%d %d\n", m + 0, c + 0 }' "$csv"
    )
    lines=$((missed + covered))
    [ "$lines" -gt 0 ] || continue

    pct=$(awk -v c="$covered" -v l="$lines" 'BEGIN { printf "%.1f", 100 * c / l }')

    note=""
    if [ -d "$module/src" ] && [ -n "$(find "$module/src" -name '*.java' -newer "$csv" -print -quit)" ]; then
        note="stale — source newer than the report"
    fi

    if [ "$module" = "$EXCLUDED" ]; then
        excluded_line=$(printf '%s: %s%% of %d lines, excluded — demonstration material, no tests' \
                        "$module" "$pct" "$lines")
        continue
    fi

    total_missed=$((total_missed + missed))
    total_covered=$((total_covered + covered))
    [ -z "$note" ] && awk -v p="$pct" -v t="$THRESHOLD" 'BEGIN { exit !(p + 0 < t + 0) }' && note="under $THRESHOLD%"

    rows+=$(printf '%s\t%s\t%d\t%d\t%s\n' "$pct" "$module" "$covered" "$lines" "$note")
    rows+=$'\n'
done

printf '%-30s %8s %16s   %s\n' "MODULE" "LINE" "COVERED/TOTAL" "NOTE"
printf '%s' "$rows" | sort -t$'\t' -k1,1n | while IFS=$'\t' read -r pct module covered lines note; do
    printf '%-30s %7s%% %16s   %s\n' "$module" "$pct" "$covered/$lines" "$note"
done

total_lines=$((total_missed + total_covered))
if [ "$total_lines" -gt 0 ]; then
    printf '%-30s %7s%% %16s\n' "(all measured modules)" \
        "$(awk -v c="$total_covered" -v l="$total_lines" 'BEGIN { printf "%.1f", 100 * c / l }')" \
        "$total_covered/$total_lines"
fi

# A module with source and no report is not a module at 0%: it is a module whose tests did not
# run, which a table that simply omits it would hide. agenor-bom has no source and is correctly
# absent.
missing=""
for pom in */pom.xml; do
    module="$(dirname "$pom")"
    [ -d "$module/src/main/java" ] || continue
    [ -f "$module/target/site/jacoco/jacoco.csv" ] && continue
    missing="$missing $module"
done
[ -n "$missing" ] && printf '\nNo report:%s — tests did not run there.\n' "$missing"

[ -n "${excluded_line:-}" ] && printf '\n%s\n' "$excluded_line"
printf 'Threshold is %s%% (CONTRIBUTING). Nothing fails on it; this is a measurement.\n' "$THRESHOLD"
