package com.knowimran.rivoquery.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowimran.rivoquery.RivoQueryApplication;
import com.knowimran.rivoquery.platform.persistence.ControlDatabaseRoleFixture;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class PlatformMigrationIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("postgres:17.6-alpine3.22@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94")
            .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(POSTGRES_IMAGE);

    // A restricted role distinct from the container's bootstrap superuser
    // (which Flyway migrates as); see ControlDatabaseRoleFixture and V2's
    // own comments on why this split must hold and why V2 no longer creates
    // this role itself.
    private static final String RUNTIME_APP_USERNAME = "rivoquery_app_it";
    private static final String RUNTIME_APP_PASSWORD = "rivoquery-app-it-synthetic-pw";

    @Test
    void migratesFromEmptyAndStartsAgainWithoutChangingTheSchema() {
        try (ConfigurableApplicationContext firstStart = startApplication()) {
            JdbcTemplate jdbc = firstStart.getBean(JdbcTemplate.class);

            assertThat(jdbc.queryForObject(
                            "SELECT schema_version FROM platform_heartbeat WHERE singleton_id = 1",
                            String.class))
                    .isEqualTo("1");
            assertThat(appliedMigrationCount(jdbc)).isEqualTo(2);
        }

        try (ConfigurableApplicationContext repeatStart = startApplication()) {
            JdbcTemplate jdbc = repeatStart.getBean(JdbcTemplate.class);

            assertThat(appliedMigrationCount(jdbc)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM platform_heartbeat", Integer.class))
                    .isEqualTo(1);
        }
    }

    private ConfigurableApplicationContext startApplication() {
        // Idempotent: creates the role on the first call only, matching the
        // out-of-band provisioning V2 now requires (see the migration's own
        // comments); safe to call again across the repeat-start assertion.
        ControlDatabaseRoleFixture.createRuntimeRole(POSTGRES, RUNTIME_APP_USERNAME, RUNTIME_APP_PASSWORD);
        Map<String, Object> properties = Map.of(
                "spring.datasource.url", POSTGRES.getJdbcUrl(),
                "spring.datasource.username", RUNTIME_APP_USERNAME,
                "spring.datasource.password", RUNTIME_APP_PASSWORD,
                "spring.flyway.user", POSTGRES.getUsername(),
                "spring.flyway.password", POSTGRES.getPassword());

        return new SpringApplicationBuilder(RivoQueryApplication.class)
                .web(WebApplicationType.NONE)
                .properties(properties)
                .run("--debug=false");
    }

    private int appliedMigrationCount(JdbcTemplate jdbc) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success", Integer.class);
        return count == null ? 0 : count;
    }
}
