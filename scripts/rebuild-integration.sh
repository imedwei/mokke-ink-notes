#!/bin/bash
# scripts/rebuild-integration.sh
# Rebuilds the integrate branch by merging all feature/fix/dev branches.
# Uses Claude to auto-resolve merge conflicts when they occur.
#
# Usage: ./scripts/rebuild-integration.sh [base-branch]
#   base-branch: branch to start from (default: master)

set -euo pipefail
BASE="${1:-master}"

git branch -D integrate 2>/dev/null || true
git checkout -B integrate "$BASE"

for branch in $(git branch --format='%(refname:short)' | grep -E "^(feature|fix|dev)/"); do
  echo "Merging $branch..."
  if git merge "$branch" --no-edit; then
    continue
  fi

  echo "Conflict merging $branch — asking Claude to resolve..."
  conflicted=$(git diff --name-only --diff-filter=U)

  if [ -z "$conflicted" ]; then
    echo "ERROR: merge failed but no conflicted files found. Aborting."
    git merge --abort
    exit 1
  fi

  echo "Conflicted files:"
  echo "$conflicted"

  # Ask Claude to resolve each conflicted file
  claude --print \
    --allowedTools 'Read,Edit,Bash(git add *)' \
    -p "You are resolving merge conflicts on the integrate branch.
We are merging branch '$branch' into integrate (based on '$BASE').
The following files have conflicts:
$conflicted

For each file:
1. Read it to see the conflict markers (<<<<<<< ======= >>>>>>>)
2. Resolve by keeping the intent of BOTH sides — do not drop changes from either branch
3. Remove all conflict markers
4. Run: git add <file>

Do NOT commit. Just resolve and stage."

  # Verify no conflicts remain
  remaining=$(git diff --name-only --diff-filter=U)
  if [ -n "$remaining" ]; then
    echo "ERROR: Claude failed to resolve all conflicts. Remaining:"
    echo "$remaining"
    git merge --abort
    exit 1
  fi

  git commit --no-edit
  echo "Resolved and committed merge of $branch."
done

echo "Integration branch ready."
