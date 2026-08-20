# ADR-0007: Versioned normalized schema catalog

Status: Accepted  
Date: 2026-08-20

## Context

Models need schema context, but full schemas can be large and contain unauthorized or malicious metadata. Target metadata differs by vendor and changes over time.

## Decision

Discover permitted JDBC metadata into a vendor-neutral, versioned catalog with a deterministic fingerprint. Select a bounded relevant subset for each question using lexical/business metadata first; keep vector selection behind an interface for later use. Raw sample values are disabled by default.

## Consequences

Positive: bounded prompts, authorization before model context, reproducibility, and dialect normalization.

Trade-off: stale snapshots and selector misses can reduce accuracy; business semantics require curation.

## Alternatives considered

- Send full DDL each time: unbounded and leaks unnecessary metadata.
- Embed database rows: privacy/cost risk and unnecessary for baseline.
- Query live metadata on every question: adds latency/load and weakens reproducibility.

## Validation

Catalog compatibility tests, fingerprint/refresh tests, schema-selection recall metrics, and prompt snapshot tests.

