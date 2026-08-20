# ADR-0008: Externalized envelope secret storage

Status: Accepted  
Date: 2026-08-20

## Context

RivoQuery accepts credentials for arbitrary customer databases. Plaintext configuration or reversible values stored next to metadata create unacceptable blast radius.

## Decision

Use a `SecretStore` port. Persist only opaque secret references/versions in the control database. Provide an AES-GCM envelope adapter for local development with a key supplied outside source control, and production adapters for managed KMS/Vault or an external secret broker.

## Consequences

Positive: key separation, rotation, provider flexibility, and reduced control-plane exposure.

Trade-off: production secret service becomes a critical dependency; local adapter is not a production recommendation.

## Alternatives considered

- Plain environment variables per customer: not dynamic/scalable.
- Reversible encryption with a key in the same database/config: inadequate separation.
- Browser-only credential storage: incompatible with agent/server operation and unsafe lifecycle.

## Validation

Ciphertext-only persistence tests, redaction snapshots, rotation/pool-eviction test, and failure-closed secret-provider tests.

