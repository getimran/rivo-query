# ADR-0005: Provider-neutral LLM port

Status: Accepted  
Date: 2026-08-20

## Context

No LLM deployment was selected. Users may require a public API, Azure/AWS-hosted model, or local/on-prem deployment. Model output is probabilistic and provider APIs change.

## Decision

Define a narrow `SqlGenerationModel` port using RivoQuery-owned request and structured-response types. Ship one configurable OpenAI-compatible HTTP adapter as a reference, without provider classes in domain or public API contracts.

## Consequences

Positive: deployment flexibility, testability, and reduced vendor coupling.

Trade-off: provider-specific structured-output, identity, safety, and telemetry features require adapter capabilities rather than leaking into the domain.

## Alternatives considered

- Hard-code one provider SDK: fastest initially but creates product coupling.
- Adopt a large agent framework as the domain abstraction: hides critical prompt, retry, and data-flow behavior.
- Self-host a model by default: infrastructure burden before quality requirements are validated.

## Validation

Deterministic fake adapter, HTTP contract tests, provider capability matrix, and evaluation results by adapter/model version.

