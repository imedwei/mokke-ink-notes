# Integration Branch Rebuild

The `scripts/rebuild-integration.sh` script merges all `feature/`, `fix/`, and `dev/` branches into a single integration branch for testing. It infers the correct merge order, resolves conflicts with Claude, and validates each merge with unit tests.

## Quick Reference

```bash
# Preview the merge order without doing anything
./scripts/rebuild-integration.sh --dry-run

# Full rebuild (merges into integrate-next, runs tests after each merge)
./scripts/rebuild-integration.sh

# Skip tests for a faster rebuild
./scripts/rebuild-integration.sh --skip-tests

# Exclude specific branches
./scripts/rebuild-integration.sh --exclude feature/experimental,dev/wip

# After verifying integrate-next, promote it
./scripts/rebuild-integration.sh --promote

# Use a different base branch
./scripts/rebuild-integration.sh some-other-base
```

## How Merge Order Is Determined

### When an `integrate` branch exists (warm start)

The script reads the merge commit messages from `integrate`'s history before building. The order is replayed exactly. Branches that have been amended with new commits are still recognized by name.

New branches not in the history are inserted using the analysis heuristics below.

### When no `integrate` branch exists (cold start)

All branches are sorted by two criteria:

1. **Merge-base age** (primary) -- branches forked from an older point in master merge first. This ensures prerequisite branches (whose changes are already baked into later branches' fork points) go before their dependents.
2. **File count** (secondary) -- within the same fork point, branches touching fewer files merge first. Simpler changes establish a clean base for more complex ones.

## Staged Rebuild: integrate-next / integrate / integrate-backup

The script never modifies `integrate` directly during a rebuild:

1. Reads merge order from existing `integrate` (if present)
2. Creates `integrate-next` from the base branch (default: `master`)
3. Merges each branch into `integrate-next`
4. Developer verifies `integrate-next` (build, test, inspect)
5. `--promote` rotates: `integrate` becomes `integrate-backup`, `integrate-next` becomes `integrate`

To discard a bad rebuild: `git branch -D integrate-next`. The previous `integrate` is untouched.

## Test Validation

After each merge, the script runs `./gradlew testDebugUnitTest`. If tests fail:

1. Claude reads the failure output and fixes the code
2. Fixes are committed as "Fix test failures after merging \<branch\>"
3. Tests are re-run (up to `--max-retries`, default 3)
4. If all retries fail, the script exits with an error

Use `--skip-tests` to disable this (faster but no validation).

## Conflict Resolution

When a merge produces conflicts, Claude is invoked with access to `Read`, `Edit`, and `git add`. It resolves each conflicted file by keeping intent from both sides, removes conflict markers, and stages the result. If Claude cannot resolve all conflicts, the merge is aborted and the script exits.

## Automation Usage

To run this script from another Claude session or automation:

```bash
# Dry run to check order
./scripts/rebuild-integration.sh --dry-run

# Full rebuild (will invoke Claude sub-processes for conflicts and test fixes)
./scripts/rebuild-integration.sh

# Promote after manual verification
./scripts/rebuild-integration.sh --promote
```

The script exits 0 on success, 1 on failure. Key failure modes:
- `integrate-next` already exists (promote or delete it first)
- Claude fails to resolve conflicts (manual intervention needed)
- Tests fail after max retries (manual fix needed on the feature branch)
