package com.knowimran.rivoquery.platform;

import com.knowimran.rivoquery.catalog.application.CatalogRepository;
import com.knowimran.rivoquery.catalog.application.SchemaDiscoveryPort;
import com.knowimran.rivoquery.catalog.application.SchemaQueryService;
import com.knowimran.rivoquery.catalog.application.SchemaRefreshConnectionFactory;
import com.knowimran.rivoquery.catalog.application.SchemaRefreshOperationRepository;
import com.knowimran.rivoquery.catalog.application.SchemaRefreshRequestService;
import com.knowimran.rivoquery.catalog.application.SchemaRefreshService;
import com.knowimran.rivoquery.catalog.application.SchemaSelectorPort;
import com.knowimran.rivoquery.catalog.application.SchemaSelectorService;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.application.ConnectionProfileRepository;
import com.knowimran.rivoquery.connection.application.ConnectionRepository;
import com.knowimran.rivoquery.connection.application.lifecycle.CanonicalIdGenerator;
import com.knowimran.rivoquery.connection.application.secret.SecretOperations;
import com.knowimran.rivoquery.platform.connector.ConnectorDialect;
import com.knowimran.rivoquery.platform.connector.MysqlConnectorDialect;
import com.knowimran.rivoquery.platform.connector.PostgresqlConnectorDialect;
import com.knowimran.rivoquery.platform.connector.mysql.MysqlSchemaDiscovery;
import com.knowimran.rivoquery.platform.connector.postgresql.PostgresqlSchemaDiscovery;
import com.knowimran.rivoquery.platform.operation.SchemaRefreshJdbcConnectionFactory;
import com.knowimran.rivoquery.catalog.application.SchemaRefreshWorkerPort;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SchemaWiringConfiguration {

    @Bean
    SchemaQueryService schemaQueryService(CatalogRepository catalogRepository) {
        return new SchemaQueryService(catalogRepository);
    }

    @Bean
    SchemaSelectorService schemaSelectorService() {
        return new SchemaSelectorService();
    }

    @Bean
    SchemaDiscoverySelector schemaDiscoverySelector(
            PostgresqlSchemaDiscovery postgresql,
            MysqlSchemaDiscovery mysql) {
        return new SchemaDiscoverySelector(postgresql, mysql);
    }

    @Bean
    SchemaRefreshService schemaRefreshService(
            CatalogRepository catalogRepository,
            SchemaDiscoverySelector schemaDiscoverySelector,
            CanonicalIdGenerator idGenerator) {
        return new SchemaRefreshService(catalogRepository, schemaDiscoverySelector, idGenerator);
    }

    @Bean
    SchemaRefreshWorkerPort schemaRefreshWorker(
            SchemaRefreshOperationRepository operationRepository,
            Clock clock) {
        return new com.knowimran.rivoquery.platform.operation.SchemaRefreshWorker(operationRepository, clock, "schema-refresh-worker");
    }

    @Bean
    SchemaRefreshJdbcConnectionFactory schemaRefreshConnectionFactory(
            ConnectionRepository connections,
            ConnectionProfileRepository profiles,
            SecretOperations secretOperations) {
        return new SchemaRefreshJdbcConnectionFactory(
                connections, profiles, secretOperations, new PostgresqlConnectorDialect(), new MysqlConnectorDialect());
    }

    @Bean
    com.knowimran.rivoquery.catalog.application.SchemaRefreshOrchestrator schemaRefreshOrchestrator(
            SchemaRefreshWorkerPort worker,
            SchemaRefreshService refreshService,
            CatalogRepository catalogRepository,
            SchemaRefreshOperationRepository operationRepository,
            SchemaRefreshConnectionFactory connectionFactory,
            Clock clock) {
        return new com.knowimran.rivoquery.catalog.application.SchemaRefreshOrchestrator(
                worker, refreshService, catalogRepository, operationRepository, connectionFactory, clock);
    }

    @Bean
    SchemaRefreshRequestService schemaRefreshRequestService(com.knowimran.rivoquery.catalog.application.SchemaRefreshOrchestrator orchestrator) {
        return new SchemaRefreshRequestService(orchestrator);
    }
}
