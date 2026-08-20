# ADR-0011: Metadata-only audit and privacy-safe telemetry

Status: Accepted  
Date: 2026-08-20

## Context

Operators need evidence for investigations and SLOs, but logs, traces, and audits can become a second data-exfiltration channel.

## Decision

Audit every execution attempt using actor/tenant/connection/request identifiers, policy/model/schema versions, SQL fingerprint, outcome, timings, and counts. Do not store raw result rows. Questions, SQL text, prompts, and model responses are disabled or protected by explicit retention policy. General telemetry excludes secrets, SQL literals, and result data.

## Consequences

Positive: investigation and accountability with minimized sensitive data.

Trade-off: some debugging is harder without raw payloads; secure opt-in diagnostics may be needed later.

## Alternatives considered

- Log everything: unacceptable disclosure risk.
- No audit: unacceptable for sensitive database access.
- Store full results for history: changes RivoQuery into a data warehouse and expands compliance scope.

## Validation

Log/trace/audit snapshot tests, data-retention tests, and incident reconstruction drills.

