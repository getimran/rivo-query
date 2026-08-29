# Phase 2 verification evidence

Status: QA PASS (with environment limitation)
Date: 2026-08-29
Branch: feat/phase-2-web-onboarding

## Commands and outcomes

### Frontend

```bash
npm --prefix apps/web run lint
```
Result: clean, 0 warnings.

```bash
npm --prefix apps/web run test
```
Result: 160 tests passed (21 files). After P2-170 accessibility fix: 161 tests passed. Coverage: 96.49% statements, 92.83% branches, 100% functions, 96.41% lines. All thresholds met.

```bash
npm --prefix apps/web run build
```
Result: production build succeeded.

### Backend

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./mvnw -f apps/api/pom.xml verify
```
Result: **580 unit/contract/architecture tests passed, 0 failures**. OpenAPI validation/generation passed. Jar and repackage succeeded.

**Integration tests:** 10 Testcontainers ITs errored with `Could not find a valid Docker environment`. This is an environment limitation, not a code regression. On this machine the documented Colima socket is not at `/var/run/docker.sock`, and Colima is not running in this shell. Prior Phase 2 checkpoints recorded the required environment variables:
- `DOCKER_HOST=unix:///Users/<user>/.colima/default/docker.sock`
- `TESTCONTAINERS_RYUK_DISABLED=true`

The same 10 ITs passed in earlier checkpoint runs with Colima active.

## Review dispositions

### P2-170 frontend review

Independent review of P2-140 found one MEDIUM accessibility finding:
- Confirmation dialogs in `ConnectionDetailPanel.tsx` lacked `aria-modal="true"` and keyboard focus trapping.

Disposition: FIXED. Added `aria-modal="true"` to both disable/delete confirmation dialogs, implemented a Tab/Shift+Tab focus trap, and added a regression test. Frontend lint, 161 tests, and build pass after the fix.

No other blocking, high, or medium findings.

### P2-160 backend review

Discharged by the P2-130 independent review, which covered tenant queries/FKs, authorization order, secret ownership/redaction, destination enforcement, pool bounds, compensation, migration safety, and tests with no blocking findings.

## QA coverage summary

| Area | Coverage | Evidence |
|------|----------|----------|
| Permission matrix | Automated | `ConnectionPermissionControllerTest` |
| Cross-tenant access | Automated | `ConnectionControllerTest`, `ConnectionQueryServiceTest`, `ConnectionAggregateTest` |
| Idempotency | Automated | `ConnectionControllerTest` concurrent-create race, `IdempotencyGuardTest` |
| Secret handling | Automated | `SecretCapabilityBoundaryTest`, `SecretStoreCanaryTest`, `ConnectorAuditBoundaryTest`, frontend `useTransientSecrets` tests |
| Audit append-only | Automated | P2-060 V2 migration + trigger tests, lifecycle audit tests |
| Destination enforcement | Automated | `DestinationEnforcementPolicyTest`, `DnsjavaDestinationResolverTest`, `AddressRangeClassifierTest` |
| Provider failure | Automated | `SafeProblemHandlerTest` |
| Frontend core journey | Automated | `ConnectionsApp`, `ConnectionCreateForm`, `ConnectionDetailPanel`, `ConnectionList` tests |
| Frontend accessibility | Automated | New focus-trap test in `ConnectionDetailPanel.test.tsx` |

## Diff and secret review

`git diff --check` passed. Secret-pattern scan of the working diff found no embedded credentials, tokens, or keys. The diff touches:
- `PLANS.md` (progress updates)
- `docs/IMPLEMENTATION_TASKS.md` (task status updates)
- `docs/OPERATIONS.md` (P2-180 operational runbook)
- `apps/web/src/app/connections/ConnectionDetailPanel.tsx` (accessibility fix)
- `apps/web/src/app/connections/ConnectionDetailPanel.test.tsx` (new test)

## Known limitations

- Testcontainers integration tests require an active Docker-compatible runtime with Colima socket at `~/.colima/default/docker.sock` and `TESTCONTAINERS_RYUK_DISABLED=true`. This shell does not have that environment.
- The frontend is a development-only shell; production OIDC, MCP, query execution, and schema discovery UI remain in future phases.

## Next step

P2-190/200: final local verification and `CP-2` closeout PR.
