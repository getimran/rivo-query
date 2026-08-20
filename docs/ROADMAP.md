# Product roadmap

## Milestone 0 — Architecture baseline

PRD, architecture, ADRs, security model, contracts, test strategy, Codex workflow, and implementation plan.

## Milestone 1 — Secure backend vertical slice

- Control plane and authentication
- PostgreSQL/MySQL connections
- Encrypted secrets
- Schema discovery
- Provider-neutral generation
- QuerySentry
- Bounded execution and audit

Exit: REST API core journey passes integration and safety tests.

## Milestone 2 — Usable web tool

- Connection onboarding
- Schema status
- Question and clarification UX
- SQL preview/execute
- Result grid and feedback

Exit: non-technical user completes connection-to-answer journey.

## Milestone 3 — Agent tool

- MCP tools
- Service identities and scoped permissions
- Typed bounded tool responses
- Agent integration examples

Exit: Codex or another MCP client can use RivoQuery without receiving credentials.

## Milestone 4 — Production hardening

- Managed secret-store implementation
- OIDC reference deployment
- Rate limits, telemetry, dashboards, runbooks
- Kubernetes/Helm and CI security gates
- Load, chaos, backup, restore, rotation exercises

Exit: release gates in the PRD pass.

## Later candidates

- Additional SQL databases and warehouses
- Semantic metric layer
- Private/local model adapters
- Deterministic charts and summaries
- Scheduled reports under explicit authorization
- Deployment modes for self-hosted and managed service

Write access and autonomous database administration are intentionally not roadmap assumptions; they require separate risk acceptance and architecture.

