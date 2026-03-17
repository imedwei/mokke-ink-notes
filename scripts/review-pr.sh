#!/usr/bin/env bash
# Run Claude Code review on the current branch's changes.
# Usage: ./scripts/review-pr.sh [--post] [--no-post] [--local] [pr-number]
#
# Options:
#   --local    Review local diff against master (no PR or remote needed)
#   --post     Post review to PR without prompting (non-interactive)
#   --no-post  Save review locally without posting (non-interactive)
#   (default)  --local if no PR exists, prompts to post if PR exists
#
# Review output is saved to .claude/reviews/<branch>-<timestamp>.md
# Prints the review file path as the last line of stdout.

set -euo pipefail

POST_MODE=""
LOCAL_MODE=""
PR=""

for arg in "$@"; do
  case "$arg" in
    --post)    POST_MODE="yes" ;;
    --no-post) POST_MODE="no" ;;
    --local)   LOCAL_MODE="yes" ;;
    *)         PR="$arg" ;;
  esac
done

BRANCH=$(git rev-parse --abbrev-ref HEAD)
BASE_BRANCH="master"

# Determine if we're working locally or with a PR
if [ "$LOCAL_MODE" = "yes" ]; then
  PR=""
elif [ -z "$PR" ]; then
  PR=$(gh pr view --json number -q .number 2>/dev/null) || {
    LOCAL_MODE="yes"
  }
fi

REVIEW_DIR=".claude/reviews"
mkdir -p "$REVIEW_DIR"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
SAFE_BRANCH=$(echo "$BRANCH" | tr '/' '-')
REVIEW_FILE="$REVIEW_DIR/${SAFE_BRANCH}-${TIMESTAMP}.md"

# Get the diff
if [ "$LOCAL_MODE" = "yes" ]; then
  echo "Reviewing local branch '${BRANCH}' against '${BASE_BRANCH}'..." >&2
  # Include committed + staged + unstaged changes vs base branch
  DIFF=$(git diff "${BASE_BRANCH}")
  CONTEXT="Local branch: ${BRANCH} (vs ${BASE_BRANCH})"
else
  echo "Reviewing PR #${PR} on branch '${BRANCH}'..." >&2
  DIFF=$(gh pr diff "$PR")
  CONTEXT="PR #${PR} on branch: ${BRANCH}"
fi

if [ -z "$DIFF" ]; then
  echo "No changes found to review." >&2
  exit 0
fi

REPO_NAME=$(basename "$(git rev-parse --show-toplevel)")

# Use printf to avoid shell interpretation of diff content
{
  printf 'PROJECT: %s\n%s\n\nHere is the diff:\n\n```diff\n' "$REPO_NAME" "$CONTEXT"
  printf '%s\n' "$DIFF"
  printf '```\n\n'
  printf '%s\n' \
    "Please review these changes and provide feedback on:" \
    "- Code quality and best practices" \
    "- Potential bugs or issues" \
    "- Performance considerations" \
    "- Security concerns" \
    "- Test coverage" \
    "" \
    "Format your review as a markdown document with sections for each concern found." \
    "If everything looks good, say so briefly." \
    "Do NOT post any GitHub comments — just output the review text."
} | claude --print --output-format text > "$REVIEW_FILE"

echo "Review saved to ${REVIEW_FILE}" >&2

# Post to PR if applicable and requested
if [ -n "$PR" ]; then
  if [ -z "$POST_MODE" ]; then
    read -r -p "Post review to PR #${PR}? [y/N] " POST_MODE
    [[ "$POST_MODE" =~ ^[Yy] ]] && POST_MODE="yes" || POST_MODE="no"
  fi

  if [ "$POST_MODE" = "yes" ]; then
    BODY=$(cat "$REVIEW_FILE")
    gh pr comment "$PR" --body "## Claude Code Review

${BODY}

---
*Local review via \`scripts/review-pr.sh\`*"
    echo "Posted review to PR #${PR}." >&2
  fi
elif [ "$POST_MODE" = "yes" ]; then
  echo "Warning: --post ignored, no PR exists for this branch." >&2
fi

# Output the review file path (for piping to review-check.sh)
echo "$REVIEW_FILE"
