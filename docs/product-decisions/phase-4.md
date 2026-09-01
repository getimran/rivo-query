# Phase 4 product decisions: generation, QuerySentry, and preview

Status: APPROVED

Decision owner: Product owner

Recorded: 2026-08-30

Applies to: Phase 4 generation, QuerySentry parser/canonicalization, and preview/execution contracts

## Context and authority

The PRD requires a provider-neutral LLM adapter, deterministic AST-based QuerySentry validation, bounded read-only execution, and explicit schema visibility controls. ADR-0005 selects a narrow `SqlGenerationModel` port with one configurable OpenAI-compatible HTTP adapter. ADR-0006 makes QuerySentry the sole authority for creating `ApprovedQuery`. ADR-0010 requires REST and MCP to share the same application use cases and policy outcomes.

Phase 4 must resolve three product decisions before implementation begins:
1. The reference OpenAI-compatible adapter configuration shipped for examples and local development.
2. Provider retention defaults for model requests and responses.
3. Whether a `QUERY` permission implicitly permits schema/object visibility in an approved preview.

## Decisions

### PD4-001: reference OpenAI-compatible adapter configuration

The reference implementation uses a single OpenAI-compatible HTTP adapter configured through application properties. The adapter is a thin, deterministic wrapper over the `SqlGenerationModel` port. It sends only the generation request fields defined by the port and returns structured proposals without exposing provider-specific details in public contracts.

Configuration defaults for local development and examples:

```yaml
rivoquery:
  generation:
    adapter:
      type: openai-compatible
      base-url: http://localhost:11434/v1
      model: qwen2.5:3b
      api-key: ""
      timeout: 30s
      max-retries: 1
      temperature: 0.1
      top-p: 0.9
```

Production deployments override `base-url`, `model`, `api-key`, and `timeout` through environment-specific configuration. The adapter rejects any request that does not fit the port contract and treats provider errors as generation failures; it never retries with looser constraints or returns raw provider messages to callers.

Alternatives such as hard-coded provider SDKs, LangChain-style orchestration frameworks, or self-hosted model defaults were rejected because they leak provider concerns into the domain, hide prompt/retry behavior, or create infrastructure burden before quality requirements are validated.

### PD4-002: provider retention defaults

By default, the system retains only the minimum provider data required for audit, debugging, and evaluation:

- **Prompt/context**: retained only in structured audit metadata as a bounded fingerprint/hash, never as raw text.
- **Model response**: retained only as a bounded proposal fingerprint plus the structured fields (SQL, explanation, assumptions, referenced objects, ambiguity status, confidence). Raw provider JSON, token usage, and latency are retained only as low-cardinality metrics.
- **Expiry**: prompt/response artifacts are short-lived and removed after the configured retention window unless an explicit audit or evaluation hold applies.

The default retention window is 7 days for generation artifacts and 90 days for audit metadata. Operators may configure shorter or longer retention within platform limits, but raw model prompts and responses are never persisted beyond the evaluation window without explicit product-owner approval.

This default satisfies the PRD data-minimization principle and the NFR-OBS-003 trace-redaction requirement. An optional future evaluation workflow may capture bounded, sanitized samples for accuracy tracking, but it requires a separate policy toggle and never includes credentials, schema comments, or raw result rows.

### PD4-003: QUERY permission and schema visibility in approved previews

A `QUERY` permission does **not** implicitly grant schema/object visibility. Schema visibility remains governed exclusively by `VIEW_SCHEMA`. An approved preview exposes only the bounded authorized catalog slice already selected for the generation request, and only to callers who hold both `QUERY` and `VIEW_SCHEMA` on the connection.

Rationale: `VIEW_SCHEMA` is the visibility permission, while `QUERY` is the execution permission. Combining them preserves least privilege and prevents a user who is allowed to run approved queries from enumerating all schemas/tables in a connection. The web console and API surface enforce this by returning schema objects only after `VIEW_SCHEMA` is verified, independent of `QUERY`.

If a future product decision permits schema-visible previews without `VIEW_SCHEMA`, it requires an explicit permission-model change, ADR, and contract update.

## Assumptions and alternatives

- The OpenAI-compatible adapter is the only provider adapter in Phase 4. Additional adapters (Azure OpenAI, AWS Bedrock, etc.) are future work requiring separate capability and security review.
- Model selection and prompt versioning are server-owned. Clients never select the model or prompt version.
- Preview SQL is a short-lived encrypted artifact with externally supplied key material and aggressive expiry cleanup.
- Execution always reauthorizes the caller, reloads current connection/catalog/policy state, and reruns QuerySentry to create a fresh `ApprovedQuery`. Clients never resubmit SQL.

## Approval record

On 2026-08-30, the product owner approved all three Phase 4 product decisions:

1. Reference OpenAI-compatible adapter configuration with the defaults listed above.
2. Provider retention defaults: 7-day generation artifact retention, 90-day audit metadata retention, no raw prompts/responses beyond evaluation window without explicit approval.
3. `QUERY` does not implicitly permit schema/object visibility; `VIEW_SCHEMA` remains the sole visibility permission.

This approval clears the P4-010 product-decision gate for P4-020 and its dependent parser/canonicalization ADR, then implementation.

## Traceability

| Requirement | Decision |
| --- | --- |
| PRD FR-NLQ-007 | PD4-001: provider-neutral adapter with OpenAI-compatible reference |
| PRD FR-NLQ-003 | PD4-001: structured proposal contract |
| PRD NFR-SEC-001 | PD4-002: no credentials or raw results in model requests/responses |
| PRD NFR-OBS-003 | PD4-002: trace redaction excludes questions, SQL, secrets, rows |
| PRD FR-IAM-003 | PD4-003: `VIEW_SCHEMA` governs visibility; `QUERY` governs execution |
| ADR-0005 | PD4-001: narrow port, configurable adapter |
| ADR-0006 | PD4-003: QuerySentry sole authority; schema resolution against authorized catalog |
| ADR-0010 | PD4-001: REST and MCP share use cases and policy outcomes |
