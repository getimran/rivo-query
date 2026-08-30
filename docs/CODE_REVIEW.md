# Code review rules

Review correctness and security before style.

## Pre-PR requirement

Code review is mandatory before raising, updating, or merging any pull request.
Every PR must be reviewed for correctness, security, architecture, and test
coverage before it is opened, re-opened, or merged. Issues identified during
review must be fixed in the same branch before merge; do not defer review
findings to follow-up PRs unless explicitly approved. No code may be pushed to
a shared branch or merged without completing this review step.

## Blocking findings

- Any path allowing raw SQL or model output to reach execution without QuerySentry approval.
- Any credential, token, raw connection URL, decrypted secret, prompt, or result row written to logs/traces unexpectedly.
- Missing tenant/connection authorization or repository query not scoped by tenant.
- A relaxation of read-only, timeout, row, byte, complexity, or concurrency limits.
- Client-controlled driver properties, JDBC URL fragments, policy versions, or approved SQL.
- Model/provider DTOs leaking into domain contracts.
- Returning raw driver errors or stack traces.
- Tests that use mocks where real PostgreSQL/MySQL behavior is the subject under test.

## Review checklist

- Does the change match the PRD and active plan?
- Is the dependency direction preserved?
- Are inputs bounded and validated at the correct boundary?
- Are failure modes explicit and fail-closed?
- Are secrets and sensitive data minimized and redacted?
- Are operations cancellable and resources closed?
- Are database-dialect differences tested?
- Are REST and MCP policies consistent?
- Are metrics/audit useful without leaking data?
- Are API and migration changes backward compatible and documented?
- Do tests cover success, denial, timeout, malformed data, and cross-tenant cases?

## Safe review outcome

A review should state checks actually run, checks not run, remaining risks, and whether an ADR/document update is needed.

