# API-specific agent instructions

These instructions extend the repository root `AGENTS.md`.

- Keep module boundaries aligned with `docs/ARCHITECTURE.md`; add architecture tests for new boundaries.
- Controllers only authenticate/map/validate transport concerns and call application use cases.
- Never accept an unrestricted JDBC URL or arbitrary driver-property map from a client.
- Only `querypolicy` can construct `ApprovedQuery`; only `execution` can use it.
- Target connectors cannot depend on REST/MCP or model adapters.
- Use Testcontainers for behavior that depends on PostgreSQL or MySQL.
- Migrations are forward-compatible and tested from an empty control database.
- Do not use Lombok for security-sensitive value types; keep redaction and invariants explicit.
- All exception-to-problem mapping must use stable safe error codes.

