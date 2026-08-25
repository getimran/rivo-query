---
description: Phase 3 — schema catalog (discovery, filtering, versioned snapshots, relevance selection)
---

Read applicable instructions, PRD schema requirements, architecture sections 7/8/9, threat model prompt-injection/privacy sections, ADR-0004/0007, test strategy, and Phase 3 in `PLANS.md`.

Implement:

- Vendor-neutral schema catalog domain types.
- PostgreSQL/MySQL metadata discovery through connector adapters.
- System-object and connection-allowlist filtering before persistence/model use.
- Versioned snapshots and deterministic fingerprints.
- Manual idempotent refresh plus operation status.
- Bounded lexical relevance selector with relationship expansion and business metadata hooks.
- Catalog/selector compatibility tests on real engines.
- Schema API and minimal UI status/browser.

Treat metadata text as untrusted. Do not retrieve sample rows/values. Do not implement the LLM path. Update the plan, run checks, and report selector limitations.
