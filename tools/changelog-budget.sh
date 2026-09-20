#!/usr/bin/env bash
#
# CHANGELOG prose budget.
#
# Measures how much prose each CHANGELOG entry spends, and — with --check — fails when an
# entry spends more than the budget allows.
#
# The rule it enforces is not a style preference. `CHANGELOG.md` declares adherence to Keep a
# Changelog, whose entries are one line, and the file stopped matching its own declared format
# somewhere around 0.25.0. Measured over the file's history, words per top-level entry went
#
#     0.3.0 - 0.9.0      7-8
#     0.14.0 - 0.22.0   26-51
#     0.25.0 onward    87-203        (single entries of 477, 390, 359, 340, 313 words)
#
# and the last ten of thirty-nine releases hold 72% of the file. What grew is not the number of
# changes: it is the amount of *reasoning per change*, which an ADR already holds. The
# ADR-018 amendment of 0.34.0 and its CHANGELOG entry differ in one preposition.
#
# So the budget is on prose, and prose only. Three things are free, because each is the
# CHANGELOG doing a job nothing else does:
#
#   - nested lists      — enumerating what was removed, and what replaces each one;
#   - fenced code       — the before/after a migration needs;
#   - the entry's lead  — the bold sentence a reader scans.
#
# What is not free is the paragraph after the paragraph after the lead. An entry that needs
# those links the ADR or the page that holds them.
#
# Reads the tree only. No build, no network, no git. Emits the per-release table on stdout.
#
#   bash tools/changelog-budget.sh                 # the table; always exits 0
#   bash tools/changelog-budget.sh --check         # audit in-scope releases; non-zero on breach
#   bash tools/changelog-budget.sh --check --all   # audit every release, including the archive
#
# Scope, by default, is `[Unreleased]` plus every release at or above --since. Releases below
# it are history: they are measured and shown, never failed. Rewriting a shipped entry costs
# more than it returns and destroys the record, which is also why `tools/doc-versions.sh`
# refuses to rewrite this file at all.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

CHANGELOG="CHANGELOG.md"
BUDGET=120        # prose words per entry, lead included
MAX_PARAS=2       # the lead, and one paragraph after it
SINCE="0.35.0"    # the first release the rule applies to
MODE="report"
SCOPE="since"

while [ $# -gt 0 ]; do
    case "$1" in
        --check)   MODE="check" ;;
        --all)     SCOPE="all" ;;
        --budget)  shift; BUDGET="${1:?--budget needs a number}" ;;
        --paras)   shift; MAX_PARAS="${1:?--paras needs a number}" ;;
        --since)   shift; SINCE="${1:?--since needs a version}" ;;
        --file)    shift; CHANGELOG="${1:?--file needs a path}" ;;
        *) printf 'usage: %s [--check] [--all] [--budget N] [--paras N] [--since X.Y.Z] [--file PATH]\n' "$0" >&2; exit 2 ;;
    esac
    shift
done

[ -f "$CHANGELOG" ] || { printf '%s: no such file\n' "$CHANGELOG" >&2; exit 2; }

awk -v MODE="$MODE" -v SCOPE="$SCOPE" -v BUDGET="$BUDGET" -v MAXP="$MAX_PARAS" \
    -v SINCE="$SINCE" -v FILE="$CHANGELOG" '
function vge(a, b,   x, y, i, n) {
    # true when version a >= version b; both are x.y.z with no suffix in this file
    n = split(a, x, "."); split(b, y, ".")
    for (i = 1; i <= 3; i++) {
        if ((x[i] + 0) > (y[i] + 0)) return 1
        if ((x[i] + 0) < (y[i] + 0)) return 0
    }
    return 1
}
function inscope(sec) {
    if (SCOPE == "all") return 1
    if (sec == "Unreleased") return 1
    return vge(sec, SINCE)
}
function lead(s,   t, n, parts) {
    # the bold opener if there is one, else the first words of the entry
    if (s ~ /^\*\*/) { n = split(s, parts, /\*\*/); t = parts[2] }
    else             { t = s }
    if (length(t) > 58) t = substr(t, 1, 55) "..."
    return t
}
function flush(   over) {
    if (!b_open) return
    if (b_para_prose > 0) b_paras++
    n = ++count[section]
    words[section] = words[section] + b_prose
    if (b_prose > maxw[section]) maxw[section] = b_prose
    total_prose += b_prose; total_free += b_free; total_entries++
    if (inscope(section)) {
        scoped_entries++
        if (MODE != "check") { b_open = 0; return }
        over = 0
        if (b_prose > BUDGET) {
            printf "%s:%d: [%s] %d prose words, budget %d - \"%s\"\n",
                   FILE, b_line, section, b_prose, BUDGET, lead(b_lead) > "/dev/stderr"
            over = 1
        }
        if (b_paras > MAXP) {
            printf "%s:%d: [%s] %d prose paragraphs, budget %d - \"%s\"\n",
                   FILE, b_line, section, b_paras, MAXP, lead(b_lead) > "/dev/stderr"
            over = 1
        }
        if (b_breaking && !b_migration) {
            printf "%s:%d: [%s] BREAKING with no \"Migration:\" line - \"%s\"\n",
                   FILE, b_line, section, lead(b_lead) > "/dev/stderr"
            over = 1
        }
        if (over) breaches++
    }
    b_open = 0
}
BEGIN { section = "(preamble)"; fence = 0 }
/^```/                  { fence = !fence }
!fence && /^## \[/      { flush(); section = $0; sub(/^## \[/, "", section); sub(/\].*$/, "", section)
                          order[++nsec] = section; next }
!fence && /^#/          { flush(); next }
!fence && /^- /         {
    flush()
    b_open = 1; b_line = FNR; b_prose = 0; b_free = 0; b_paras = 0; b_para_prose = 0
    b_breaking = 0; b_migration = 0; b_list = 0
    b_lead = $0; sub(/^- /, "", b_lead)
    line = $0; sub(/^- /, "", line)
    b_prose += split(line, tmp, /[ \t]+/); b_para_prose = 1
    if (line ~ /^\*\*BREAKING/) b_breaking = 1     # the marker opens the lead; a mention is not one
    next
}
b_open {
    if (fence || $0 ~ /^[ \t]*```/) { b_free += NF; next }           # code: free
    if ($0 ~ /^[ \t]*$/) {                                           # paragraph break
        if (b_para_prose > 0) { b_paras++; b_para_prose = 0 }
        next
    }
    if ($0 ~ /^ {2,}[-*] / || $0 ~ /^ {2,}[0-9]+\. /) { b_list = 1; b_free += NF; next }
    if (b_list && $0 ~ /^ {4,}/)                      { b_free += NF; next }
    b_list = 0
    b_prose += NF; b_para_prose++
    if ($0 ~ /[Mm]igration:/)   b_migration = 1
    next
}
END {
    flush()
    if (MODE == "check") {
        if (breaches > 0) {
            printf "\n%d of %d audited entr%s over budget (%s).\n",
                   breaches, scoped_entries, (scoped_entries == 1 ? "y" : "ies"),
                   (SCOPE == "all" ? "every release" : "Unreleased and >= " SINCE) > "/dev/stderr"
            exit 1
        }
        printf "changelog-budget: %d audited entr%s within %d prose words and %d paragraphs (%s).\n",
               scoped_entries, (scoped_entries == 1 ? "y" : "ies"), BUDGET, MAXP,
               (SCOPE == "all" ? "every release" : "Unreleased and >= " SINCE)
        exit 0
    }
    print "| release | entries | prose words | per entry | longest | over budget |"
    print "|---------|--------:|------------:|----------:|--------:|------------:|"
    for (i = 1; i <= nsec; i++) {
        s = order[i]
        if (count[s] == 0) continue
        over = 0
        printf "| %s%s | %d | %d | %d | %d | %s |\n",
               s, (inscope(s) ? " *" : ""), count[s], words[s],
               int(words[s] / count[s]), maxw[s],
               (maxw[s] > BUDGET ? "yes" : "no")
    }
    printf "\nBudget %d prose words, %d paragraphs per entry; * marks the releases --check audits.\n",
           BUDGET, MAXP
    printf "Totals: %d entries, %d prose words, %d words in lists and code (exempt).\n",
           total_entries, total_prose, total_free
}
' "$CHANGELOG"
