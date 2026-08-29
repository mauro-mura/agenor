#!/usr/bin/env bash
#
# API usage census.
#
# For every public top-level type in the censused modules, counts the files that reference it,
# and separates two things a naive count conflates:
#
#   - a use: an example doing something else that happens to need the type;
#   - a demonstration: an example whose only reason to exist is to show the type off.
#
# The second kind is self-justifying and proves nothing about whether anyone needs the API.
# Tests are never scanned, of any module, which is what makes a zero here mean "no uses
# outside its own test".
#
# Reads the tree only. No build, no network. Emits the census page on stdout; redirect it
# wherever the report belongs.
#
#   bash tools/api-census.sh > api-census-$(date +%Y%m%d).md
#
# With --check it does something else entirely: it skips the census and audits the removal
# schedule, exiting non-zero if any deprecation is overdue or carries no target release at all.
#
#   bash tools/api-census.sh --check
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

CHECK_ONLY=0
case "${1:-}" in
    --check) CHECK_ONLY=1 ;;
    "")      ;;
    *)       printf 'usage: %s [--check]\n' "$0" >&2; exit 2 ;;
esac

# ---------------------------------------------------------------------------------------
# Removal schedule audit.
#
# CLAUDE.md makes `grep -c "forRemoval = true"` the phase's measure of progress on the
# reasoning that it counts the public surface actually scheduled for removal. That reading
# only holds while the schedule is honoured, and nothing was checking. It went unhonoured:
# `dev.agenor.core.AgentDirectory` promised removal at 0.24.0 and was still shipping at
# 0.28.0, four releases past its own date, because a passed date is invisible to every tool
# and to every reader who is not looking for it.
#
# Two failures, not one, and the second is the worse:
#
#   overdue  - a declared release at or below the version being built;
#   undated  - forRemoval = true with no declared release anywhere near it. This one can
#              never *become* overdue, so no check will ever see it. Both of the tree's
#              undated deprecations had sat that way for releases.
#
# The date is read from the Javadoc, not from the annotation: @Deprecated has a `since` but
# no "until", so the target release lives in prose, and prose is where it has to be read
# from. Two prepositions are in use ("removal at", "removal in") and the pattern tolerates
# both rather than demanding a sweep of 44 Javadoc blocks.
# ---------------------------------------------------------------------------------------
DEPRECATION_WINDOW=10   # measured: the furthest date sits 7 lines above its annotation

current_version() {
    # The reactor version, minus -SNAPSHOT: a deprecation targeting the release being built
    # is due now, not later. First <version> in the root pom is the project's own.
    grep -m1 -oE "<version>[^<]+</version>" pom.xml \
        | sed -E "s|</?version>||g; s/-SNAPSHOT$//"
}

# file|line|declared release (empty when none)|the declaration it sits on
deprecation_sites() {
    find . -path "*/src/main/*" -name "*.java" -print0 \
        | xargs -0 awk -v W="$DEPRECATION_WINDOW" '
        FNR == 1 { pending = 0 }
        { buf[FNR] = $0 }
        pending && $0 !~ /forRemoval = true/ {
            subject = $0
            gsub(/^[ \t]+|[ \t]+$/, "", subject)
            if (subject != "" && substr(subject, 1, 1) != "@") {
                # Trailing brace or semicolon ends a declaration; a trailing comma ends an
                # enum constant. An *interior* comma does not - it separates an extends list,
                # and cutting there reports half an interface name.
                sub(/[ \t]*[{;].*$/, "", subject)
                sub(/,[ \t]*$/, "", subject)
                if (length(subject) > 64) subject = substr(subject, 1, 61) "..."
                print FILENAME "|" pending "|" declared "|" subject
                pending = 0
            }
        }
        /forRemoval = true/ {
            declared = ""
            for (i = FNR - 1; i >= 1 && i > FNR - 1 - W; i--) {
                if (match(buf[i], /removal (at|in) [0-9]+\.[0-9]+\.[0-9]+/)) {
                    declared = substr(buf[i], RSTART, RLENGTH)
                    sub(/removal (at|in) /, "", declared)
                    break
                }
            }
            pending = FNR
        }
    ' | sed 's|^\./||' | sort
}

version_le() {   # $1 <= $2
    [[ "$1" == "$2" ]] || [[ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | head -1)" == "$1" ]]
}

run_schedule_check() {
    local version rows="" n_overdue=0 n_undated=0 file line declared subject
    version="$(current_version)"

    while IFS='|' read -r file line declared subject; do
        [[ -n "$file" ]] || continue
        if [[ -z "$declared" ]]; then
            rows+="| \`$subject\` | $file:$line | **none declared** | undated |"$'\n'
            n_undated=$((n_undated + 1))
        elif version_le "$declared" "$version"; then
            rows+="| \`$subject\` | $file:$line | $declared | **overdue** |"$'\n'
            n_overdue=$((n_overdue + 1))
        fi
    done < <(deprecation_sites)

    if (( n_overdue == 0 && n_undated == 0 )); then
        printf 'Removal schedule clean at %s: every forRemoval names a later release.\n' "$version"
        return 0
    fi

    cat <<REPORT
## Overdue

Building $version. $n_overdue past their declared release, $n_undated with no release declared.

| Deprecation | Site | Declared | |
|---|---|---|---|
$rows
An undated deprecation is the worse of the two: it can never *become* overdue, so nothing will
ever flag it. Either remove these now, or move the date and say in the commit why the release
they were promised to came and went.
REPORT
    return 1
}

if (( CHECK_ONLY )); then
    # Explicit rather than leaning on set -e: the exit code is the point of this mode.
    run_schedule_check || exit 1
    exit 0
fi

CENSUS_MODULES=(agenor-core agenor-runtime agenor-runtime-ext agenor-runtime-llm agenor-runtime-scanning)
EXAMPLES_PREFIX="agenor-examples/src/main"
EXAMPLES_README="agenor-examples/README.md"

# ---------------------------------------------------------------------------------------
# Corpus. src/main only, ever.
# ---------------------------------------------------------------------------------------
mapfile -t ALL_FILES < <(
    find . -path "*/src/main/*" -name "*.java" | sed 's|^\./||' | sort
)

declare -A PKG_OF          # file -> package
declare -A FILES_IN_PKG    # package -> space-separated files

while IFS=: read -r file pkg; do
    pkg="${pkg#package }"
    pkg="${pkg%;}"
    PKG_OF["$file"]="$pkg"
    FILES_IN_PKG["$pkg"]="${FILES_IN_PKG[$pkg]:-} $file"
done < <(grep -H -m1 "^package " "${ALL_FILES[@]}" | sed 's/;.*$/;/')

# ---------------------------------------------------------------------------------------
# Import index, built in a single pass. Java requires an import to name a type from another
# package, so this is what "references" resolves to for everything but same-package use.
# ---------------------------------------------------------------------------------------
declare -A IMPORTERS       # fully-qualified type -> space-separated files
declare -A WILDCARD        # package -> space-separated files that import it with .*

while IFS=: read -r file stmt; do
    stmt="${stmt#import }"
    stmt="${stmt#static }"
    stmt="${stmt%;}"
    stmt="${stmt// /}"
    if [[ "$stmt" == *".*" ]]; then
        WILDCARD["${stmt%.*}"]="${WILDCARD[${stmt%.*}]:-} $file"
    else
        IMPORTERS["$stmt"]="${IMPORTERS[$stmt]:-} $file"
    fi
done < <(grep -H "^import dev\.agenor\." "${ALL_FILES[@]}" || true)

# ServiceLoader registrations are references no import can express. Without this, ADR-027's
# whole SPI seam — HitlRegistrationExtension, LlmRegistrationExtension,
# DefaultAgentDiscoveryEngine and the rest — reads as dead surface and the census recommends
# deleting the thing that makes the module split work.
declare -A SERVICE_LOADED  # fully-qualified type -> 1
while IFS= read -r svc; do
    while IFS= read -r impl; do
        impl="${impl%%#*}"; impl="${impl//[[:space:]]/}"
        [[ -n "$impl" ]] && SERVICE_LOADED["$impl"]=1
    done < "$svc"
done < <(find . -path "*/src/main/resources/META-INF/services/*" -type f | sed 's|^\./||')

# ---------------------------------------------------------------------------------------
# "Dedicated to T": the example whose subject is T.
#
# Two sources, because neither alone suffices. Name equality after stripping the trailing
# role words catches RetryExample/RetryBehavior but not BatchProcessingExample/BatchBehavior;
# the README learning-path table names the concept each example demonstrates, which catches
# the rest. Loose prefix matching was rejected — it makes MessageFilterExample a
# demonstration of Message.
# ---------------------------------------------------------------------------------------
declare -A DEDICATED       # "<example file>|<TypeName>" -> 1

normalize() {
    local s="$1"
    s="${s%Example}"; s="${s%Application}"; s="${s%Demo}"
    s="${s%Behavior}"; s="${s%Agent}"
    printf '%s' "$s"
}

constant_to_behavior() {   # BATCH -> BatchBehavior, CIRCUIT_BREAKER -> CircuitBreakerBehavior
    local out="" word
    for word in ${1//_/ }; do
        out+="${word:0:1}$(printf '%s' "${word:1}" | tr '[:upper:]' '[:lower:]')"
    done
    printf '%sBehavior' "$out"
}

if [[ -f "$EXAMPLES_README" ]]; then
    while IFS= read -r line; do
        [[ "$line" == \|* ]] || continue
        fqn=$(printf '%s' "$line" | awk -F'|' '{print $3}' \
              | grep -o 'dev\.agenor\.examples\.[A-Za-z0-9_.]*' | head -1) || true
        [[ -n "${fqn:-}" ]] || continue
        path="$EXAMPLES_PREFIX/java/${fqn//.//}.java"
        [[ -f "$path" ]] || continue
        concepts=$(printf '%s' "$line" | awk -F'|' '{print $4}' | grep -o '`[^`]*`' | tr -d '`') || true
        for token in ${concepts:-}; do
            token="${token//[^A-Za-z0-9_]/}"
            [[ -n "$token" ]] || continue
            if [[ "$token" =~ ^[A-Z][A-Z0-9_]*_[A-Z0-9_]*$ || "$token" =~ ^[A-Z]{3,}$ ]]; then
                DEDICATED["$path|$(constant_to_behavior "$token")"]=1
            elif [[ "$token" =~ ^[A-Z][A-Za-z0-9]*$ ]]; then
                DEDICATED["$path|$token"]=1
            fi
        done
    done < "$EXAMPLES_README"
fi

is_dedicated() {   # <example file> <TypeName>
    [[ -n "${DEDICATED["$1|$2"]:-}" ]] && return 0
    local a b
    a=$(normalize "$(basename "$1" .java)")
    b=$(normalize "$2")
    [[ -n "$a" && "$a" == "$b" ]]
}

# ---------------------------------------------------------------------------------------
# Offered surface: the types user-facing documentation names.
#
# C-3 pairs usage with documentation — an API with no uses is "either undocumented or
# unnecessary" — so usage alone cannot decide. Framework code referencing a type only proves
# the framework needs it internally, which is no reason for a user to ever meet it.
#
# ADRs are excluded on purpose (C-4): a decision record explains why something is the way it is
# to whoever maintains it. It does not offer the type to a user. Working notes are excluded by
# construction — they do not live under docs/, which is also why they are never published.
# ---------------------------------------------------------------------------------------
mapfile -t DOC_FILES < <(
    { find docs -name "*.md" | grep -v '^docs/adr/'
      ls README.md agenor-examples/README.md 2>/dev/null; } | sort -u
)

documented_in() {   # <TypeName> -> number of user-facing doc files naming it
    # grep exits 1 on no match, which under `pipefail` would abort the whole census.
    { grep -lw -- "$1" "${DOC_FILES[@]}" 2>/dev/null || true; } | grep -c . || true
}

# ---------------------------------------------------------------------------------------
# The census
# ---------------------------------------------------------------------------------------
CENSUS_MODULE_LIST=$(printf '`%s`, ' "${CENSUS_MODULES[@]}"); CENSUS_MODULE_LIST="${CENSUS_MODULE_LIST%, }"

rows=""
declare -i n_total=0 n_keep=0 n_selfjust=0 n_offered=0 n_internal=0 n_dead=0 n_demo=0 n_dep=0

for module in "${CENSUS_MODULES[@]}"; do
    for own in "${ALL_FILES[@]}"; do
        [[ "$own" == "$module/src/main/"* ]] || continue
        grep -qE "^public (final |abstract |sealed |non-sealed )*(class|interface|enum|record|@interface) " \
            "$own" || continue

        simple=$(basename "$own" .java)
        pkg="${PKG_OF[$own]:-}"
        [[ -n "$pkg" ]] || continue
        fqn="$pkg.$simple"

        declare -A seen=()

        # Explicit importers: the import statement itself is the reference.
        for f in ${IMPORTERS["$fqn"]:-}; do
            [[ "$f" == "$own" ]] || seen["$f"]=1
        done
        # Wildcard importers and same-package files can see the type without naming it,
        # so those need the type's simple name to actually appear as a word.
        for f in ${WILDCARD["$pkg"]:-} ${FILES_IN_PKG["$pkg"]:-}; do
            [[ "$f" == "$own" ]] && continue
            [[ -n "${seen[$f]:-}" ]] && continue
            grep -qw -- "$simple" "$f" && seen["$f"]=1
        done

        # A ServiceLoader registration is a framework reference, and the strongest kind:
        # the type exists precisely so the runtime can find it without naming it.
        fw=0; ex_total=0; ex_incidental=0; demos=""
        [[ -n "${SERVICE_LOADED[$fqn]:-}" ]] && fw=1
        for f in "${!seen[@]}"; do
            if [[ "$f" == "$EXAMPLES_PREFIX/"* ]]; then
                ex_total=$((ex_total + 1))
                if is_dedicated "$f" "$simple"; then
                    demos+="${demos:+, }$(basename "$f" .java)"
                else
                    ex_incidental=$((ex_incidental + 1))
                fi
            else
                fw=$((fw + 1))
            fi
        done
        unset seen

        docs=$(documented_in "$simple")

        # A type already scheduled for removal is a decision taken, not a candidate. Without
        # this the report tells you to deprecate what you deprecated last release.
        deprecated=""
        grep -q "forRemoval = true" "$own" && { deprecated="**deprecated**"; n_dep+=1; }

        # Order matters. Being named by real user code settles it; everything below is a
        # different way of not being named.
        #
        # Framework use has to be checked before the demonstration verdict, or a type the
        # runtime depends on gets marked for deletion because someone also wrote a demo of it:
        # AgentContext has five framework callers and one demo. There the demo is the problem,
        # not the type, and the two verdicts must say so separately.
        if   (( ex_incidental > 0 ));            then verdict="named by user code";       n_keep+=1
        elif (( fw > 0 && ex_total > 0 ));       then verdict="**plumbing with a demo**";  n_demo+=1
        elif (( ex_total > 0 ));                 then verdict="**self-justifying**";       n_selfjust+=1
        elif (( docs > 0 ));                     then verdict="**documented, unnamed**";   n_offered+=1
        elif (( fw > 0 ));                       then verdict="plumbing";                  n_internal+=1
        else                                          verdict="**dead surface**";          n_dead+=1
        fi
        n_total+=1

        rows+="| \`$simple\` | $module | $fw | $ex_total | $ex_incidental | $docs | $verdict | ${deprecated:--} | ${demos:--} |"$'\n'
    done
done

# ---------------------------------------------------------------------------------------
# Behaviour types, counted as constants instead of as classes.
#
# The type census above cannot see this feature. A user reaches a behaviour by writing
# @Behavior(type = THROTTLED) and never names ThrottledBehavior, so every annotation-driven
# behaviour scores zero example references and lands in "documented, unnamed" for a reason
# that has nothing to do with whether anyone uses it.
#
# For an annotation-driven feature the *constant* is the unit of decision: the class is
# implementation the user never mentions, and deprecating it while the constant stays offered
# retires nothing.
#
# There is no framework column here, deliberately. Every framework reference to a constant is
# the switch arm that implements it — the feature's own weight, never evidence of demand.
# ---------------------------------------------------------------------------------------
BEHAVIOR_TYPE_FILE="agenor-core/src/main/java/dev/agenor/core/BehaviorType.java"

crows=""
declare -i c_total=0 c_keep=0 c_selfjust=0 c_offered=0 c_dead=0

if [[ -f "$BEHAVIOR_TYPE_FILE" ]]; then
    mapfile -t EXAMPLE_FILES < <(find "$EXAMPLES_PREFIX" -name "*.java" | sed 's|^\./||' | sort)

    # Four spaces then an upper-case word: the constants. Javadoc lines carry a leading
    # asterisk at a different indent and cannot match.
    while IFS= read -r constant; do
        behavior_class=$(constant_to_behavior "$constant")
        ex_total=0; ex_incidental=0; demos=""

        for f in "${EXAMPLE_FILES[@]}"; do
            grep -qw -- "$constant" "$f" || continue
            ex_total=$((ex_total + 1))
            if is_dedicated "$f" "$behavior_class"; then
                demos+="${demos:+, }$(basename "$f" .java)"
            else
                ex_incidental=$((ex_incidental + 1))
            fi
        done

        docs=$(documented_in "$constant")
        # The @Deprecated sits directly above the constant it applies to.
        deprecated=""
        if grep -B3 -E "^    $constant,?\$" "$BEHAVIOR_TYPE_FILE" | grep -q "forRemoval = true"; then
            deprecated="**deprecated**"
        fi

        if   (( ex_incidental > 0 ));  then verdict="named by user code";       c_keep+=1
        elif (( ex_total > 0 ));       then verdict="**self-justifying**";      c_selfjust+=1
        elif (( docs > 0 ));           then verdict="**documented, unnamed**";  c_offered+=1
        else                                verdict="**dead surface**";         c_dead+=1
        fi
        c_total+=1

        crows+="| \`$constant\` | $ex_total | $ex_incidental | $docs | $verdict | ${deprecated:--} | ${demos:--} |"$'\n'
    done < <(grep -oE '^    [A-Z][A-Z0-9_]*' "$BEHAVIOR_TYPE_FILE" | tr -d ' ')
fi

# ---------------------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------------------
cat <<EOF
# API usage census — $(date +%Y-%m-%d)

Generated by \`tools/api-census.sh\`. Do not edit by hand — rerun it instead.

## Method

Every public top-level type in ${CENSUS_MODULE_LIST}. A file counts as
referencing a type when it can see it — explicit import, wildcard import of its package, or same
package — and, for the latter two, mentions its simple name as a word. **Tests are never
scanned**, of any module: an API used only by its own test is what this census is looking for.

### What "referenced" deliberately excludes

Reaching a type through a getter does not count. \`PingPongExample\` calls
\`runtime.getAgentDirectory().findAgents(...)\` without ever importing \`AgentDirectory\`, and this
census scores that as zero — **on purpose**. The question the concept budget asks is *how many
types must a newcomer name in their own code*, and a type you never name is not a concept you
had to learn. It is plumbing you reached through something else.

So a zero here does not by itself mean "unused". It means "user code never has to name it",
which is the input to two different conclusions: either the type is genuinely unused, or it is
plumbing that user-facing documentation is presenting as surface (C-1). The **docs** column is
what separates them.

The census covers types, not methods. Method-level findings — \`BaseAgent.requestFrom\`,
\`ConversationManager.onMessage\` — still need finding by hand.

### Read \`framework = 1\` with suspicion

A single framework reference is often not independent evidence of need. \`RetryBehavior\`,
\`BatchBehavior\` and \`ScheduledBehavior\` each score 1, and in all three cases the referrer is
\`ExtBehaviorAnnotationExtension\` — same package, which is why no import shows up. That class
exists precisely to wire those behaviours to their annotation. Machinery whose only job is to
reach a feature does not demonstrate that anyone wants the feature; it is the feature's own
weight, counted twice. Check who the referrer is before reading a 1 as a reprieve.

The column that decides is **incidental**: example references *minus* references inside the
example whose subject is that type. \`RetryExample\` naming \`RetryBehavior\` is a demonstration,
not a use, and counting it is how an API with no users certifies itself as used.

Framework references are reported but never settle a verdict on their own. That the framework
needs a type internally is no reason for a user to ever meet it — so the second axis is
**docs**: how many user-facing pages name the type (ADRs excluded, per C-4).
Crossing the two is what C-3 actually asks for, an API being "either undocumented or
unnecessary":

| Verdict | Rule | Action |
|---|---|---|
| named by user code | an example about something else names it | — it is a concept, and it earns its place |
| **plumbing with a demo** | framework depends on it, but the only example is its own demonstration | delete the example, keep the type |
| **self-justifying** | nothing depends on it; named only by its own demonstration | deprecate; the example goes with it |
| **documented, unnamed** | user-facing docs name it, no user code does | decide: plumbing (stop documenting as surface) or unused (deprecate) |
| plumbing | never named by user code, never documented as surface | — correctly invisible |
| **dead surface** | named by nothing, documented nowhere | deprecate |

## Summary

| Verdict | Count |
|---|---|
| named by user code | $n_keep |
| **plumbing with a demo** | $n_demo |
| **self-justifying** | $n_selfjust |
| **documented, unnamed** | $n_offered |
| plumbing | $n_internal |
| **dead surface** | $n_dead |
| **Total** | $n_total |

Of these, **$n_dep are already deprecated for removal** — decisions taken, not candidates. The
verdict column reports what the tree looks like to a user; the *deprecated* column is what stops
a later reader re-deciding something settled.

## Table

Sorted by verdict, then module, then type.

| Type | Module | Framework | Examples | Incidental | Docs | Verdict | Deprecated | Demonstrated by |
|---|---|---|---|---|---|---|---|---|
EOF
printf '%s' "$rows" | sort -t'|' -k8,8 -k3,3 -k2,2

cat <<EOF

## Behaviour types, counted as constants

The table above counts types. It cannot see this feature: a user writes
\`@Behavior(type = THROTTLED)\` and never names \`ThrottledBehavior\`, so every annotation-driven
behaviour scores zero example references for a reason unrelated to whether anyone uses it.

**For an annotation-driven feature the constant is the unit of decision.** The class is
implementation the user never mentions, and deprecating the class while the constant stays
offered retires nothing.

No framework column, deliberately: every framework reference to a constant is the switch arm
that implements it — the feature's own weight, not evidence of demand. The **incidental**
column decides here exactly as it does above.

| Verdict | Count |
|---|---|
| named by user code | $c_keep |
| **self-justifying** | $c_selfjust |
| **documented, unnamed** | $c_offered |
| **dead surface** | $c_dead |
| **Total** | $c_total |

| Constant | Examples | Incidental | Docs | Verdict | Deprecated | Demonstrated by |
|---|---|---|---|---|---|---|
EOF
printf '%s' "$crows"
