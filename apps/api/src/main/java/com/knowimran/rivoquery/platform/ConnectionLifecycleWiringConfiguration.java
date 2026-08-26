package com.knowimran.rivoquery.platform;

import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.application.ConnectionAuditPort;
import com.knowimran.rivoquery.connection.application.ConnectionPermissionRepository;
import com.knowimran.rivoquery.connection.application.ConnectionRepository;
import com.knowimran.rivoquery.connection.application.DurableOperationRepository;
import com.knowimran.rivoquery.connection.application.destination.DestinationOperations;
import com.knowimran.rivoquery.connection.application.lifecycle.CanonicalIdGenerator;
import com.knowimran.rivoquery.connection.application.lifecycle.ConnectionLifecycleService;
import com.knowimran.rivoquery.connection.application.lifecycle.ConnectionQueryService;
import com.knowimran.rivoquery.connection.application.lifecycle.CurrentDestinationPolicyVersion;
import com.knowimran.rivoquery.connection.application.lifecycle.InMemoryDiscoveryEnqueuer;
import com.knowimran.rivoquery.connection.application.lifecycle.NoOpTargetPoolManager;
import com.knowimran.rivoquery.connection.application.lifecycle.SecretOperationAuthorizationRegistry;
import com.knowimran.rivoquery.connection.application.lifecycle.SecureRandomCanonicalIdGenerator;
import com.knowimran.rivoquery.connection.application.secret.SecretCapabilityAuthority;
import com.knowimran.rivoquery.connection.application.secret.SecretOperations;
import com.knowimran.rivoquery.connection.application.secret.SecretStore;
import com.knowimran.rivoquery.connection.connector.DatabaseConnector;
import com.knowimran.rivoquery.connection.connector.DatabaseConnectorSelector;
import com.knowimran.rivoquery.connection.pool.TargetPoolManager;
import com.knowimran.rivoquery.platform.connector.CapabilityReportingDatabaseConnector;
import com.knowimran.rivoquery.platform.connector.DatabaseConnectorSelectorImpl;
import com.knowimran.rivoquery.platform.connector.MysqlConnectorDialect;
import com.knowimran.rivoquery.platform.connector.PostgresqlConnectorDialect;
import com.knowimran.rivoquery.platform.connector.mysql.MysqlPhysicalJdbcConnector;
import com.knowimran.rivoquery.platform.connector.postgresql.PostgresqlPhysicalJdbcConnector;
import com.knowimran.rivoquery.platform.secrets.KeyEncryptionKeyRing;
import com.knowimran.rivoquery.platform.secrets.LocalAesGcmSecretStore;
import com.knowimran.rivoquery.platform.secrets.SecretReferenceKey;
import com.knowimran.rivoquery.platform.secrets.SecureRandomGrantNonceSource;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the {@link ConnectionLifecycleService}/{@link ConnectionQueryService}
 * bean graph into the default {@code ApplicationContext} for the first time
 * (P2-100 deliberately deferred this - see its own evidence in
 * docs/IMPLEMENTATION_TASKS.md, "ready for whichever task lands the real
 * adapters to wire them"). Uses the already-reviewed local-dev {@link
 * LocalAesGcmSecretStore} (P2-070) and the already-reviewed physical
 * connectors (P2-110/P2-120) exactly as published; this class only
 * instantiates them, it does not modify {@code platform.secrets} or {@code
 * platform.connector.{postgresql,mysql}}. Managed/KMS secret-provider
 * selection, key-rotation operations, and deployment-environment key
 * injection remain P2-180's explicit charter (devops); see {@code
 * rivoquery.secrets.local.*} below for the local/CI-only env-driven wiring
 * this task provides so the application can boot and be exercised end to
 * end today.
 *
 * <p><b>Provider safety rail (post-review S6 fix):</b> {@link #secretStore}
 * was previously wired unconditionally - nothing prevented a
 * production-shaped deployment from silently ending up on this local,
 * file-backed adapter. {@code rivoquery.secrets.provider} ({@code
 * RIVOQUERY_SECRETS_PROVIDER}) must now be set to exactly {@code "local"}
 * for this bean to activate at all; any other value (including unset) fails
 * closed at startup with an explicit message, matching this codebase's
 * established fail-closed-at-startup convention (e.g. {@code
 * MigrationOwnerSeparationValidator}). This is an interim safety rail, not a
 * provider-selection mechanism: managed/KMS provider selection (a real
 * {@code rivoquery.secrets.provider=kms}-style branch) remains P2-180's own
 * charter.
 */
@Configuration
public class ConnectionLifecycleWiringConfiguration {

    private static final String LOCAL_PROVIDER = "local";

    @Bean
    Clock lifecycleClock() {
        return Clock.systemUTC();
    }

    @Bean
    SecretStore secretStore(@Value("${rivoquery.secrets.provider:}") String provider) {
        if (!LOCAL_PROVIDER.equals(provider)) {
            throw new IllegalStateException(
                    "rivoquery.secrets.provider (RIVOQUERY_SECRETS_PROVIDER) must be explicitly set to \"local\" "
                            + "to use the local file-backed SecretStore; got "
                            + (provider == null || provider.isBlank() ? "<unset>" : "\"" + provider + "\"")
                            + ". A managed/KMS provider is P2-180's charter; this deliberate opt-in prevents a "
                            + "production-shaped deployment from silently ending up on local disk storage.");
        }
        String storageDirectory = requireEnv("RIVOQUERY_SECRETS_LOCAL_STORAGE_DIR");
        KeyEncryptionKeyRing keyRing = KeyEncryptionKeyRing.fromEnvironment(System::getenv);
        SecretReferenceKey referenceKey = SecretReferenceKey.fromEnvironment(System::getenv);
        return new LocalAesGcmSecretStore(Path.of(storageDirectory), keyRing, referenceKey, Clock.systemUTC());
    }

    @Bean
    SecretOperationAuthorizationRegistry secretOperationAuthorizationRegistry(Clock lifecycleClock) {
        return new SecretOperationAuthorizationRegistry(lifecycleClock, Duration.ofMinutes(5));
    }

    @Bean
    SecretOperations secretOperations(
            ConnectionRepository connections,
            SecretOperationAuthorizationRegistry authorizations,
            SecretStore secretStore,
            Clock lifecycleClock) {
        return new SecretCapabilityAuthority(
                connections, authorizations, secretStore, new SecureRandomGrantNonceSource(),
                lifecycleClock, Duration.ofMinutes(5));
    }

    @Bean
    DatabaseConnectorSelector databaseConnectorSelector(Clock lifecycleClock) {
        DatabaseConnector postgresql = new CapabilityReportingDatabaseConnector(
                new PostgresqlConnectorDialect(), new PostgresqlPhysicalJdbcConnector(lifecycleClock), lifecycleClock);
        DatabaseConnector mysql = new CapabilityReportingDatabaseConnector(
                new MysqlConnectorDialect(), new MysqlPhysicalJdbcConnector(lifecycleClock), lifecycleClock);
        return new DatabaseConnectorSelectorImpl(
                Map.of(DatabaseKind.POSTGRESQL, postgresql, DatabaseKind.MYSQL, mysql));
    }

    @Bean
    TargetPoolManager targetPoolManager() {
        return new NoOpTargetPoolManager();
    }

    @Bean
    CanonicalIdGenerator canonicalIdGenerator() {
        return new SecureRandomCanonicalIdGenerator();
    }

    @Bean
    CurrentDestinationPolicyVersion currentDestinationPolicyVersion() {
        // A fixed configuration tag rather than a per-request value; see
        // CurrentDestinationPolicyVersion's own Javadoc. Operator-facing
        // policy versioning is out of this task's scope (P2-180/ops).
        return () -> "policy-v1";
    }

    @Bean
    ConnectionLifecycleService connectionLifecycleService(
            ConnectionRepository connections,
            DurableOperationRepository operations,
            ConnectionAuditPort auditPort,
            SecretOperations secretOperations,
            SecretOperationAuthorizationRegistry secretAuthorizations,
            DestinationOperations destinationOperations,
            CurrentDestinationPolicyVersion currentDestinationPolicyVersion,
            DatabaseConnectorSelector databaseConnectorSelector,
            TargetPoolManager targetPoolManager,
            CanonicalIdGenerator canonicalIdGenerator,
            Clock lifecycleClock) {
        return new ConnectionLifecycleService(
                connections, operations, auditPort, secretOperations, secretAuthorizations,
                destinationOperations, currentDestinationPolicyVersion, databaseConnectorSelector,
                targetPoolManager, new InMemoryDiscoveryEnqueuer(), canonicalIdGenerator, lifecycleClock);
    }

    @Bean
    ConnectionQueryService connectionQueryService(
            ConnectionRepository connections, ConnectionPermissionRepository permissions) {
        return new ConnectionQueryService(connections, permissions);
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set");
        }
        return value;
    }
}
