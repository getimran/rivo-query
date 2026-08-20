# ADR-0013: Vetted-address outbound destination enforcement

Status: Accepted

Date: 2026-08-20

## Context

RivoQuery accepts a database hostname and port and is therefore an outbound
connection broker. Validating a hostname only when a connection is registered
does not prevent DNS rebinding, mixed public/private DNS answers, a later policy
change, or a JDBC driver resolving a different address when it opens a socket.
PostgreSQL and MySQL TLS identity checks must still validate the user-configured
hostname rather than a selected IP address.

ADR-0004 isolates database behavior behind connector adapters. This ADR defines
the destination capability those adapters must consume; it does not permit
user-selected JDBC URLs, socket factories, driver classes, or unrestricted
properties.

## Decision

### Structured endpoint and canonical host

The API accepts a database kind, host, port, database name, TLS mode, and an
explicit allowlist of safe connector options. Trusted connector code constructs
the JDBC URL and properties.

The destination adapter parses IP literals strictly and canonicalizes DNS names
to lower-case IDNA ASCII without accepting alternative numeric IPv4 notation,
IPv6 zone identifiers, URI user information, paths, query strings, or embedded
ports. It retains the canonical DNS hostname separately as `tlsServerName`.
Ports must be valid for the selected connector and allowed by platform policy.

### Resolve and classify every answer

Before a connection test and before every new physical database connection, the
adapter performs a fresh bounded lookup of the complete CNAME and A/AAAA result.
An empty, truncated, malformed, timed-out, or indeterminate result fails closed.
Every returned IPv4 and IPv6 address is normalized and classified. IPv4-mapped
IPv6 and transition forms are also classified by their embedded address.

The entire destination is rejected when any answer is prohibited. The adapter
must not choose an allowed answer while ignoring a prohibited answer. The
non-overridable deny set includes unspecified, loopback, link-local, multicast,
broadcast, documentation, benchmarking, reserved/special-use, and cloud metadata
destinations. Platform policy may expand this set.

RFC 1918, carrier-grade NAT, and IPv6 unique-local destinations are denied by
default. A deployment operator, not a tenant administrator or request caller,
may permit private destinations using a bounded rule containing connector kind,
canonical DNS suffix or exact name, port/range, and destination CIDR. Both the
name/port and every resolved address must match the same applicable rule. Private
rules cannot override the non-overridable deny set. Mixed public/private answers
fail closed.

### Short-lived destination capability

Successful validation creates an in-memory, non-serializable
`ValidatedDestination` containing:

- tenant ID, connection ID and optimistic connection version;
- connector kind and connection purpose;
- canonical hostname, original-host TLS server name, and port;
- the complete vetted address set and its fingerprint;
- destination-policy version, resolution time, expiry, absolute operation
  deadline, and an unguessable nonce.

Only the destination adapter can construct this type. Connector methods require
it rather than raw host/port data. It is invalid if any binding differs or the
connection/policy version, expiry, or operation deadline has passed.

The capability expires at the earliest positive DNS TTL, a platform-configured
short maximum, or the caller's remaining deadline. Where the resolver cannot
provide a trustworthy TTL, the platform uses a conservative short fallback.
Capabilities are never persisted or shared between replicas. Resolution and
connect/TLS handshakes each have hard platform maxima and also consume the one
absolute caller deadline; retries never reset that deadline.

### Connect to a vetted address and verify the original host

PostgreSQL and MySQL adapters use RivoQuery-controlled, non-user-configurable
driver socket hooks. The hook opens the network socket only to an address in the
capability and prevents the driver or operating-system resolver from selecting a
different address. Multiple vetted addresses may be attempted within the same
absolute deadline, but no new DNS answer may be introduced by a retry.

The logical JDBC host and TLS server name remain the canonical original
hostname. Production TLS uses full certificate-chain and hostname verification
against that name, never the selected IP. When the configured host is itself an
IP literal, verification requires a matching IP subject alternative name. The
socket hook may not disable or replace driver TLS verification. Each
driver/version combination must prove this behavior using a certificate valid
for the hostname but not for the selected IP. If a supported driver cannot both
pin the socket address and verify the original hostname, that connector
configuration is unavailable; falling back to ordinary driver DNS is forbidden.

### Pool revalidation

Destination validation occurs at physical-connection creation, not merely at
pool creation or logical checkout. A pool is keyed by tenant, connection ID and
version, secret version, and destination-policy version. It also records the
vetted address-set fingerprint and validation expiry without recording a secret.

Before lending an existing connection after destination validation expires, the
pool pauses new borrows and obtains a fresh resolution under policy. If the set
is unchanged and allowed, the pool binding is renewed. If it changed, policy
changed, resolution failed, or any answer is prohibited, the pool is quarantined
and drained; a newly validated pool is required. Every replacement physical
connection obtains its own fresh capability even while the pool binding is
current. Disable, endpoint/policy update, rotation, deletion, and shutdown also
stop new borrows and drain the applicable pool.

An already established socket cannot be redirected by a later DNS answer, but
it is still bounded by idle/lifetime limits and the pool drain protocol.

### Independent infrastructure enforcement

Application validation is not the only barrier. Production deployment uses
deny-by-default workload egress and routes target database traffic through a
network firewall or egress gateway that independently denies special-use and
metadata ranges and permits only operator-approved public/private destination
CIDRs and database ports. DNS egress is restricted to approved resolvers. Model,
identity, secret-provider, control-plane, and target-database egress rules are
separate so a target rule cannot authorize access to an internal service plane.

The application never changes infrastructure policy from tenant input. A target
must pass both application policy and infrastructure policy; disagreement fails
closed. Local development may explicitly allow synthetic container networks,
but that policy is not a production default.

## Consequences

### Positive

- Every driver socket is bound to an address that passed complete IPv4/IPv6
  policy rather than a preflight-only hostname check.
- DNS rebinding cannot redirect a socket between validation and connection.
- Private connectivity remains possible under explicit operator governance.
- Original-host TLS verification is preserved for PostgreSQL and MySQL.
- Pool reuse cannot indefinitely outlive DNS or destination-policy validation.
- Infrastructure controls limit impact if application validation regresses.

### Negative or trade-off

- Trusted socket integrations and driver-version compatibility tests are needed
  for both connectors.
- Strict all-answer rejection may reduce availability for DNS records containing
  mixed or temporarily invalid addresses.
- TTL-aware pool revalidation and draining add DNS load and connection churn.
- Private targets require coordinated application and infrastructure rules.
- Some managed-database authentication/socket plugins remain unsupported until
  separately reviewed; users cannot supply them as driver properties.

## Alternatives considered

- Validate only during registration: rejected because later DNS and policy
  changes bypass the decision.
- Resolve, validate, then let JDBC resolve the hostname again: rejected because
  it retains a DNS time-of-check/time-of-use gap.
- Connect to an IP and disable hostname verification: rejected because it trades
  SSRF resistance for a TLS impersonation vulnerability.
- Allow a public answer when another answer is private: rejected because address
  selection can change and attackers can deliberately create mixed results.
- Use only an egress proxy: rejected as the application would still lack tenant,
  connection, hostname, and policy-version binding. Infrastructure enforcement
  remains a required independent layer.

## Validation

- Unit tests cover strict literals, IDNA, CNAMEs, all-answer IPv4/IPv6
  classification, mapped/transition addresses, mixed results, private-rule
  matching, metadata/special-use denial, empty/truncated responses, TTL/expiry,
  deadline exhaustion, and policy/version binding.
- Deterministic resolver tests change answers between registration, pool use, and
  physical connection creation and prove that no unvetted address is dialed.
- PostgreSQL and MySQL integration tests use trusted socket hooks and hostname
  certificates to prove vetted-IP dialing plus original-host verification.
- Pool tests cover expiry, unchanged and changed address sets, resolution/policy
  failure, quarantine/drain, rotation, deletion, and concurrent borrowers.
- Deployment tests prove approved DNS only, metadata/special-use denial, explicit
  private allowlists, and separate service-plane/target egress policy.
- Logs, metrics, traces, exceptions, and RFC 9457 responses expose neither
  credentials nor unsafe raw driver details.

## Supersedes

None. This ADR refines ADR-0004 and the destination policy in the security threat
model.
