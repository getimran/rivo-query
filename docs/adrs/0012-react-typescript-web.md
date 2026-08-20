# ADR-0012: React and TypeScript web console

Status: Accepted  
Date: 2026-08-20

## Context

The generic tool needs a usable connection onboarding and question/result experience for non-technical users. The backend selection does not define the browser stack.

## Decision

Use React with strict TypeScript and a generated client from the OpenAPI contract. Keep secrets only in transient form state and never persist them in browser storage.

## Consequences

Positive: mature component/testing ecosystem, typed contracts, and clear separation from backend.

Trade-off: Node build toolchain and separate dependency/security maintenance.

## Alternatives considered

- Server-rendered Spring templates: simpler deployment but less suitable for the interactive query workspace.
- Angular: viable but heavier for the initial team/product.
- No web UI: conflicts with “usable by anyone.”

## Validation

Strict build, lint/unit/component tests, accessibility checks, typed contract generation, and Playwright core-journey tests.

