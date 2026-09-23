#!/usr/bin/env bash
#
# PreToolUse(Bash): refuse the git operations that belong to the maintainer.
#
# In this repository the agent prepares commits, tags, merges and pushes; it does not run them.
# That was a rule the agent had to remember, and a rule an agent has to remember is a rule that
# fails on the session where the context is long and the change is small. This makes it a
# property of the tool instead.
#
# It is not project policy, and it binds nobody but an assistant working in this tree: a human
# contributor commits their own work, as CONTRIBUTING describes. Nothing in the build, the CI or
# the checks in tools/ depends on this file existing.
#
# Two families are refused, for two different reasons.
#
#   Handed over — commit, push, tag, merge, rebase, and `gh release|pr create`. These are the
#   maintainer's: they sign, and they decide what goes out.
#
#   Never run here — restore, clean, `checkout --`, `reset --hard`, `stash drop|clear`. These
#   discard work in the tree, and this tree has two actors: the maintainer may have uncommitted
#   changes of their own in it at any moment.
#
# **Creating a branch is deliberately not refused.** `git checkout -b` and `git switch -c` change
# no content, publish nothing, and are undone with `git branch -d`; review lands on the commit,
# which is refused anyway. Handing branch creation over cost a round trip and bought nothing, so
# it is not a rule here — do not reintroduce it out of caution.
#
# Read-only git — status, log, diff, show, ls-files, branch — is untouched, and so is anything
# that merely mentions one of those words in an argument.
#
# Escape hatch, for a session where you have decided otherwise:  AGENOR_GIT_HOOK=off
#
set -euo pipefail

[ "${AGENOR_GIT_HOOK:-on}" = "off" ] && exit 0

# The payload travels in the environment, not on stdin: a `python3 - <<EOF` heredoc *is* stdin,
# and would eat the very JSON this hook is here to read.
AGENOR_HOOK_PAYLOAD="$(cat)" python3 - <<'PY'
import json, os, re, sys

try:
    event = json.loads(os.environ.get("AGENOR_HOOK_PAYLOAD") or "{}")
except (json.JSONDecodeError, ValueError):
    sys.exit(0)                       # not something this hook understands; do not get in the way

command = (event.get("tool_input") or {}).get("command") or ""

HANDOVER = {"commit", "push", "tag", "merge", "rebase"}
DISCARD  = {"restore", "clean"}
GH       = {("release", "create"), ("pr", "create")}

# Split on shell separators so `cd x && git commit` is seen as two commands, and a quoted
# "git commit" inside an echo argument is not seen as one at all.
segments = re.split(r"(?:\|\||&&|[;\n|])", command)

def words(segment):
    try:
        import shlex
        return shlex.split(segment)
    except ValueError:                # unbalanced quotes: fall back to whitespace
        return segment.split()

def refused(segment):
    w = [x for x in words(segment) if "=" not in x.split("/")[0] or x.startswith("-")]
    while w and re.fullmatch(r"\w+=.*", w[0]):
        w.pop(0)                      # leading VAR=value assignments
    if not w:
        return None
    prog = w[0].rsplit("/", 1)[-1]
    rest = w[1:]
    if prog == "git":
        # skip global options that take a value, e.g. `git -C path commit`
        i = 0
        while i < len(rest):
            if rest[i] in ("-C", "-c", "--git-dir", "--work-tree", "--namespace"):
                i += 2
                continue
            if rest[i].startswith("-"):
                i += 1
                continue
            break
        if i < len(rest):
            sub, tail = rest[i], rest[i:]
            if sub in HANDOVER:
                return f"git {sub}", "handover"
            if sub in DISCARD:
                return f"git {sub}", "discard"
            if sub == "reset" and "--hard" in tail:
                return "git reset --hard", "discard"
            # `git checkout -b x` is fine; `git checkout -- path` throws the file away.
            if sub == "checkout" and "--" in tail:
                return "git checkout --", "discard"
            if sub == "stash" and ({"drop", "clear"} & set(tail)):
                return f"git stash {'drop' if 'drop' in tail else 'clear'}", "discard"
        return None
    if prog == "gh":
        pair = tuple(x for x in rest if not x.startswith("-"))[:2]
        if pair in GH:
            return "gh " + " ".join(pair), "handover"
    return None

for segment in segments:
    verdict = refused(segment)
    if verdict and verdict[1] == "discard":
        what = verdict[0]
        print(f"""Blocked: `{what}` discards work in the working tree.

This tree has two actors. The maintainer may have uncommitted changes in it right now, and
nothing in that command distinguishes theirs from yours.

  - To undo an edit of your own, rewrite the file.
  - If the tree genuinely needs cleaning, hand the command over and say what will be lost.

Escape hatch, if this was deliberate: AGENOR_GIT_HOOK=off""", file=sys.stderr)
        sys.exit(2)
    if verdict:
        what = verdict[0]
        print(f"""Blocked: `{what}` is the maintainer's to run, not yours.

Prepare it instead:
  1. Write the commit message to a file under the scratchpad — a pasted heredoc interleaves
     with whatever else is being typed in that terminal.
  2. Hand over ONE command, then stop. Do not keep editing the tree while it runs: `git add`
     stages a file's whole content, so an edit made meanwhile lands in that commit.
  3. After the maintainer has run it, check `git status` for files the commit missed.

Escape hatch, if this was deliberate: AGENOR_GIT_HOOK=off""", file=sys.stderr)
        sys.exit(2)

sys.exit(0)
PY
