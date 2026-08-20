# ADR-0010: REST first, MCP as an adapter

Status: Accepted  
Date: 2026-08-20

## Context

RivoQuery must serve a web console, applications, and AI agents. Independent implementations would drift in safety and authorization.

## Decision

Build application use cases first, expose OpenAPI-based REST for the web/application interface, then expose a narrow MCP tool adapter over the same use cases. MCP accepts connection IDs, never credentials.

## Consequences

Positive: one policy implementation, typed web client generation, and agent usability.

Trade-off: two transport contracts and authentication adapters must remain consistent.

## Alternatives considered

- MCP only: poor fit for general web/application clients.
- REST only: misses direct agent-tool integration goal.
- Separate MCP service with direct connector access: dangerous policy duplication.

## Validation

Behavioral parity tests and architectural prohibition on MCP-to-connector direct dependencies.

