#!/usr/bin/env bash
#
# Public references check.
#
# A tracked file must be readable by someone who has only the repository. Two families of
# reference break that, and both have already shipped:
#
#   - a path into `working/`. That folder's own README states the rule — "nothing outside this
#     folder references anything inside it", naming CLAUDE.md, CHANGELOG.md, an ADR and a Javadoc
#     @deprecated tag — because a working note is deleted the moment its content is superseded,
#     and every pointer at it becomes dangling on that day. ADR-032 already cited a work plan
#     that was later deleted.
#
#   - an internal register label: F-18, W-6, O-7, C-2, "task 3". These name rows in documents
#     that are not published. A reader meeting one has no way to resolve it, and `bc50b55` had
#     to go and resolve a batch of them by hand.
#
# What is deliberately NOT a breach:
#
#   - D-n. Those are ADR decision identifiers, defined in the ADR that uses them and public by
#     construction: 17 in ADR-028, 14 in ADR-032, 11 in ADR-033. Confusing the two families is
#     the one way this check can do damage, so the label pattern names its letters explicitly
#     rather than matching any letter.
#
#   - milestone labels of the M6 shape. \bM[0-9]+\b collides with too much ordinary prose to
#     be worth failing a build over. If one ever leaks, add it to LABEL below.
#
# Scans tracked files only — what is in `.gitignore` is by definition not public. Reads the
# tree and `git ls-files`. No build, no network.
#
#   bash tools/public-refs-check.sh        # exits non-zero on the first family of breach
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

usage() {
    sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d' | sed 's/^# \{0,1\}//'
    exit 2
}

[ $# -eq 0 ] || usage

python3 - <<'PY'
import pathlib, re, subprocess, sys

# The two files that must be allowed to name what everything else may not: the ignore rule that
# excludes the folder, and this check itself.
SKIP = {".gitignore", "tools/public-refs-check.sh"}

WORKING = re.compile(r"(?<![-\w.])working/")
LABEL   = re.compile(r"\b[FWOC]-\d+\b")
TASK    = re.compile(r"\b[Tt]ask \d+\b")

tracked = subprocess.run(["git", "ls-files"], capture_output=True, text=True,
                         check=True).stdout.split("\n")

breaches = 0
scanned = 0

for name in tracked:
    if not name or name in SKIP:
        continue
    path = pathlib.Path(name)
    try:
        text = path.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        continue            # binary, or gone from the working tree
    scanned += 1
    # One pass over the whole file before splitting it: almost no file matches, and walking
    # every line of every tracked file cost more than the rest of the checks put together.
    if not (WORKING.search(text) or LABEL.search(text) or TASK.search(text)):
        continue
    for n, line in enumerate(text.splitlines(), start=1):
        if WORKING.search(line):
            print(f"{name}:{n}: references working/ — that note is deleted the day it is "
                  f"superseded, and this becomes a dangling pointer", file=sys.stderr)
            breaches += 1
        for m in LABEL.finditer(line):
            print(f'{name}:{n}: internal register label "{m.group()}" — a reader cannot '
                  f"resolve it; say what it says instead", file=sys.stderr)
            breaches += 1
        for m in TASK.finditer(line):
            print(f'{name}:{n}: internal work-plan reference "{m.group()}" — the plan is not '
                  f"published; name the change instead", file=sys.stderr)
            breaches += 1

if breaches:
    print(f"\n{breaches} reference(s) into unpublished material, across {scanned} tracked files.")
    sys.exit(1)

print(f"Public references clean: {scanned} tracked files, no path into working/, "
      f"no internal register label.")
PY
