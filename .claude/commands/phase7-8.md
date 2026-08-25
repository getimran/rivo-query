---
description: Phases 7-8 — MCP interface and production hardening
---

Complete Phase 7 before Phase 8 and checkpoint between them. Read applicable instructions, MCP contracts, ADR-0009/0010/0011, threat model, operations, roadmap, test strategy, and remaining `PLANS.md` acceptance criteria.

Phase 7:

- Implement bounded MCP tools `list_connections`, `describe_schema`, `preview_query`, and `ask_database` over the same application use cases.
- Map an authenticated MCP identity to tenant and permissions.
- Prove MCP cannot bypass REST-equivalent authorization, QuerySentry, or execution limits.

Phase 8:

- Rate/concurrency limits, privacy-safe telemetry, SLO dashboards/alerts.
- CI security gates, dependency/container/IaC scans.
- Production container and Kubernetes/Helm deployment with external secrets, TLS, egress policy, non-root/read-only filesystem, probes, resources, and graceful shutdown.
- Load/abuse tests, backup/restore and credential-rotation exercises, and completed runbooks.

Do not claim production readiness without evidence for every PRD release gate. Update `PLANS.md` with measurements, remaining risk, and final retrospective.
