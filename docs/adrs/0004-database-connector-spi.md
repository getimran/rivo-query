# ADR-0004: Database connector SPI

Status: Accepted  
Date: 2026-08-20

## Context

PostgreSQL and MySQL differ in metadata, identifiers, types, functions, read-only session controls, timeouts, and error behavior. More databases may be added later.

## Decision

Define a vendor-neutral connector SPI for connection test, schema discovery, capability reporting, and execution of `ApprovedQuery`. Implement PostgreSQL and MySQL adapters with shared compatibility tests and dialect-specific policy suites.

## Consequences

Positive: isolates driver behavior, enables extension, and makes capabilities explicit.

Trade-off: lowest-common-denominator abstractions can hide useful features; normalization needs careful escape hatches.

## Alternatives considered

- Generic `JdbcTemplate` everywhere: leaks dialect behavior through the application.
- ORM for target databases: schemas are arbitrary and unknown at compile time.
- One service per database: unjustified operational overhead.

## Validation

Run the same connector contract suite against PostgreSQL and MySQL Testcontainers plus dialect-specific cases.

