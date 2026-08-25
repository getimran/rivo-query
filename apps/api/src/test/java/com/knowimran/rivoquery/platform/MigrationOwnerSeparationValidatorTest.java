package com.knowimran.rivoquery.platform;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

/**
 * Direct unit coverage for {@link MigrationOwnerSeparationValidator}'s
 * decision logic, independent of Flyway/Spring Boot's own wiring order (see
 * {@code MigrationOwnerSeparationIT} for the end-to-end proof that this
 * actually stops application startup).
 */
class MigrationOwnerSeparationValidatorTest {

    private static final FluentConfiguration IGNORED_CONFIGURATION = new FluentConfiguration();

    @Test
    void rejectsAMissingMigrationOwnerUser() {
        Environment environment = environmentWith(Map.of("spring.datasource.username", "rivoquery_app"));

        assertThatThrownBy(() -> new MigrationOwnerSeparationValidator(environment).customize(IGNORED_CONFIGURATION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(MigrationOwnerSeparationValidator.MESSAGE);
    }

    @Test
    void rejectsABlankMigrationOwnerUser() {
        Environment environment = environmentWith(Map.of(
                "spring.flyway.user", "   ",
                "spring.datasource.username", "rivoquery_app"));

        assertThatThrownBy(() -> new MigrationOwnerSeparationValidator(environment).customize(IGNORED_CONFIGURATION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(MigrationOwnerSeparationValidator.MESSAGE);
    }

    @Test
    void rejectsAMigrationOwnerUserThatIsTheSameRoleAsTheRuntimeDatasourceUser() {
        Environment environment = environmentWith(Map.of(
                "spring.flyway.user", "rivoquery_app",
                "spring.datasource.username", "rivoquery_app"));

        assertThatThrownBy(() -> new MigrationOwnerSeparationValidator(environment).customize(IGNORED_CONFIGURATION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(MigrationOwnerSeparationValidator.MESSAGE);
    }

    @Test
    void acceptsADistinctNonBlankMigrationOwnerUser() {
        Environment environment = environmentWith(Map.of(
                "spring.flyway.user", "rivoquery_migrator",
                "spring.datasource.username", "rivoquery_app"));

        assertThatNoException()
                .isThrownBy(() -> new MigrationOwnerSeparationValidator(environment).customize(IGNORED_CONFIGURATION));
    }

    private static Environment environmentWith(Map<String, String> properties) {
        MockEnvironment environment = new MockEnvironment();
        properties.forEach(environment::setProperty);
        return environment;
    }
}
