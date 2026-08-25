---
description: Phase 5 — bounded execution and audit (ApprovedQuery execution only)
---

Read applicable instructions, PRD execution/result/audit requirements, architecture sections 11/12/17, security invariants, ADR-0006/0008/0011, operations, test strategy, and Phase 5 in `PLANS.md`.

Implement execution accepting only `ApprovedQuery`:

- Reauthorization and preview-state/version/fingerprint checks.
- Just-in-time secret resolution and bounded target pool use.
- Read-only connection plus database/session controls per dialect.
- Timeout, cancellation, max rows, max bytes, max columns/cell length, and typed result normalization.
- Safe errors with no raw driver details.
- Metadata-only append-only audit and feedback.
- REST execution endpoint and integration tests on PostgreSQL/MySQL for writes denied, timeout, cancellation, truncation, pool cleanup, and cross-tenant denial.

Do not persist rows or send them to the LLM. Update the plan, run checks, and explicitly verify each security invariant touched.
