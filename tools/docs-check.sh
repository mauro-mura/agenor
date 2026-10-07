#!/usr/bin/env bash
#
# Builds the documentation site the way deploy-docs.yml does, so a broken link fails the commit
# that introduced it and not the release that happened to be next.
#
# `mkdocs build --strict` turns one warning into a failure, and deploy-docs runs only when a
# GitHub release is published. 0.36.0 found out that way: docs/spring-boot-starter.md linked
# ../README.md, which is outside docs/, and the site build failed after the tag was pushed.
#
# The Javadoc is not built here. deploy-docs copies it into docs/api, and the nav names
# api/index.html, so a stub stands in for it, in a throwaway copy: the working tree is untouched.
# Everything else, links and anchors included, is checked as the real build checks it.
#
# mkdocs does not have to be installed. When it is not on the PATH, the script builds a virtualenv
# with the version deploy-docs.yml pins and keeps it under ~/.cache, so the download happens once
# and a clean machine still runs the check. Only when that cannot be done (no python3, no venv
# module, no network on the first run) is the answer "unknown", not "broken".
#
#   bash tools/docs-check.sh        exit 0 holds, 1 broken, 3 mkdocs unavailable
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Keep in step with deploy-docs.yml.
MKDOCS_PIN="mkdocs-material==9.7.2"

if ! command -v mkdocs >/dev/null 2>&1; then
    VENV="${XDG_CACHE_HOME:-$HOME/.cache}/agenor/docs-venv-${MKDOCS_PIN##*==}"
    if [ ! -x "$VENV/bin/mkdocs" ]; then
        echo "installing $MKDOCS_PIN into $VENV" >&2
        rm -rf "$VENV"
        if ! { python3 -m venv "$VENV" && "$VENV/bin/pip" install -q "$MKDOCS_PIN"; } >&2; then
            rm -rf "$VENV"
            echo "mkdocs unavailable: could not install $MKDOCS_PIN into a virtualenv" >&2
            exit 3
        fi
    fi
    PATH="$VENV/bin:$PATH"
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

cp mkdocs.yml "$TMP/"
cp -r docs "$TMP/docs"
mkdir -p "$TMP/docs/api"
echo '<!doctype html><title>stub</title>' >"$TMP/docs/api/index.html"

cd "$TMP"
if mkdocs build --strict -d "$TMP/site" >"$TMP/log" 2>&1; then
    echo "documentation builds in strict mode"
else
    grep -E 'WARNING|ERROR' "$TMP/log" | grep -v 'MkDocs 2.0' >&2 || cat "$TMP/log" >&2
    exit 1
fi
