#!/usr/bin/env bash
# Fetch and display CI failure logs for the current (or specified) branch.
# Usage: ./scripts/ci-failures.sh [branch]

set -euo pipefail

BRANCH="${1:-$(git rev-parse --abbrev-ref HEAD)}"

echo "Branch: $BRANCH"

RUN_ID=$(gh run list --branch "$BRANCH" --limit 10 --json databaseId,status,conclusion \
  --jq '[.[] | select(.conclusion == "failure")] | first | .databaseId')

if [[ -z "$RUN_ID" || "$RUN_ID" == "null" ]]; then
  echo "No failed runs found for branch: $BRANCH"
  exit 0
fi

echo "Run ID: $RUN_ID"
echo "---"

gh run view "$RUN_ID" --log-failed 2>&1 \
  | grep -v -E "Retrieving .+\.(pom|jar) from" \
  | grep -v "^$"
