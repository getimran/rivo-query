# Test and evaluation strategy

## 1. Goals

Testing must prove safety boundaries, result correctness, tenant isolation, connector compatibility, and operational limits. Exact generated SQL is usually not the correctness oracle; the expected result set and policy outcome are.

## 2. Test layers

### Unit tests

- Value-object validation and redaction
- Destination policy and host/IP classification
- Catalog normalization and fingerprinting
- Schema relevance scoring and budgets
- Structured model-output parsing
- QuerySentry AST policy, object resolution, complexity, and limit enforcement
- Result type normalization
- Permission decisions and problem-code mapping

### Architecture tests

Use an architecture-testing library to enforce module boundaries, forbidden dependencies, package cycles, and the rule that only QuerySentry can construct `ApprovedQuery`.

### Integration tests

Use Testcontainers with real PostgreSQL and MySQL engines for:

- Connection and TLS option behavior where practical
- Metadata discovery differences
- Read-only transaction/session behavior
- Type normalization
- Timeout, cancellation, row limit, and pool eviction
- Schema changes and refresh fingerprints
- Dialect-specific accepted and rejected SQL

### Model contract tests

Use a fake deterministic adapter for most tests and an HTTP stub for provider transport behavior:

- Valid structured response
- Clarification and unanswerable responses
- Malformed JSON/schema
- Timeout, rate limit, server error, and oversized output
- Prompt/version metadata
- No secret or disallowed schema content in outbound request snapshots

Live-provider tests are opt-in, non-blocking for normal local builds, cost-bounded, and never use sensitive databases.

### API contract tests

- OpenAPI request/response validation
- RFC 9457 safe errors
- Idempotency and optimistic conflicts
- Authentication/authorization matrix
- No write-only secret fields in responses
- REST and MCP behavioral parity

### Frontend tests

- Component tests for connection forms, secret handling, preview, results, warnings, and errors
- Typed-client integration tests
- Accessibility checks
- End-to-end happy path and failure paths with Playwright
- Browser storage inspection to ensure secrets/results are not persisted unexpectedly

## 3. Text-to-SQL evaluation

Maintain a versioned `evals/` dataset containing:

- Database fixture and schema version
- Natural-language question
- Optional clarification dialogue
- Allowed connection objects
- Expected semantic result or invariant
- Expected policy outcome
- Tags for joins, dates, aggregation, ambiguity, dialect, and difficulty

Metrics:

- Execution accuracy: result semantics match expected.
- Valid-SQL rate.
- Clarification precision/recall.
- Unsafe-query escape rate.
- Policy false-positive rate.
- Schema selection recall.
- Latency and tokens by stage.

Never optimize solely for exact SQL match; equivalent SQL is valid.

## 4. Security corpus

Keep a separate adversarial corpus for prompt injection, SQL obfuscation, DML/DDL nesting, denied functions, comments, Unicode, cross-schema access, system catalogs, delay/resource exhaustion, and result-exfiltration attempts.

Release rule: zero accepted write/administrative statements. Any discovered bypass becomes a permanent regression case.

## 5. Performance tests

Scenarios:

- Concurrent queries across many tenants/connections
- Hot and cold schema cache
- Slow/failed LLM provider
- Slow/failed target database
- Pool saturation and replica scaling
- Large row/cell values hitting payload caps
- Schema refresh concurrent with queries

Report P50/P95/P99 latency by stage, throughput, rejection rate, CPU/memory, pool usage, cancellation effectiveness, and target connection count.

## 6. CI stages

1. Format, static analysis, unit, architecture, and secret scan.
2. PostgreSQL/MySQL integration and API contract tests.
3. Frontend lint, unit, build, accessibility smoke.
4. End-to-end test on ephemeral stack.
5. Dependency/container/IaC scan.
6. QuerySentry adversarial corpus and deterministic evaluation subset.
7. Scheduled full evaluation and performance suite.

## 7. Test data rules

- Use synthetic fixtures only.
- Never copy production schemas containing sensitive comments without review.
- Never commit real connection strings or access tokens.
- Use distinct read-only and intentionally overprivileged test identities to verify warnings and controls.

