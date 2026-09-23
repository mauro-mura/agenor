# `.claude/`

Configuration for [Claude Code](https://claude.com/claude-code), the assistant this repository is
largely built with. **Nothing here is required to contribute.** Clone the repo, run
`mvn clean install`, open a branch: the build, the tests and CI are the same for everyone, and
none of them reads this directory.

## What is in here

| Path | What it does |
|---|---|
| `hooks/hand-git-over.sh` | Refuses two families **when an assistant tries to run them**: `commit`, `push`, `tag`, `merge`, `rebase` and `gh release create`, which are the maintainer's to run and are handed over prepared; and `restore`, `clean`, `checkout --`, `reset --hard`, `stash drop`, which discard work in a tree that has two actors. **Creating a branch is deliberately allowed** — it changes no content and review lands on the commit. |
| `hooks/branch-guard.sh` | Says once per session, without blocking, when the tree being edited is on `main` and CONTRIBUTING asks for a branch. |
| `hooks/session-start.sh` | Prints branch, versions and two fast checks at the start of a session. |
| `skills/release/` | The release procedure of CONTRIBUTING § Release Process, as steps to follow rather than prose to re-read. |
| `settings.json` | Registers the above, and pre-approves read-only git and Maven commands so they do not prompt. |

## What it is not

It is **not project policy**. A hook here binds an assistant working in this tree and nothing
else: a human contributor commits their own work, exactly as CONTRIBUTING describes, and no hook
can or should stop them.

Nor does any project rule live only in here. Every check that matters is a script in `tools/`,
run by CI and by `bash tools/preflight.sh` — a hook only triggers one earlier. If you ever find a
rule enforced here and nowhere else, that is a defect in the rule's home, not a feature of this
directory.

## Precedence

`docs/adr/` > [`CONTRIBUTING.md`](../CONTRIBUTING.md) > [`CLAUDE.md`](../CLAUDE.md). Where they
disagree, the leftmost wins.

## Not tracked

`settings.local.json` (one machine's paths and MCP endpoints), `CLAUDE.md` in this directory, and
`skills/graphify/` (a third-party skill installed by its own tool).
