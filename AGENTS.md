# Repository Instructions

## Verification

- After every requested modification, inspect the resulting diff and run checks that are appropriate for the changed behavior before considering the work complete.
- For source, test, configuration, or build changes, run `.\mvnw.cmd verify` from the repository root unless a narrower check is clearly more appropriate. Add focused tests when the change introduces or fixes behavior.
- For documentation-only changes, at minimum run `git diff --check` and review the rendered content or final file for correctness.
- If a check fails, investigate and fix the failure, then rerun the relevant checks. Do not commit while known bugs or failing checks remain. If a failure cannot be resolved, report it instead of creating a commit.

## Git Workflow

- Once verification succeeds, automatically create a Git commit for the completed modification without waiting for a separate request.
- Review `git status` and the staged diff before committing. Stage only files changed for the current request; preserve unrelated user changes and untracked files.
- Use a concise commit message that describes the completed change.
- Never push commits or branches unless the user explicitly requests a push.
