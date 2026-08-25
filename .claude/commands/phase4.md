---
description: Phase 4 — SQL generation and QuerySentry (NLQ, validation, ApprovedQuery)
---

Read applicable instructions, PRD NLQ/QuerySentry requirements, architecture sections 9/10/11, the entire threat model query/prompt sections, ADR-0005/0006/0007, and the test strategy.

Implement:

- Provider-neutral `SqlGenerationModel` port and deterministic fake.
- One configurable OpenAI-compatible HTTP adapter without leaking provider DTOs into domain/public contracts.
- Prompt/context builder with strict budgets and untrusted-data delimiters.
- Versioned structured response supporting READY, NEEDS_CLARIFICATION, and UNANSWERABLE.
- QuerySentry AST parse/validation for PostgreSQL and MySQL.
- Object resolution against authorized catalog, dangerous construct/function denial, complexity limits, server-owned limit policy, immutable `ApprovedQuery`, fingerprints, and stable rejections.
- Comprehensive unit/dialect/adversarial corpus tests.
- Preview REST contract; no execution yet.

Do not trust model confidence as authorization. Do not use regex as the primary SQL policy. Do not send credentials or raw rows to the model. Update `PLANS.md`, run all safety tests, and review for fail-open paths.
