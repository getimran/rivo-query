package com.knowimran.rivoquery.platform;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowimran.rivoquery.RivoQueryApplication;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves {@link MigrationOwnerSeparationValidator} actually stops
 * application startup end-to-end - not merely in isolation - for the exact
 * default-configuration degradation it exists to close: an operator who
 * only sets {@code spring.datasource.*} (never {@code spring.flyway.user}/
 * {@code password}), which otherwise makes Spring Boot's Flyway
 * autoconfiguration silently reuse the runtime datasource role as the
 * migration owner. A reachable, correctly-credentialed PostgreSQL container
 * is used throughout so these failures are demonstrably caused by the
 * migration-owner/runtime-role check itself, not by an unreachable
 * database.
 */
@Testcontainers
class MigrationOwnerSeparationIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("postgres:17.6-alpine3.22@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94")
            .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE);

    @Test
    void applicationRefusesToStartWhenSpringFlywayUserIsNeverSet() {
        // This is the reviewer-reproduced default-misconfiguration shape:
        // only spring.datasource.* is configured, exactly as an operator
        // following application.yaml/.env.example without the
        // SPRING_FLYWAY_USER/PASSWORD split would end up.
        Map<String, Object> properties = Map.of(
                "spring.datasource.url", POSTGRES.getJdbcUrl(),
                "spring.datasource.username", POSTGRES.getUsername(),
                "spring.datasource.password", POSTGRES.getPassword());

        assertThatThrownBy(() -> new SpringApplicationBuilder(RivoQueryApplication.class)
                        .web(WebApplicationType.NONE)
                        .properties(properties)
                        .run("--debug=false"))
                .hasStackTraceContaining(MigrationOwnerSeparationValidator.MESSAGE);
    }

    @Test
    void applicationRefusesToStartWhenSpringFlywayUserEqualsSpringDatasourceUsername() {
        Map<String, Object> properties = Map.of(
                "spring.datasource.url", POSTGRES.getJdbcUrl(),
                "spring.datasource.username", POSTGRES.getUsername(),
                "spring.datasource.password", POSTGRES.getPassword(),
                "spring.flyway.user", POSTGRES.getUsername(),
                "spring.flyway.password", POSTGRES.getPassword());

        assertThatThrownBy(() -> new SpringApplicationBuilder(RivoQueryApplication.class)
                        .web(WebApplicationType.NONE)
                        .properties(properties)
                        .run("--debug=false"))
                .hasStackTraceContaining(MigrationOwnerSeparationValidator.MESSAGE);
    }
}
