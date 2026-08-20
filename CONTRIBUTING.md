# Contributing

## Workflow

1. Read the relevant product, architecture, security, and ADR documents.
2. Create or update the active execution plan.
3. Implement the smallest vertical change that satisfies the acceptance criteria.
4. Add tests at the appropriate layer.
5. Run the checks listed in `AGENTS.md`.
6. Review against `docs/CODE_REVIEW.md`.
7. Update documentation and ADRs when behavior or decisions change.

## Commit guidance

Use focused commits and conventional prefixes such as `feat:`, `fix:`, `test:`, `docs:`, `refactor:`, and `chore:`. Never commit credentials, generated local data, access tokens, model prompts containing customer data, or query result exports.

## Decision changes

Do not edit accepted ADRs to rewrite history. Add a superseding ADR and update the ADR index.

