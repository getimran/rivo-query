package com.knowimran.rivoquery.platform;

import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails application startup - loudly, before Flyway ever runs a migration -
 * when the control-database migration-owner role is missing or identical to
 * the application's own runtime datasource role.
 *
 * <p>{@code V2__create_identity_and_connection_control_plane.sql} grants the
 * runtime role ({@code spring.datasource.username}) only SELECT/INSERT on
 * {@code connection_lifecycle_audit_events} and revokes UPDATE/DELETE/
 * TRUNCATE, relying on that role never being the table's <em>owner</em>: an
 * owning role always retains {@code ALTER TABLE} rights (including
 * {@code ALTER TABLE ... DISABLE TRIGGER}) that no {@code REVOKE} can strip.
 * Every table Flyway creates is owned by whichever role Flyway itself
 * connects as ({@code spring.flyway.user}). Spring Boot's Flyway
 * autoconfiguration silently falls back to
 * {@code spring.datasource.username}/{@code password} whenever
 * {@code spring.flyway.user} is left unset, which would make Flyway - and
 * therefore table ownership - resolve to the exact same role the
 * application authenticates as, silently voiding the append-only guarantee
 * instead of failing loudly.
 *
 * <p>This class is registered as a {@link FlywayConfigurationCustomizer},
 * which Spring Boot invokes while constructing the {@code Flyway} bean -
 * strictly before {@code FlywayMigrationInitializer} (which depends on that
 * bean) ever calls {@code migrate()}. Throwing here therefore aborts
 * {@code ApplicationContext} refresh before any migration runs and before
 * the application can accept traffic.
 *
 * <p><strong>Residual risk:</strong> this check is configuration-shaped, not
 * privilege-shaped. It proves {@code spring.flyway.user} is set and differs
 * textually from {@code spring.datasource.username}; it cannot detect every
 * way a deployment could still end up with the runtime role holding
 * table-owner privileges (for example, an operator independently granting
 * an unrelated role equivalent ownership rights outside of this migration,
 * or Postgres role-name case folding making two differently-cased names
 * resolve to the same role). {@code ControlPlaneSchemaConstraintsIT} is the
 * runtime, privilege-level proof that the split this validator enforces
 * actually holds for a correctly configured deployment.
 */
@Component
public final class MigrationOwnerSeparationValidator implements FlywayConfigurationCustomizer {

    static final String MESSAGE =
            "spring.flyway.user must be set to a dedicated control-database migration-owner role, "
                    + "distinct from spring.datasource.username (the application's own runtime role). "
                    + "Refusing to start: without this split, Flyway would migrate as the same role the "
                    + "application connects with, making that role the owner of every table it creates - "
                    + "including connection_lifecycle_audit_events - which defeats the append-only "
                    + "guarantee (an owning role always retains ALTER TABLE ... DISABLE TRIGGER, which "
                    + "REVOKE cannot remove). Provision the migration-owner role out of band (see "
                    + "deploy/local/postgres-initdb/ and docs/OPERATIONS.md) and set "
                    + "SPRING_FLYWAY_USER/SPRING_FLYWAY_PASSWORD.";

    private final Environment environment;

    public MigrationOwnerSeparationValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void customize(FluentConfiguration configuration) {
        String migrationOwnerUser = environment.getProperty("spring.flyway.user");
        String runtimeUser = environment.getProperty("spring.datasource.username");
        if (migrationOwnerUser == null || migrationOwnerUser.isBlank() || migrationOwnerUser.equals(runtimeUser)) {
            throw new IllegalStateException(MESSAGE);
        }
    }
}
