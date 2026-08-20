# Deployment-specific agent instructions

These instructions extend the repository root `AGENTS.md`.

- Never embed credentials or encryption keys in images, manifests, Helm defaults, or examples.
- Use external secrets and restrictive workload identities in production examples.
- Deny outbound traffic by default and document required model/target/identity endpoints.
- Run the application as a non-root user with a read-only root filesystem where practical.
- Define readiness, liveness, resource requests/limits, graceful shutdown, and disruption behavior.
- Account for total target database connections across all replicas.
- Migrations run as a controlled release job, not concurrently in every application pod unless explicitly proven safe.

