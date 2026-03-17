#!/usr/bin/env bash
# Check which review items have been addressed by subsequent changes.
# Usage: ./scripts/review-check.sh [--post] [--no-post] [--local] <review-file> [pr-number]
#
# Options:
#   --local    Compare against local diff (no PR needed)
#   --post     Post update to PR without prompting (non-interactive)
#   --no-post  Save update locally without posting (non-interactive)
#   (default)  Prompt whether to post

set -euo pipefail

POST_MODE=""
LOCAL_MODE=""
POSITIONAL=()

for arg in "$@"; do
  case "$arg" in
    --post)    POST_MODE="yes" ;;
    --no-post) POST_MODE="no" ;;
    --local)   LOCAL_MODE="yes" ;;
    *)         POSITIONAL+=("$arg") ;;
  esac
done

REVIEW_FILE="${POSITIONAL[0]:?Usage: review-check.sh [options] <review-file> [pr-number]}"
PR="${POSITIONAL[1]:-}"

if [ ! -f "$REVIEW_FILE" ]; then
  echo "Error: review file not found: ${REVIEW_FILE}" >&2
  exit 1
fi

BASE_BRANCH="master"

# Determine if we're working locally or with a PR
if [ "$LOCAL_MODE" = "yes" ]; then
  PR=""
elif [ -z "$PR" ]; then
  PR=$(gh pr view --json number -q .number 2>/dev/null) || {
    LOCAL_MODE="yes"
  }
fi

# Get the current diff
if [ "$LOCAL_MODE" = "yes" ] || [ -z "$PR" ]; then
  # Include committed + staged + unstaged changes vs base branch
  DIFF=$(git diff "${BASE_BRANCH}")
else
  DIFF=$(gh pr diff "$PR")
fi

REVIEW=$(cat "$REVIEW_FILE")
REPO_NAME=$(basename "$(git rev-parse --show-toplevel)")

echo "Checking which review items have been addressed..." >&2

# Use printf to avoid shell interpretation of diff/review content
UPDATE=$({
  printf 'You were given this code review for %s:\n\n' "$REPO_NAME"
  printf '%s\n' "$REVIEW"
  printf '\nHere is the current diff after the author made changes:\n\n```diff\n'
  printf '%s\n' "$DIFF"
  printf '```\n\n'
  printf '%s\n' \
    "For each item in the original review, determine whether it has been addressed" \
    "by the current changes. Output a checklist in markdown:" \
    "" \
    "- [x] Item — addressed (brief explanation)" \
    "- [ ] Item — still open (brief explanation)" \
    "" \
    "Be concise. Only list items that were actual action items from the review."
} | claude --print --output-format text)

echo "$UPDATE" >&2

# Post to PR if applicable
if [ -n "$PR" ]; then
  if [ -z "$POST_MODE" ]; then
    read -r -p "Post update to PR #${PR}? [y/N] " POST_MODE
    [[ "$POST_MODE" =~ ^[Yy] ]] && POST_MODE="yes" || POST_MODE="no"
  fi

  if [ "$POST_MODE" = "yes" ]; then
    gh pr comment "$PR" --body "## Review Update

${UPDATE}

---
*Checked via \`scripts/review-check.sh\`*"
    echo "Posted update to PR #${PR}." >&2
  fi
elif [ "$POST_MODE" = "yes" ]; then
  echo "Warning: --post ignored, no PR exists for this branch." >&2
fi
