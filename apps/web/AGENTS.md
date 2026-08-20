# Web-specific agent instructions

These instructions extend the repository root `AGENTS.md`.

- Use strict TypeScript and the generated OpenAPI client.
- Treat connection secrets as write-only transient form state; never put them in URL, localStorage, sessionStorage, analytics, or logs.
- Default the query workflow to preview before execute.
- Always show selected connection, enforced limits, generated SQL, warnings, truncation, and request ID.
- Build accessible keyboard-operable components and test important states.
- Do not render unbounded rows or cell content.
- Do not invent backend behavior in UI-only mocks; update the API contract first.

