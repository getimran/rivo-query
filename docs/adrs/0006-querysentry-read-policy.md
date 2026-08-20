# ADR-0006: QuerySentry fail-closed read policy

Status: Accepted  
Date: 2026-08-20

## Context

Generated SQL cannot be trusted. Regex-only filtering is bypassable, while a model cannot be the authority judging its own output.

## Decision

Create QuerySentry as a deterministic AST-based policy engine. It accepts exactly one read query, resolves referenced objects against the authorized catalog, rejects denied constructs/functions, applies complexity and server limits, and returns an immutable `ApprovedQuery`. Execution additionally requires read-only DB credentials and session controls.

## Consequences

Positive: auditable defense in depth and a testable safety boundary.

Trade-off: valid advanced SQL may be rejected, parser/dialect gaps require maintenance, and safety cannot guarantee semantic correctness.

## Alternatives considered

- Regex/keyword filter: insufficient.
- Execute in a transaction and roll back: reads can still exfiltrate or exhaust resources; some operations are not safely contained.
- Trust model self-review: probabilistic and correlated failure.

## Validation

Dialect-specific malicious corpus with zero write/admin escapes, parser timeouts, read-only integration roles, and resource-limit tests.

