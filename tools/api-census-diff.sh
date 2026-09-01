#!/usr/bin/env bash
#
# Diff two api-census.sh reports by verdict, per type and per behaviour constant — not by
# totals. A summary count can improve while every individual verdict it is made of gets worse:
# 0.29.0 deleted docs/mailbox.md and three mailbox types went "documented, unnamed" ->
# "plumbing", which reads as progress in the summary table and is actually three types that
# lost the only thing asking whether they belong. Comparing per row is what catches that.
#
# Done by hand once, at 0.30.0, against a saved copy of the previous report. This is the same
# comparison, repeatable:
#
#   bash tools/api-census.sh > /tmp/before.md   # before the change
#   ... make the change ...
#   bash tools/api-census.sh > /tmp/after.md
#   bash tools/api-census-diff.sh /tmp/before.md /tmp/after.md
#
set -euo pipefail

if [[ $# -ne 2 ]]; then
    printf 'usage: %s <old-report.md> <new-report.md>\n' "$0" >&2
    exit 2
fi

OLD_REPORT="$1"
NEW_REPORT="$2"

for f in "$OLD_REPORT" "$NEW_REPORT"; do
    [[ -f "$f" ]] || { printf '%s: no such file\n' "$f" >&2; exit 2; }
done

# Both tables in the report are pipe-delimited markdown with the type/constant name in column 2
# and a verdict column further along; which column and which section is which is keyed off the
# header row, so a future column added to one table does not silently misread the other.
extract_verdicts() {   # <report-file> -> "type:Module:Name<TAB>verdict" / "const:Name<TAB>verdict", one per line
    awk -F'|' '
        /^\| Type \| Module \| Framework /  { section = "type"; next }
        /^\| Constant \| Examples \|/       { section = "const"; next }
        /^##? /                             { section = "" }
        section == "type" && NF >= 9 {
            name = $2; gsub(/[ `]/, "", name)
            module = $3; gsub(/^[ \t]+|[ \t]+$/, "", module)
            verdict = $8; gsub(/^[ \t]+|[ \t]+$/, "", verdict); gsub(/\*/, "", verdict)
            if (name != "" && name != "---") print "type:" module ":" name "\t" verdict
        }
        section == "const" && NF >= 7 {
            name = $2; gsub(/[ `]/, "", name)
            verdict = $6; gsub(/^[ \t]+|[ \t]+$/, "", verdict); gsub(/\*/, "", verdict)
            if (name != "" && name != "---") print "const:" name "\t" verdict
        }
    ' "$1"
}

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

extract_verdicts "$OLD_REPORT" | sort -u > "$WORK_DIR/old.tsv"
extract_verdicts "$NEW_REPORT" | sort -u > "$WORK_DIR/new.tsv"

declare -A OLD_V NEW_V
while IFS=$'\t' read -r key verdict; do
    [[ -n "$key" ]] && OLD_V["$key"]="$verdict"
done < "$WORK_DIR/old.tsv"
while IFS=$'\t' read -r key verdict; do
    [[ -n "$key" ]] && NEW_V["$key"]="$verdict"
done < "$WORK_DIR/new.tsv"

DIFF_FILE="$WORK_DIR/diff.txt"
: > "$DIFF_FILE"

for key in "${!NEW_V[@]}"; do
    if [[ -z "${OLD_V[$key]:-}" ]]; then
        printf '+ %s: (new type) -> %s\n' "$key" "${NEW_V[$key]}" >> "$DIFF_FILE"
    elif [[ "${OLD_V[$key]}" != "${NEW_V[$key]}" ]]; then
        printf '~ %s: %s -> %s\n' "$key" "${OLD_V[$key]}" "${NEW_V[$key]}" >> "$DIFF_FILE"
    fi
done
for key in "${!OLD_V[@]}"; do
    if [[ -z "${NEW_V[$key]:-}" ]]; then
        printf -- '- %s: %s -> (removed)\n' "$key" "${OLD_V[$key]}" >> "$DIFF_FILE"
    fi
done

if [[ ! -s "$DIFF_FILE" ]]; then
    printf 'No verdict changed between %s and %s.\n' "$OLD_REPORT" "$NEW_REPORT"
    exit 0
fi

printf 'Verdict changes, %s -> %s:\n\n' "$OLD_REPORT" "$NEW_REPORT"
sort "$DIFF_FILE"
