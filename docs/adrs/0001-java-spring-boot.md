# ADR-0001: Java 21 and Spring Boot backend

Status: Accepted  
Date: 2026-08-20

## Context

The owner has deep Java/Spring experience. RivoQuery needs secure HTTP APIs, JDBC support, connection pooling, authentication, validation, migrations, observability, and operational maturity.

## Decision

Use Java 21, Spring Boot 3.5.x at the latest approved patch, and Maven Wrapper. Pin exact versions in source and update through reviewed dependency automation. Reconsider Spring Boot 4 only in a compatibility ADR after the first release baseline.

## Consequences

Positive: mature ecosystem, strong JDBC/security/observability support, maintainable by the owner, and a stable LTS JVM baseline.

Trade-off: JVM startup/memory is higher than lightweight alternatives; Spring upgrades require disciplined compatibility and security maintenance.

## Alternatives considered

- Python/FastAPI: faster prototyping but not the selected engineering stack.
- Node.js: good web ecosystem but weaker alignment with owner experience and JDBC-heavy domain.
- Spring Boot 4 immediately: current but introduces a wider framework migration surface before product risks are validated.

## Validation

Maven verify, architecture tests, container image measurement, and representative load tests.

