# ADR-0014: Accept GPL-2.0-with-Universal-FOSS-Exception for the MySQL JDBC driver

Status: Accepted
Date: 2026-08-26

## Context

P2-120 (real MySQL connectivity, `apps/api/src/main/java/com/knowimran/rivoquery/platform/connector/mysql/**`) requires a JDBC driver for MySQL. The only officially maintained option is Oracle's `com.mysql:mysql-connector-j`, added compile-scoped in `apps/api/pom.xml`.

Its POM declares "The GNU General Public License, v2 with Universal FOSS Exception, v1.0" — a materially different license posture from `org.postgresql:postgresql` (BSD-2, runtime-scoped), already in use for PostgreSQL connectivity (ADR-0004). The Universal FOSS Exception permits linking from an application distributed under an OSI-approved open-source license without that application itself becoming subject to the GPL, but does not apply to proprietary/closed-source distribution. This is a distribution-model decision, not a purely engineering one, so it needed explicit product sign-off rather than being silently absorbed into P2-120's diff.

## Decision

Accept `com.mysql:mysql-connector-j` under GPL-2.0-with-Universal-FOSS-Exception as RivoQuery's MySQL JDBC driver. RivoQuery's own distribution model is expected to remain compatible with the FOSS Exception's terms (open-source distribution, or a deployment model — e.g. SaaS — that does not trigger GPL's distribution obligations in the first place). If RivoQuery's distribution model changes to closed-source/proprietary redistribution of the compiled application in a way that would trigger GPL obligations, this decision must be revisited before that change ships.

## Consequences

### Positive

- Unblocks MySQL as a supported target database (the PRD's second initial target, alongside PostgreSQL) using the only officially maintained, actively supported driver.
- No engineering cost of building/maintaining an alternative driver integration.

### Negative or trade-off

- Introduces a GPL-family dependency into a codebase that was previously entirely permissively-licensed (BSD-2, Apache-2.0, MIT).
- If RivoQuery's distribution model changes, this dependency (and everything that links against it, in practice the whole `apps/api` module) may need to be revisited or replaced.

## Alternatives considered

- **MariaDB Connector/J (LGPL)**: wire-compatible with MySQL, more permissive license family than plain GPL. Rejected for now — would require building and independently security-reviewing an entirely separate socket-pinning implementation (P2-120's core deliverable) against a different driver's internals, for a license benefit that doesn't currently apply given RivoQuery's expected distribution model.
- **Deferring MySQL support entirely**: rejected — MySQL is explicit PRD scope, and deferring only postpones this decision rather than avoiding it.

## Validation

Revisit this ADR if RivoQuery's distribution/licensing model changes (e.g. a closed-source on-prem distributable), or if a product/legal review determines the Universal FOSS Exception does not cover RivoQuery's actual deployment shape.

## Supersedes

None.
