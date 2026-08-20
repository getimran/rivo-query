# ADR-0009: OIDC with tenant and connection authorization

Status: Accepted  
Date: 2026-08-20

## Context

A generic tool may host multiple organizations and sensitive database connections. Authentication alone does not prevent IDOR or cross-tenant schema/query access.

## Decision

Use OAuth2/OIDC JWT authentication. Derive actor identity from validated claims and server-side membership. Scope every resource to a tenant and enforce connection permissions: `VIEW_SCHEMA`, `QUERY`, `EXPORT`, and `MANAGE`.

## Consequences

Positive: standards-based identity and explicit least privilege across interfaces.

Trade-off: reference deployments need an identity provider; service identities and MCP require careful mapping.

## Alternatives considered

- Shared API key: weak attribution and coarse authorization.
- Application-managed passwords: expands identity/security scope.
- Tenant ID in request body: not an authority and vulnerable to tampering.

## Validation

Issuer/audience tests, complete role/permission matrix, cross-tenant negative tests, and REST/MCP parity tests.

