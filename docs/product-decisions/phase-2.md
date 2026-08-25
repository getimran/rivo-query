# Phase 2 product decisions: workspace and connection administration

Status: APPROVED

Decision owner: Product owner

Recorded: 2026-08-20

Applies to: Phase 2 identity and connection control plane

## Context and authority

The PRD makes the workspace the tenant boundary, identifies a workspace
administrator as the actor who registers and operates database connections,
and requires server-side membership and connection authorization. ADR-0009
rejects a request-body tenant ID as authority. The product owner explicitly
approved all three P2-010 defaults on 2026-08-20: administrator-only connection
administration, server-derived multi-workspace discovery, and mandatory exact
workspace-selector semantics. This record makes those accepted decisions
testable without expanding Phase 2 into workspace provisioning, schema
discovery, or query execution.

## Decisions

### PD2-001: connection administration is administrator-only

Only an authenticated actor with an active `ADMINISTRATOR` membership in the
selected workspace may create a connection or perform an administrative
connection lifecycle action. Administrative actions are create, update
configuration or policy, test, rotate a secret, disable, re-enable, delete, and
grant or revoke connection permissions.

The server derives the role from current membership data. A role or permission
claim supplied by the client, an identity-provider workspace claim, a request
body field, or possession of an opaque connection ID is never sufficient.
Workspace administrators have effective `MANAGE` permission for connections in
their workspace. In the MVP, `MANAGE` is not grantable to a member and does not
elevate a member to administrator. Members may receive `VIEW_SCHEMA`, `QUERY`,
and `EXPORT` independently when those capabilities become available.

Administrators can discover and administer all connections in their selected
workspace. A member can discover only connections for which the member has at
least one effective connection permission, and receives only the safe metadata
required by the permitted operation. No connection response returns a secret.

This is the least-privilege default. Delegated connection administration for
non-administrators is not part of Phase 2 and requires explicit product-owner
approval plus corresponding PRD, contract, permission-matrix, audit, and tenant
isolation changes.

### PD2-002: workspace discovery is server-derived

An authenticated caller discovers workspaces through a dedicated unscoped
workspace-discovery operation. Its result is derived from current, active
server-side memberships and contains only the opaque workspace ID, display
name, effective role, and selection status needed by the client. It does not
accept a workspace ID as an authority and does not disclose workspaces for
which the caller has no active membership.

An authenticated caller with no active membership receives an empty discovery
result. This does not create a workspace or membership. The browser may
automatically select the sole discovered workspace for convenience, but it
must still send the explicit selector on every workspace-scoped request.

### PD2-003: `X-RivoQuery-Workspace-Id` is an explicit selector only

Every workspace-scoped REST request requires exactly one
`X-RivoQuery-Workspace-Id` header. The server processes it as follows:

1. Authenticate the caller and validate the token.
2. Reject a missing, duplicated, comma-combined, malformed, non-canonical, or
   whitespace-padded selector with `400` and a stable safe problem code.
3. Load the selected active workspace membership from server-side persistence
   using the authenticated external subject.
4. Return the same `403` safe problem for an unknown workspace, a non-member,
   an inactive workspace, or an inactive membership; do not reveal which case
   occurred.
5. Construct the immutable `ActorContext` from the validated membership and
   authorize the requested operation before loading a connection, resolving a
   secret, applying destination policy, or opening a target connection.

Header names follow HTTP case-insensitivity, but the opaque header value is
matched exactly in its canonical representation. The server does not trim,
case-fold, split, or choose one value from multiple values. It never falls back
to the caller's first membership or to a workspace named in a token, path,
query parameter, cookie, or request body.

The workspace-discovery operation itself is unscoped and rejects this header
when supplied so callers cannot mistake it for an authorization input. A body
may contain a workspace ID only when a future resource representation genuinely
requires it; it must match the already validated `ActorContext` and can never
select or authorize a workspace. New Phase 2 workspace-owned resources omit a
body workspace ID and take ownership exclusively from `ActorContext`.

Membership and role changes take effect on the next request because membership
is checked server-side each time. A selection is request-local: changing it in
the web console must clear workspace-specific cached state and in-progress
secret input. Responses containing workspace data use private/no-store caching
semantics and must not be shared across selectors.

## Assumptions and alternatives

- Opaque workspace IDs are server-generated and globally non-semantic; the API
  architect will define their canonical schema and stable problem-code names.
- `ADMINISTRATOR` and `MEMBER` are effective product roles. Identity-provider
  group/claim mappings may locate an external identity, but current membership
  persistence remains authoritative.
- Requiring the header even for a caller with one membership was selected over
  implicit selection. It costs one client step but avoids environment-dependent
  defaults, confused-deputy behavior, and accidental cross-workspace requests.
- A generic tenant ID in a body, query parameter, or trusted JWT claim was
  rejected because it conflicts with ADR-0009 and permits stale or tampered
  authority.
- Returning one indistinguishable `403` for invalid membership selection was
  selected over distinct not-found/member-state responses to avoid workspace
  enumeration. Resource-level inaccessible connection IDs should likewise be
  indistinguishable from unknown IDs as the API contract defines.
- Allowing members with `MANAGE` to administer a connection was rejected for
  the MVP. It creates a delegated-administration model that the current product
  roles, onboarding flow, and audit requirements do not yet specify.

## Approval record

On 2026-08-20, the product owner explicitly answered “Approved” to all three
P2-010 questions:

1. Do you approve restricting connection creation and every administrative
   connection lifecycle and permission action to active workspace
   administrators, with `MANAGE` not grantable to members in the MVP?
2. Do you approve server-derived multi-workspace discovery, including an empty
   result for authenticated callers who have no active membership and automatic
   client selection only as a UI convenience?
3. Do you approve requiring exactly one canonical
   `X-RivoQuery-Workspace-Id` on every workspace-scoped REST request, including
   the stated `400`/non-enumerating `403` behavior, no implicit single-workspace
   fallback, and rejection of that selector on the discovery operation?

This approval clears the P2-010 product-decision gate for P2-040 and its
dependent contract and implementation tasks. The corresponding accepted
requirements are recorded in the PRD.

## Product-owner decisions intentionally deferred beyond this approval

The following are not needed to implement the Phase 2 boundary and must not be
invented by engineering:

- How the first workspace and administrator are provisioned in each deployment
  (operator bootstrap, identity-provider mapping, invitation, or self-service).
- Which identity provider is used by the reference deployment.
- Whether a future release adds a delegated connection-administrator role or
  permits members to receive `MANAGE`.
- Whether cross-workspace platform operators exist. Phase 2 grants no such
  implicit access; support would require an explicit administrative boundary,
  audit model, contract, and product-owner approval.

Local and automated Phase 2 tests may provision synthetic workspaces,
identities, and memberships directly through controlled fixtures. No public
workspace-create or invitation endpoint is implied.

## Acceptance and traceability tests

| Requirement | Required evidence |
| --- | --- |
| Administrator-only administration | The complete role/action matrix proves an active administrator can create and perform every lifecycle action; a member, including one presented with `MANAGE`, receives a safe denial before repository mutation, secret resolution, destination resolution, or connector access. |
| Server-derived membership | Forged body, query, cookie, token workspace/role claims, and opaque connection IDs cannot change the selected workspace or effective role. Revoked and disabled memberships lose access on the next request. |
| Discovery isolation | Zero, one, and multiple active-membership responses are tested; inactive and unrelated workspaces never appear; a selector sent to discovery is rejected. |
| Selector syntax | Missing, empty, padded, malformed, duplicated, comma-combined, and non-canonical selector values return stable safe `400` problems; a valid selector is accepted only after authentication. |
| Selector authorization | Unknown, cross-tenant, inactive-workspace, and inactive-membership selectors return the same safe `403` response and perform no workspace-owned resource lookup or downstream secret/network operation. |
| No implicit fallback | A scoped request without a selector fails even with one membership; an unauthorized selector never falls back to another membership. |
| Tenant persistence | Creation takes `tenant_id` only from `ActorContext`; repository methods require the selected tenant; composite constraints prevent cross-tenant connection and permission references. |
| Interface parity | REST, web, and future MCP adapters invoke the same membership and authorization use cases and produce equivalent allow/deny outcomes. |
| Browser isolation | Switching workspaces clears prior connection data, requests, and secret fields; cache keys include the selected workspace and workspace responses are not shared or persistently cached. |
| Safe observability | Tests prove tokens, membership claims, secrets, endpoint credentials, and raw driver details are absent from responses, logs, traces, and metrics; audit metadata records actor, selected workspace, action, and outcome. |

Traceability: PRD `FR-CON-002`, `FR-CON-004`, `FR-CON-007`, `FR-IAM-002`
through `FR-IAM-007`, and `NFR-SEC-001`; architecture sections 6, 13, and 14;
security threat model sections 3, 5, 7, and 8; ADR-0009; Phase 2 tasks P2-040,
P2-050, P2-060, P2-100, P2-130, P2-140, P2-150, P2-160, and P2-170.
