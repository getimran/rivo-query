# ADR-0002: Modular monolith in a monorepo

Status: Accepted  
Date: 2026-08-20

## Context

The core query path crosses multiple security-sensitive concerns. Early microservices would add network trust boundaries, distributed transactions, deployment cost, and debugging overhead before independent scaling needs are known.

## Decision

Build one Spring Boot deployable with enforced internal modules, plus a separate web application in the same repository. MCP begins as an adapter over the same application services.

## Consequences

Positive: atomic security flow, simpler local development, easier refactoring and tests, shared contract changes.

Trade-off: one backend release unit and shared scaling. Target connector isolation may later require worker extraction.

## Alternatives considered

- Microservices per module: premature operational complexity.
- Single unstructured application package: insufficient boundary enforcement.
- Separate repositories: increases coordinated contract and developer-environment overhead.

## Validation

Automated architecture tests prevent package cycles and forbidden dependencies. Extraction requires measured scaling/isolation evidence and a new ADR.

