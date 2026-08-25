# RivoQuery — Claude Code entrypoint

@AGENTS.md

## Claude Code specifics

This project was bootstrapped with the Codex IDE extension (`.codex/agents/`, `prompts/*.md`, `docs/CODEX_WORKFLOW.md`). The instructions above (`AGENTS.md`) are provider-neutral and authoritative for both agents. This section adds the Claude Code equivalents so the same workflow runs here.

### Subagents (`.claude/agents/`)

Same roles and scopes as `.codex/agents/`, invoked with the `Agent` tool instead of Codex subagents:

| Use for | Subagent | Edits files? |
|---|---|---|
| Product/PRD scope questions | `prd-researcher` | No |
| Cross-cutting architecture, ADR needs | `solution-architect` | No |
| Backend API/contract/domain design | `api-architect` | No |
| Independent design challenge before build | `solution-design-reviewer` | No |
| Turning an approved design into tasks | `task-planner` | `PLANS.md` only |
| Backend implementation (`apps/api`) | `backend-engineer` | Yes |
| Frontend implementation (`apps/web`) | `frontend-engineer` | Yes |
| CI/CD, containers, `deploy/` | `devops-engineer` | Yes |
| Backend diff review | `backend-code-reviewer` | No |
| Frontend diff review | `frontend-code-reviewer` | No |
| Risk-based test design/execution | `qa-tester` | Test code only |
| Post-implementation live sanity check | `sanity-runner` | No (never a pre-push gate) |

Same rules as `AGENTS.md` § Planning and review: the primary agent orchestrates and stays accountable; never run `backend-engineer` and `frontend-engineer` (or any two file-writing agents) on overlapping files in parallel; use `sanity-runner` only once a runnable product exists and normal automated tests already pass.

### Phase commands (`.claude/commands/`)

Equivalent to `prompts/00-orientation.md` and `prompts/01-07-*.md`; run in order, one phase at a time, per `PLANS.md`:

`/orient`, `/phase1`, `/phase2`, `/phase3`, `/phase4`, `/phase5`, `/phase6`, `/phase7-8`

### Keeping this in sync

If you change agent scope, add a phase, or add a non-negotiable rule, update both the Codex artifact (`.codex/agents/*.toml` or `prompts/*.md`) and its Claude Code counterpart (`.claude/agents/*.md` or `.claude/commands/*.md`). Durable rules belong in `AGENTS.md` (imported above), not duplicated here.
