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
#   bash tools/docs-check.sh        exit 0 holds, 1 broken, 3 mkdocs is not installed
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if ! command -v mkdocs >/dev/null 2>&1; then
    echo "mkdocs is not installed: pip install mkdocs-material==9.7.2" >&2
    exit 3
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
