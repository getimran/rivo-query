#!/usr/bin/env bash
# Provisions the restricted runtime application role that
# V2__create_identity_and_connection_control_plane.sql grants privileges to
# but deliberately never creates itself (see that migration's own comments).
#
# This script runs via Postgres's own docker-entrypoint-initdb.d mechanism:
# the official postgres image executes every *.sh/*.sql file here exactly
# once, on first startup of an empty data volume, using a local connection
# already authenticated as POSTGRES_USER (the migration-owner/bootstrap
# superuser configured in compose.yaml). Nothing here goes through the
# application's or Flyway's own connection, so this role's password never
# appears in a connection Flyway or the app itself opens, logs, or traces -
# unlike embedding it as a Flyway-substituted SQL literal, which is exactly
# what this split avoids.
#
# The runtime role's own credentials come from RIVOQUERY_RUNTIME_DB_USER/
# RIVOQUERY_RUNTIME_DB_PASSWORD (compose.yaml), which mirror
# SPRING_DATASOURCE_USERNAME/PASSWORD (.env.example) - the exact identity
# the running application authenticates as.
set -euo pipefail

: "${RIVOQUERY_RUNTIME_DB_USER:?RIVOQUERY_RUNTIME_DB_USER must be set}"
: "${RIVOQUERY_RUNTIME_DB_PASSWORD:?RIVOQUERY_RUNTIME_DB_PASSWORD must be set}"

# psql's `:'var'` token safely quotes each value as a single SQL string
# literal (embedded single quotes are escaped) wherever it appears outside
# an already-open string literal, so the runtime role's password can never
# break out of the intended literal or be reinterpreted as SQL - the same
# failure mode V2 itself now avoids by never embedding a password in
# Flyway-substituted SQL text. `:'var'` substitution is *not* performed
# inside a dollar-quoted ($$...$$) PL/pgSQL body, so this deliberately uses
# `\gexec` (build the CREATE/ALTER ROLE statement as a plain string result,
# then execute it) instead of a DO block, so each `:'var'` token stays
# outside of any string literal at substitution time.
psql -v ON_ERROR_STOP=1 \
    --username "$POSTGRES_USER" \
    --dbname "$POSTGRES_DB" \
    -v runtime_user="$RIVOQUERY_RUNTIME_DB_USER" \
    -v runtime_password="$RIVOQUERY_RUNTIME_DB_PASSWORD" <<'EOSQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'runtime_user', :'runtime_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = :'runtime_user')
\gexec

SELECT format('ALTER ROLE %I LOGIN PASSWORD %L', :'runtime_user', :'runtime_password')
WHERE EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = :'runtime_user')
\gexec
EOSQL
