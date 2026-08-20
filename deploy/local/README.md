# Local dependencies

This Compose project runs the RivoQuery control-plane PostgreSQL dependency for
local development. It does not represent the production deployment topology.

## Start

Docker Engine (or another Docker-compatible runtime) and Compose are required.
The commands below use the standalone `docker-compose` executable installed in
the project environment. If Compose is installed as a Docker CLI plugin, replace
`docker-compose` with `docker compose`. From the repository root:

```bash
cp .env.example .env
docker-compose --env-file .env -f deploy/local/compose.yaml up -d --wait
docker-compose --env-file .env -f deploy/local/compose.yaml ps
```

The example credentials are intentionally synthetic and local-only. Keep real
credentials in an external secret provider; never add them to `.env.example`,
Compose files, command history, logs, or source control. If the local port is
already occupied, change both `RIVOQUERY_CONTROL_DB_PORT` and the port in
`SPRING_DATASOURCE_URL` in the ignored `.env` file.

The database port binds only to `127.0.0.1` and is therefore unavailable through
the host's non-loopback interfaces. The container uses Compose's project-scoped
bridge network because Colima cannot forward a published host port from an
internal-only network. PostgreSQL data is held in the `control-postgres-data`
named volume, not in the repository. The image is pinned to PostgreSQL 17.6 on
Alpine 3.22 and its multi-platform image digest so local builds are reproducible;
update the tag and digest together through dependency review.

PostgreSQL applies the database name and credentials only while initializing an
empty volume. Editing `.env` later does not rotate an existing local role; use a
database role-rotation procedure or deliberately reset the local volume.

## Stop or reset

Stop the dependency while preserving local data:

```bash
docker-compose --env-file .env -f deploy/local/compose.yaml down
```

To deliberately destroy the local control database and migrate again from an
empty volume:

```bash
docker-compose --env-file .env -f deploy/local/compose.yaml down --volumes
```

The second command permanently deletes this Compose project's local database
volume. It must never be used against a shared or production environment.

## Validate

```bash
docker-compose --env-file .env.example -f deploy/local/compose.yaml config --quiet
docker-compose --env-file .env.example -f deploy/local/compose.yaml up -d --wait
docker-compose --env-file .env.example -f deploy/local/compose.yaml exec -T control-postgres \
  pg_isready -U rivoquery_local -d rivoquery_control
docker-compose --env-file .env.example -f deploy/local/compose.yaml down --volumes
```
