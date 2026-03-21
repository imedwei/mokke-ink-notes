#!/bin/bash
# scripts/rebuild-integration.sh
# Rebuilds the integration branch by merging all feature/fix/dev branches.
# Infers merge order from the existing integrate branch's history.
# Analyzes new branches to find the best insertion point.
# Uses Claude to auto-resolve merge conflicts when they occur.
#
# Usage:
#   ./scripts/rebuild-integration.sh [options] [base-branch]
#
# Options:
#   --dry-run           Print computed merge order without merging
#   --promote           Rotate integrate-next → integrate (with backup)
#   --exclude b1,b2     Comma-separated branches to skip
#   --skip-tests        Skip unit tests after each merge
#   --max-retries N     Max test-fix-retest cycles per merge (default: 3)
#
# The rebuild targets 'integrate-next', leaving 'integrate' intact.
# After verifying, run --promote to swap them.

set -euo pipefail

# ── Defaults ──────────────────────────────────────────────────────────
BASE="master"
DRY_RUN=false
PROMOTE=false
SKIP_TESTS=false
MAX_TEST_RETRIES=3
EXCLUDE=()

# ── Parse arguments ───────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)      DRY_RUN=true; shift ;;
    --promote)      PROMOTE=true; shift ;;
    --exclude)      IFS=',' read -ra EXCLUDE <<< "$2"; shift 2 ;;
    --skip-tests)   SKIP_TESTS=true; shift ;;
    --max-retries)  MAX_TEST_RETRIES="$2"; shift 2 ;;
    -*)             echo "Unknown option: $1"; exit 1 ;;
    *)              BASE="$1"; shift ;;
  esac
done

# ── Promote mode ──────────────────────────────────────────────────────
if $PROMOTE; then
  if ! git show-ref --verify --quiet refs/heads/integrate-next; then
    echo "ERROR: integrate-next does not exist. Nothing to promote."
    exit 1
  fi
  # Back up current integrate (overwrite old backup if it exists)
  if git show-ref --verify --quiet refs/heads/integrate; then
    git branch -M integrate integrate-backup
    echo "Demoted integrate → integrate-backup"
  fi
  git branch -M integrate-next integrate
  echo "Promoted integrate-next → integrate"
  exit 0
fi

# ── Helper: check if value is in EXCLUDE list ─────────────────────────
is_excluded() {
  local needle="$1"
  for ex in "${EXCLUDE[@]+"${EXCLUDE[@]}"}"; do
    [[ "$ex" == "$needle" ]] && return 0
  done
  return 1
}

# ── Read merge order from existing integrate branch ───────────────────
read_integrate_order() {
  if ! git show-ref --verify --quiet refs/heads/integrate; then
    return
  fi
  git log integrate --merges --reverse --format='%s' \
    | sed -n "s/^Merge branch '\([^']*\)'.*/\1/p"
}

# ── Discover all candidate branches ──────────────────────────────────
discover_branches() {
  git branch --format='%(refname:short)' | grep -E "^(feature|fix|dev)/"
}

# ── Analyze where a new branch fits among known branches ──────────────
# Returns the index (0-based) where the new branch should be inserted.
# Heuristics (checked in order):
#   1. Ancestry: if a known branch is a descendant of new, insert before it
#   2. File overlap: insert before the known branch with highest overlap
#   3. Default: append at end
analyze_insertion_point() {
  local new_branch="$1"
  shift
  local known=("$@")

  if [[ ${#known[@]} -eq 0 ]]; then
    echo 0
    return
  fi

  # Get files changed by the new branch relative to BASE
  local new_files
  new_files=$(git diff "$BASE"..."$new_branch" --name-only 2>/dev/null || true)
  if [[ -z "$new_files" ]]; then
    echo "${#known[@]}"
    return
  fi

  local best_idx=${#known[@]}
  local best_score=0

  for i in "${!known[@]}"; do
    local kb="${known[$i]}"

    # Ancestry: if known branch descends from new branch, insert before it
    if git merge-base --is-ancestor "$new_branch" "$kb" 2>/dev/null; then
      echo "$i"
      return
    fi

    # File overlap score
    local kb_files
    kb_files=$(git diff "$BASE"..."$kb" --name-only 2>/dev/null || true)
    if [[ -n "$kb_files" ]]; then
      local overlap
      overlap=$(comm -12 <(echo "$new_files" | sort) <(echo "$kb_files" | sort) | wc -l)
      if [[ $overlap -gt $best_score ]]; then
        best_score=$overlap
        best_idx=$i
      fi
    fi
  done

  # If we found overlap, insert before that branch
  if [[ $best_score -gt 0 ]]; then
    echo "$best_idx"
  else
    echo "${#known[@]}"
  fi
}

# ── Compute final merge order ─────────────────────────────────────────
compute_merge_order() {
  local -a known_order=()
  local -a all_branches=()
  local -a new_branches=()

  # Read known order from integrate history
  while IFS= read -r b; do
    [[ -n "$b" ]] && known_order+=("$b")
  done < <(read_integrate_order)

  # Discover all current branches
  while IFS= read -r b; do
    [[ -n "$b" ]] && all_branches+=("$b")
  done < <(discover_branches)

  # Filter known_order: keep only branches that still exist and aren't excluded
  local -a filtered_known=()
  for b in "${known_order[@]+"${known_order[@]}"}"; do
    if is_excluded "$b"; then
      echo "  Excluding: $b"
      continue
    fi
    # Check branch still exists
    local found=false
    for ab in "${all_branches[@]}"; do
      if [[ "$ab" == "$b" ]]; then
        found=true
        break
      fi
    done
    if $found; then
      filtered_known+=("$b")
    else
      echo "  Warning: $b (from integrate history) no longer exists, skipping"
    fi
  done

  # Find new branches (exist locally but not in known order)
  for ab in "${all_branches[@]}"; do
    if is_excluded "$ab"; then
      echo "  Excluding: $ab"
      continue
    fi
    local in_known=false
    for kb in "${known_order[@]+"${known_order[@]}"}"; do
      if [[ "$ab" == "$kb" ]]; then
        in_known=true
        break
      fi
    done
    if ! $in_known; then
      new_branches+=("$ab")
    fi
  done

  # Start with known order
  local -a result=("${filtered_known[@]+"${filtered_known[@]}"}")

  # Insert new branches at analyzed positions
  for nb in "${new_branches[@]+"${new_branches[@]}"}"; do
    local idx
    idx=$(analyze_insertion_point "$nb" "${result[@]+"${result[@]}"}")
    echo "  New branch: $nb → inserting at position $((idx + 1))"
    # Insert at index
    local -a tmp=()
    for ((i = 0; i < ${#result[@]}; i++)); do
      [[ $i -eq $idx ]] && tmp+=("$nb")
      tmp+=("${result[$i]}")
    done
    [[ $idx -ge ${#result[@]} ]] && tmp+=("$nb")
    result=("${tmp[@]}")
  done

  # If no integrate history existed, note we're in analysis-only mode
  if [[ ${#known_order[@]} -eq 0 && ${#result[@]} -gt 0 ]]; then
    echo "  Note: no integrate history found, using analysis-only ordering"
  fi

  printf '%s\n' "${result[@]}"
}

# ── Compute the order ─────────────────────────────────────────────────
echo "Computing merge order..."
ORDER=()
while IFS= read -r line; do
  # Lines starting with spaces are status messages, print them
  if [[ "$line" == " "* ]]; then
    echo "$line"
  elif [[ -n "$line" ]]; then
    ORDER+=("$line")
  fi
done < <(compute_merge_order)

if [[ ${#ORDER[@]} -eq 0 ]]; then
  echo "No branches to merge."
  exit 0
fi

echo ""
echo "Merge order:"
for i in "${!ORDER[@]}"; do
  echo "  $((i + 1)). ${ORDER[$i]}"
done
echo ""

# ── Dry run: stop here ────────────────────────────────────────────────
if $DRY_RUN; then
  echo "(dry run — no merges performed)"
  exit 0
fi

# ── Guard: integrate-next must not already exist ──────────────────────
if git show-ref --verify --quiet refs/heads/integrate-next; then
  echo "ERROR: integrate-next already exists."
  echo "Either promote it:  $0 --promote"
  echo "Or delete it:       git branch -D integrate-next"
  exit 1
fi

# ── Build integrate-next ──────────────────────────────────────────────
git checkout -B integrate-next "$BASE"

for branch in "${ORDER[@]}"; do
  echo "Merging $branch..."
  if ! git merge "$branch" --no-edit; then
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
      -p "You are resolving merge conflicts on the integrate-next branch.
We are merging branch '$branch' into integrate-next (based on '$BASE').
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
  fi

  # ── Run unit tests after merge (unless --skip-tests) ──────────────
  if ! $SKIP_TESTS; then
    echo "Running unit tests after merging $branch..."
    retry=0
    while true; do
      test_output=$(./gradlew testDebugUnitTest 2>&1) && break

      retry=$((retry + 1))
      if [[ $retry -gt $MAX_TEST_RETRIES ]]; then
        echo "ERROR: Tests still failing after $MAX_TEST_RETRIES fix attempts for $branch."
        echo "Last test output:"
        echo "$test_output" | tail -40
        exit 1
      fi

      echo "Tests failed after merging $branch (attempt $retry/$MAX_TEST_RETRIES) — asking Claude to fix..."

      # Extract just the failure summary for Claude
      test_failures=$(echo "$test_output" | grep -A 2 -E "(FAILED|FAILURE|> Task.*FAILED)" | head -60)

      claude --print \
        --allowedTools 'Read,Edit,Bash(git add *),Bash(./gradlew testDebugUnitTest*)' \
        -p "Unit tests are failing on the integrate-next branch after merging '$branch'.

Test failure output:
$test_failures

Full output (last 80 lines):
$(echo "$test_output" | tail -80)

Fix the test failures. These are likely caused by the merge of '$branch' interacting
with previously merged branches. Read the failing test files and the source code they
test, identify the issue, fix it, and stage the changes with git add.

Do NOT commit. Just fix and stage."

      # Stage and commit fixes
      if ! git diff --cached --quiet; then
        git commit -m "Fix test failures after merging $branch"
        echo "Committed test fixes for $branch."
      fi
    done
    echo "Tests pass after merging $branch."
  fi
done

echo ""
echo "Integration branch 'integrate-next' is ready."
echo "Verify it, then run:"
echo "  $0 --promote   to replace integrate"
