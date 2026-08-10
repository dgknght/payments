---
name: fix-ci
description: Read GitHub Actions failures for the current PR and fix them.
user-invocable: true
disable-model-invocation: true
---

# Fix CI failures

Read the GitHub Actions failures for the current PR and fix them.

Steps:

1. Run `gh pr view --json number,headRefName` to confirm the current PR and branch.
2. Run `.claude/scripts/ci-failures.sh` to get the failure output for the most
   recent failed run (see the `gh-actions-failures` skill for details).
3. Analyze the failures and identify the root cause(s).
4. Fix the issues in the code.
5. Run the relevant tests locally to confirm the fix (use `lein ptest <namespace>`
   for the smallest subset that covers the failure).
6. Run `clj-kondo --lint src` and resolve any warnings before committing.
7. Commit the fix with a descriptive message.
