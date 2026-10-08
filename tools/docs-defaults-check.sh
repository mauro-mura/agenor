#!/usr/bin/env bash
#
# Documented default models check.
#
# A page that says "default: <model>" is making a claim about code, and that claim goes stale the
# day the code moves. It has shipped twice: 0.35.0 corrected five statements on
# docs/llm-integration.md that the adapters did not honour, and that page then went on naming
# claude-3-5-sonnet-20241022 as Anthropic's default after getDefaultModel() had moved to
# claude-sonnet-4-6.
#
# The allowed set is read from the Java, not written here:
#
#   - each adapter's getDefaultModel(), resolved through its Models enum to the id string;
#   - the Spring Boot starter's DEFAULT_*_MODEL constants. These differ from the adapters' on
#     purpose (the starter has always defaulted to the smaller, cheaper model), so both sets are
#     legitimate and a page may name either.
#
# Every docs/**/*.md line that says "default" and names a model must name one of those.
#
# What it cannot prove: it reads only lines containing the word "default". A model named on a
# neighbouring line, or a default described without the word, is out of scope by design. It is a
# tripwire for the form of claim that has already gone wrong, not a proof the page is right.
#
# Reads the tree. No build, no network.
#
#   bash tools/docs-defaults-check.sh        # exits 1 on the first page that disagrees
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
import pathlib, re, sys

ADAPTERS = pathlib.Path("agenor-adapters/src/main/java/dev/agenor/adapters/llm")
STARTER = pathlib.Path("agenor-spring-boot-starter/src/main/java/dev/agenor/autoconfigure/AgenorAutoConfiguration.java")

allowed = {}  # model id -> where it is defined

for src in sorted(ADAPTERS.glob("*/*Provider.java")):
    text = src.read_text()
    m = re.search(r"getDefaultModel\(\)\s*\{\s*return\s+Models\.(\w+)\.id;", text)
    if not m:
        continue
    const = re.search(r"\b" + m.group(1) + r'\s*\(\s*"([^"]+)"', text)
    if not const:
        sys.exit(f"{src}: getDefaultModel() names Models.{m.group(1)} but no such constant was found")
    allowed[const.group(1)] = f"{src.name} getDefaultModel()"

for m in re.finditer(r'DEFAULT_\w+_MODEL\s*=\s*"([^"]+)"', STARTER.read_text()):
    allowed[m.group(1)] = f"{STARTER.name} {m.group(0).split('=')[0].strip()}"

if len(allowed) < 4:
    sys.exit(f"found only {len(allowed)} default models in the code ({sorted(allowed)}); "
             "the patterns here no longer match the source, so this check would pass vacuously")

MODEL = re.compile(r"\b(?:gpt-[\w.\-]+|claude-[\w.\-]+|llama[\w.\-:]*\d[\w.\-:]*)")

breaches = 0
for page in sorted(pathlib.Path("docs").rglob("*.md")):
    for n, line in enumerate(page.read_text().splitlines(), 1):
        if "default" not in line.lower():
            continue
        for model in MODEL.findall(line):
            model = model.rstrip(".,:;`")
            if model not in allowed:
                print(f"{page}:{n}: says default and names {model}, "
                      f"which no getDefaultModel() or starter default returns", file=sys.stderr)
                breaches += 1

if breaches:
    print(f"allowed: {', '.join(sorted(allowed))}", file=sys.stderr)
    sys.exit(1)
print(f"documented defaults agree with the code ({len(allowed)} models)")
PY
