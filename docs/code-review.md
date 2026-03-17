# Local Code Review

Run Claude Code reviews locally using your already-authenticated `claude` CLI, then optionally post results to a PR. No CI secrets or OAuth tokens needed.

## Prerequisites

- `claude` CLI installed and logged in
- `gh` CLI authenticated with your GitHub account

## Interactive use

```bash
# Review local changes (prompts to post if a PR exists)
./scripts/review-pr.sh

# Review a specific PR
./scripts/review-pr.sh 42

# Check addressed items
./scripts/review-check.sh <review-file> [pr-number]
```

## Non-interactive / agent use

Both scripts accept `--local`, `--post`, and `--no-post` flags:

```bash
# Review local branch diff, no remote needed
REVIEW=$(./scripts/review-pr.sh --local --no-post)

# After fixing issues, verify locally
./scripts/review-check.sh --local --no-post "$REVIEW"

# Once ready, create PR and post results
gh pr create --draft
./scripts/review-pr.sh --post
./scripts/review-check.sh --post "$REVIEW"
```

## Automated PR cycle (used by Claude agent)

The full self-review cycle is documented in `CLAUDE.md` under **PR Workflow**.
Claude will automatically:

1. Review local changes with `--local --no-post` (no remote needed)
2. Address all actionable items found
3. Verify fixes with `review-check.sh --local --no-post`
4. Iterate until all items are resolved
5. Create the PR and post the final review + checklist
6. Mark the PR as ready
