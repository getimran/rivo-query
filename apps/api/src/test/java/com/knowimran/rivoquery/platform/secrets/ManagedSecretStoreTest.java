package com.knowimran.rivoquery.platform.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowimran.rivoquery.connection.ConnectionState;
import com.knowimran.rivoquery.connection.ConnectionVersion;
import com.knowimran.rivoquery.connection.DatabaseConnection;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.application.Deadline;
import com.knowimran.rivoquery.connection.application.secret.BoundSecretReference;
import com.knowimran.rivoquery.connection.application.secret.DestructibleSecretMaterial;
import com.knowimran.rivoquery.connection.application.secret.SecretReference;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ManagedSecretStoreTest {
    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void stageFailsClosedThroughTheStubProviderAndConsumesTheMaterialExactlyOnce() {
        var managedStore = new ManagedSecretStore(
                new UnimplementedManagedSecretProvider(),
                SecretReferenceKey.of(AesGcmCipher.randomBytes(32)),
                "managed-kms-stub",
                CLOCK);
        var connection = DatabaseConnection.reserve(
                        SecretStoreTestFixtures.WORKSPACE, SecretStoreTestFixtures.CONNECTION, DatabaseKind.POSTGRESQL)
                .reserveNextSecretVersion(new ConnectionVersion(1));
        var binding = SecretStoreTestFixtures.binding(1);
        var authority = SecretStoreTestFixtures.authority(
                managedStore, connection, CLOCK,
                Optional.of(SecretStoreTestFixtures.stageOperation(SecretStoreTestFixtures.STAGE_OPERATION, binding)));

        try (var material = new DestructibleSecretMaterial("canary".getBytes(StandardCharsets.UTF_8))) {
            assertThatThrownBy(() -> authority.stage(
                            SecretStoreTestFixtures.actor(),
                            SecretStoreTestFixtures.CONNECTION,
                            binding,
                            material,
                            SecretStoreTestFixtures.STAGE_OPERATION,
                            new Deadline(NOW.plusSeconds(30))))
                    .isInstanceOf(SecretManagerNotConfiguredException.class)
                    .satisfies(ex -> SecretStoreTestFixtures.assertNoSecretLeakage(ex, "canary"));

            // The material was consumed (and zeroed) exactly once inside the
            // stub's fail-closed path, not silently discarded before or
            // retained after the failure.
            assertThatThrownBy(() -> material.consume(bytes -> bytes.length))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void resolveFailsClosedThroughTheStubProviderWithoutInvokingTheConsumer() {
        var managedStore = new ManagedSecretStore(
                new UnimplementedManagedSecretProvider(),
                SecretReferenceKey.of(AesGcmCipher.randomBytes(32)),
                "managed-kms-stub",
                CLOCK);
        var binding = SecretStoreTestFixtures.binding(1);
        var reference = new SecretReference("managed-kms-stub", "opaque-id-0123456789ab", 1, SecretBindingCodec.fingerprint(binding));
        var boundSecret = new BoundSecretReference(binding, reference);
        var connection = DatabaseConnection.reserve(
                        SecretStoreTestFixtures.WORKSPACE, SecretStoreTestFixtures.CONNECTION, DatabaseKind.POSTGRESQL)
                .reserveNextSecretVersion(new ConnectionVersion(1));
        var withSecret = connection.attachCurrentSecret(connection.version(), boundSecret);
        var active = withSecret.transition(withSecret.version(), ConnectionState.ACTIVE);
        var authority = SecretStoreTestFixtures.authority(managedStore, active, CLOCK);

        var invoked = new AtomicBoolean();
        assertThatThrownBy(() -> authority.consume(
                        SecretStoreTestFixtures.actor(),
                        SecretStoreTestFixtures.CONNECTION,
                        SecretStoreTestFixtures.connectionTest(),
                        new Deadline(NOW.plusSeconds(30)),
                        bytes -> invoked.set(true)))
                .isInstanceOf(SecretManagerNotConfiguredException.class)
                .satisfies(ex -> SecretStoreTestFixtures.assertNoSecretLeakage(ex, "opaque-id-0123456789ab"));
        assertThat(invoked).isFalse();
    }

    @Test
    void constructorRejectsAnInvalidProviderNamespace() {
        assertThatThrownBy(() -> new ManagedSecretStore(
                        new UnimplementedManagedSecretProvider(),
                        SecretReferenceKey.of(AesGcmCipher.randomBytes(32)),
                        "Not_A_Valid_Namespace!",
                        CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
