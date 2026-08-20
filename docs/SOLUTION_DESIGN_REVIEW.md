# RivoQuery solution-design review

Status: Approved with required changes
Date: 2026-08-20
Reviewed artifact: `docs/SOLUTION_DESIGN.md`

## Verdict

No issue blocks the Phase 1 bootstrap, provided Phase 1 remains limited to the bootstrap prompt. Security-sensitive phases must satisfy the gates below. The required design changes have been incorporated into `docs/SOLUTION_DESIGN.md`; implementation tasks and evidence remain to be planned.

## Required findings and dispositions

1. **Catalog authorization order — high.** Canonicalize metadata into a transient dialect-aware form before authorization; evaluate rules against raw and canonical qualified identifiers; persist/index only authorized objects. Test PostgreSQL/MySQL quoting and case behavior.
2. **ApprovedQuery boundary — high.** Only a QuerySentry-owned private factory/capability may construct `ApprovedQuery`. Architecture tests prohibit all other construction and raw-SQL executor overloads.
3. **Preview replay/concurrency — high.** Use transactional `READY -> EXECUTING` compare-and-set, attempt/idempotency identity, stable replay/in-progress/conflict outcomes, expiry cleanup, and no automatic retry after ambiguous target failures. Direct execution uses the same internal capability.
4. **Outbound destinations — high.** Accept an ADR before Phase 2 selecting an enforceable DNS rebinding, vetted-address, pool, private-network, egress, and TLS-hostname mechanism for both target dialects.
5. **Terminal audit failure — high.** Commit accepted audit before target execution; persist terminal audit/outbox before returning rows; withhold rows on terminal persistence failure; retain reconciliation and alerts.
6. **Secret/pool capability ownership — high.** Bind operations to tenant, connection, purpose, and version; use closeable redacted leases; test confused-deputy access, compensation, rotation races, pool drain, provider failures, and telemetry leakage.
7. **State alignment — medium.** Keep public `SUCCEEDED` plus `truncated: true`, document complete transitions, and test invalid/concurrent transitions.
8. **Phase 1 minimalism — medium.** The future-state table inventory is not Phase 1 scope; Phase 1 creates only bootstrap persistence and platform foundations.
9. **Product/API decisions — medium.** Track workspace selection, query visibility, connection management, export, browser identity, MCP identity/transport, hosting, and residency as named phase-gated decisions.
10. **Parser/canonicalization — medium.** Accept an ADR before Phase 4 and prove timeout containment and semantic preservation across dialects, limits, CTEs, set operations, and quoted identifiers.
11. **Worker leasing — medium.** Define claims, leases, fencing, retries, idempotency, crash recovery, and shutdown before Phase 3.
12. **Sanity-runner workflow — medium.** Keep `sanity_runner` restricted to a runnable implemented product after normal automated verification; it is not a Phase 1 or generic pre-push reviewer.
13. **Verification gaps — medium.** Add migration-from-empty, composite-FK, identifier-normalization, double-submit, worker-fencing, encrypted-preview, terminal-audit, parser-nontermination, and secret-compensation tests.

## Phase gates

- Before Phase 2: identity/workspace decision, SecretStore capability semantics, and outbound-destination ADR.
- Before Phase 3: catalog canonicalization/authorization design and worker lease/fencing design.
- Before Phase 4: QuerySentry-only construction enforcement and parser/canonicalization ADR.
- Before Phase 5: preview persistence/replay ADR, aligned state machines, and terminal-audit semantics.
- Before Phases 6–8: export/browser identity, MCP identity/transport, and hosting/residency decisions at their consuming phase.

## Validated strengths

The design preserves the modular monolith, shared REST/MCP use cases, provider neutrality, bounded LLM context, deterministic fail-closed query policy, read-only connector controls, generated frontend client, transient browser secrets, bounded result rendering, privacy-safe telemetry, real-engine tests, and safe parallelism with serialized shared contracts and migrations.
