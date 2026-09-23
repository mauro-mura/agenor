#!/usr/bin/env bash
#
# PreToolUse(Edit|Write): say so, once, when the tree being edited is on main.
#
# CONTRIBUTING asks for a branch per change. The rule is kept most of the time and broken in
# exactly the situation where it is hardest to notice: a one-line documentation fix that grows.
# `00ac507` is the last such commit at the time of writing, and 0.33.0 had one too.
#
# This warns and does not block, deliberately. A branch is cheap but it is the maintainer's
# call, and a hook that refuses the edit turns a typo fix into a negotiation. It speaks once
# per session, because a reminder repeated on every edit is a reminder nobody reads.
#
# Not project policy: it binds an assistant working in this tree and nothing else.
#
set -euo pipefail

AGENOR_HOOK_PAYLOAD="$(cat)" python3 - <<'PY'
import json, os, pathlib, subprocess, sys, tempfile

try:
    event = json.loads(os.environ.get("AGENOR_HOOK_PAYLOAD") or "{}")
except (json.JSONDecodeError, ValueError):
    sys.exit(0)

path = (event.get("tool_input") or {}).get("file_path") or ""
session = event.get("session_id") or "nosession"

try:
    root = subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True,
                          text=True, check=True).stdout.strip()
    branch = subprocess.run(["git", "rev-parse", "--abbrev-ref", "HEAD"], capture_output=True,
                            text=True, check=True).stdout.strip()
except (subprocess.CalledProcessError, FileNotFoundError):
    sys.exit(0)                       # not a git tree: nothing to say

if branch != "main":
    sys.exit(0)

# Outside the repository, or inside the maintainer's unpublished notes: neither is a commit.
try:
    rel = pathlib.Path(path).resolve().relative_to(pathlib.Path(root).resolve())
except (ValueError, OSError):
    sys.exit(0)
if rel.parts and rel.parts[0] == "working":
    sys.exit(0)

stamp = pathlib.Path(tempfile.gettempdir()) / f"agenor-branch-warn-{session}"
if stamp.exists():
    sys.exit(0)
try:
    stamp.touch()
except OSError:
    pass

print(json.dumps({"hookSpecificOutput": {
    "hookEventName": "PreToolUse",
    "additionalContext": (
        "This tree is on main. CONTRIBUTING asks for a branch per change "
        "(feature/ fix/ docs/ refactor/ test/ chore/ build/). Hand the maintainer the branch "
        "command before going further, unless they have said to work on main. "
        "Said once per session; it will not be repeated."
    )}}))
PY
