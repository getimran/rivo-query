# Phase 2 prompt — connection control plane

Read the applicable agent instructions, PRD connection requirements, architecture sections 6/8/13/14, security threat model, ADR-0003/0004/0008/0009, and Phase 2 of `PLANS.md`.

Implement connection creation/list/get/update/disable/delete/test/secret-rotation with:

- Tenant-scoped authorization and opaque IDs.
- Structured host/port/database fields; no unrestricted raw JDBC URL.
- Destination/SSRF policy with IPv4/IPv6 tests.
- Write-only secrets behind `SecretStore`.
- Local AES-GCM adapter whose key comes only from external configuration; make production secret integration an interface and documented stub, not fake security.
- PostgreSQL/MySQL connector skeletons and real Testcontainers connection tests.
- Strict timeout, TLS policy, stable errors, redacted logs, pool eviction on rotation/deletion.
- OpenAPI contract and typed frontend connection-onboarding flow only to the extent needed for Phase 2 acceptance.

Do not implement schema discovery or model/query features. Update `PLANS.md`, run checks, and self-review security invariants before finishing.

