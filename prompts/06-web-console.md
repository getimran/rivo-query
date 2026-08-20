# Phase 6 prompt — web console

Read applicable instructions, PRD user journey, API contracts, ADR-0012, web `AGENTS.md`, test strategy, and Phase 6 in `PLANS.md`.

Implement the usable web journey:

- Authentication-aware application shell.
- Connection create/test/manage and schema freshness.
- Question workspace with connection selector.
- Clarification state.
- Generated SQL preview, assumptions, warnings, enforced limits, and explicit execute action.
- Typed bounded results grid, truncation/error/empty/loading states, request ID, and feedback.
- No browser persistence or analytics leakage of secrets/results.
- Responsive accessible components and Playwright core-flow tests.

Use the generated OpenAPI client and do not duplicate backend policy. Update the plan and run lint, unit, build, accessibility smoke, and E2E checks.

