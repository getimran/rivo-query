# ADR-0003: PostgreSQL control plane

Status: Accepted  
Date: 2026-08-20

## Context

RivoQuery needs transactional storage for tenants, memberships, connection metadata, secret references, schema snapshots, query metadata, feedback, audit events, and versioned policies.

## Decision

Use PostgreSQL as the control-plane database with versioned migrations. It is separate from customer target connections, even when a target is PostgreSQL.

## Consequences

Positive: transactions, constraints, JSON support where needed, mature operations, and one authoritative system of record.

Trade-off: a required production dependency and migration/backup responsibility.

## Alternatives considered

- MySQL: viable but PostgreSQL better matches the selected operational baseline.
- Embedded database: unsuitable for multi-replica production.
- Store all state in a secret manager: wrong consistency/query model.

## Validation

Migration tests, constraint/tenant-isolation tests, backup/restore exercise, and failover readiness checks.

