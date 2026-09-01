# ADR-0014: QuerySentry parser/canonicalization

Status: Accepted
Date: 2026-08-30

## Context

QuerySentry must parse generated SQL proposals into an AST, validate them against deterministic policy, transform limits, and serialize canonical SQL. The parser must support PostgreSQL and MySQL dialects, contain nontermination risk, preserve query semantics, and reject unsupported syntax safely.

Alternatives considered:
- Regex/keyword filter: insufficient by design (already rejected in ADR-0006).
- Vendor-native parsing: would couple QuerySentry to specific JDBC drivers and complicate testing.
- Apache Calcite: powerful but heavy; introduces a large dependency surface, slower parse/transform cycle, and complex validation model that obscures the containment boundary.
- JSQLParser: lightweight, supports both target dialects, provides parse/validate/transform/reparse cycles, and keeps the containment boundary explicit.

## Decision

Adopt JSQLParser (`com.github.jsqlparser:jsqlparser`) as the QuerySentry parser/canonicalizer. Wrap it behind a `SqlParserPort` inside `querypolicy/`. The port exposes dialect-aware parsing, statement classification, AST traversal, transformation, and serialization. All dialect-specific behavior is isolated in `querypolicy` implementations; the rest of the system sees only the port.

Parser behavior and containment rules:

- Deadline: parsing and each reparse must complete within 500 ms or the proposal is rejected as a policy failure.
- Input size: proposals larger than 64 KiB are rejected before parsing.
- Encoding: only UTF-8 input is accepted; invalid encoding is rejected.
- Statement count: exactly one statement is accepted; stacked statements are rejected.
- Statement types: only `SELECT` and `WITH` (non-modifying CTEs ending in `SELECT`) are accepted.
- Rejected constructs: `INSERT`, `UPDATE`, `DELETE`, `MERGE`, `DDL`, administrative commands (`SET`, `COMMIT`, `ROLLBACK`, `SAVEPOINT`), locking hints, `SELECT INTO`, dynamic SQL, `EXECUTE`, `CALL`, file/network functions, sleep/pg_sleep, system-inspection functions, and cross-database/cross-schema references not present in the authorized catalog.
- Complexity: the AST must contain no more than 50 table references, 200 columns, 10 joins, and 5 CTE levels. Proposals exceeding these limits are rejected.
- Semantic preservation: AST transformations add or reduce row limits only. No other rewrite is performed. After transformation, the SQL is serialized, reparsed, and revalidated under the same containment deadline. If reparsing fails or produces a different statement type, the proposal is rejected.
- Quoted identifiers: preserved verbatim. Canonicalization does not alter quoted names.
- CTEs and set operations: supported within the stated limits. `UNION`, `INTERSECT`, and `EXCEPT` are allowed only when every branch satisfies the same policy. Recursive CTEs are rejected.
- Dialect differences: dialect-specific syntax, functions, and types are accepted only when they map to the target dialect's approved feature set. Unknown dialect features are rejected rather than silently dropped.

ApprovedQuery lifecycle:

1. `QuerySentry` receives a proposal, authorized catalog slice, dialect, and policy version.
2. The parser validates input size, encoding, and statement count.
3. The AST is classified and checked against rejected constructs and complexity limits.
4. Every referenced object is resolved against the authorized catalog slice; unresolved objects are rejected.
5. Row limits are enforced through AST transformation (`LIMIT` or `TOP` insertion).
6. The transformed SQL is serialized, reparsed, and revalidated.
7. An immutable `ApprovedQuery` is created containing dialect, canonical SQL/fingerprint, referenced objects, risks, enforced limits, and policy version.

Consequences:

Positive: explicit containment boundary, testable parser behavior, deterministic validation, and clear rejection semantics.

Trade-off: valid advanced SQL may be rejected if JSQLParser lacks dialect-specific parse coverage. Unsupported syntax must be tracked as a parser gap and resolved by policy or parser update rather than by weakening validation.

Alternatives considered:

- Regex/keyword filter: insufficient.
- Execute in a transaction and roll back: reads can still exfiltrate or exhaust resources; some operations are not safely contained.
- Trust model self-review: probabilistic and correlated failure.
- Vendor-native parsing: couples policy to drivers and complicates testing.
- Apache Calcite: heavy dependency surface and slower transformation cycle.

Validation

- Dialect-specific adversarial corpus with zero write/admin escapes.
- Parser timeout and nontermination containment tests.
- Round-trip serialization/reparse/revalidation tests for transformed and untransformed SQL.
- Complexity-limit rejection tests for tables, columns, joins, CTEs, and set operations.
- Quoted-identifier and case-sensitivity preservation tests.
- PostgreSQL and MySQL integration tests using real-engine metadata and types.
