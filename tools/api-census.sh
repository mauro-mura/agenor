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
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

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
declare -i n_total=0 n_keep=0 n_selfjust=0 n_offered=0 n_internal=0 n_dead=0 n_demo=0

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

        rows+="| \`$simple\` | $module | $fw | $ex_total | $ex_incidental | $docs | $verdict | ${demos:--} |"$'\n'
    done
done

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

## Table

Sorted by verdict, then module, then type.

| Type | Module | Framework | Examples | Incidental | Docs | Verdict | Demonstrated by |
|---|---|---|---|---|---|---|---|
EOF
printf '%s' "$rows" | sort -t'|' -k8,8 -k3,3 -k2,2
