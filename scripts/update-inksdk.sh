#!/usr/bin/env bash
# Sync the vendored inksdk subtree at third_party/inksdk with upstream.
# Usage: ./scripts/update-inksdk.sh [branch]
#
# Auto-detects whether to run `git subtree add` (first import) or `git subtree pull`
# (subsequent updates), then records the resolved upstream commit SHA in
# third_party/inksdk/UPSTREAM.md so version provenance is greppable in the repo.
# Also renormalizes line endings so .gitattributes (CRLF for *.bat) is satisfied.

set -euo pipefail

REMOTE="https://github.com/imedwei/inksdk"
PREFIX="third_party/inksdk"
BRANCH="${1:-main}"

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "ERROR: tracked files have uncommitted changes. Commit or stash before syncing." >&2
  git status --short >&2
  exit 1
fi

if [[ -d "$PREFIX" ]]; then
  ACTION="pull"
else
  ACTION="add"
fi

echo "→ git subtree $ACTION --prefix=$PREFIX $REMOTE $BRANCH --squash"
git subtree "$ACTION" --prefix="$PREFIX" "$REMOTE" "$BRANCH" --squash

SHA="$(git ls-remote "$REMOTE" "$BRANCH" | awk '{print $1}')"
DATE="$(date -u +%Y-%m-%d)"

if [[ -z "$SHA" ]]; then
  echo "ERROR: could not resolve $REMOTE $BRANCH" >&2
  exit 1
fi

cat > "$PREFIX/UPSTREAM.md" <<EOF
# Upstream provenance

This directory is a \`git subtree --squash\` import of an external repository.
Do not hand-edit unless you also record the change here.

| Field  | Value |
|--------|-------|
| URL    | $REMOTE |
| Branch | $BRANCH |
| Commit | \`$SHA\` |
| Pulled | $DATE |

To sync with upstream, run \`./scripts/update-inksdk.sh [branch]\` from the repo root.
EOF

git add "$PREFIX/UPSTREAM.md"
git add --renormalize "$PREFIX"

if git diff --cached --quiet; then
  echo "✓ inksdk already at $SHA, no metadata changes"
else
  git commit -m "Pin inksdk to ${SHA:0:12} ($DATE)"
  echo "✓ inksdk synced to $SHA"
fi
