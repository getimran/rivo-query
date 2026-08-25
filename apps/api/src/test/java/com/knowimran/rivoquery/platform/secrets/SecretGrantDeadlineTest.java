package com.knowimran.rivoquery.platform.secrets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowimran.rivoquery.connection.ConnectionState;
import com.knowimran.rivoquery.connection.ConnectionVersion;
import com.knowimran.rivoquery.connection.DatabaseConnection;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.application.Deadline;
import com.knowimran.rivoquery.connection.application.DeadlineExceededException;
import com.knowimran.rivoquery.connection.application.secret.DestructibleSecretMaterial;
import com.knowimran.rivoquery.connection.application.secret.StagedSecretReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves {@code stage}/{@code resolve}/{@code revoke} each independently
 * reject an already-expired <em>grant</em> deadline even when the
 * caller-supplied {@link Deadline} passed to that same direct call is still
 * fresh - the gap where only the caller-supplied deadline, and not the
 * grant's own deadline, was checked on these entry points. A shared
 * {@link MutableClock} lets a legitimate grant be minted with a short
 * deadline and later observed as expired without sleeping the test thread.
 */
class SecretGrantDeadlineTest {
    private static final Instant T0 = Instant.parse("2026-08-24T12:00:00Z");

    @TempDir
    Path directory;

    @Test
    void stageRejectsAnAlreadyExpiredGrantDeadlineDespiteAFreshCallerDeadline() {
        var clock = new MutableClock(T0, ZoneOffset.UTC);
        var store = newStore(directory, clock);
        var binding = SecretStoreTestFixtures.binding(1);
        var connection = DatabaseConnection.reserve(
                        SecretStoreTestFixtures.WORKSPACE, SecretStoreTestFixtures.CONNECTION, DatabaseKind.POSTGRESQL)
                .reserveNextSecretVersion(new ConnectionVersion(1));
        var authority = SecretStoreTestFixtures.authority(store, connection, clock, Optional.of(
                SecretStoreTestFixtures.stageOperation(SecretStoreTestFixtures.STAGE_OPERATION, binding)));

        try (var material = new DestructibleSecretMaterial("canary-password".getBytes(StandardCharsets.UTF_8))) {
            authority.stage(
                    SecretStoreTestFixtures.actor(),
                    SecretStoreTestFixtures.CONNECTION,
                    binding,
                    material,
                    SecretStoreTestFixtures.STAGE_OPERATION,
                    new Deadline(T0.plusSeconds(1)));
        }
        var mutationGrant = store.lastMutationGrant();

        clock.advanceTo(T0.plusSeconds(10));
        var freshDeadline = new Deadline(clock.instant().plusSeconds(30));

        try (var material = new DestructibleSecretMaterial("canary-password".getBytes(StandardCharsets.UTF_8))) {
            assertThatThrownBy(() -> store.stage(binding, material, mutationGrant, freshDeadline))
                    .isInstanceOf(DeadlineExceededException.class);
        }
    }

    @Test
    void resolveRejectsAnAlreadyExpiredGrantDeadlineDespiteAFreshCallerDeadline() {
        var clock = new MutableClock(T0, ZoneOffset.UTC);
        var store = newStore(directory, clock);
        var binding = SecretStoreTestFixtures.binding(1);
        var connection = DatabaseConnection.reserve(
                        SecretStoreTestFixtures.WORKSPACE, SecretStoreTestFixtures.CONNECTION, DatabaseKind.POSTGRESQL)
                .reserveNextSecretVersion(new ConnectionVersion(1));
        var stageAuthority = SecretStoreTestFixtures.authority(store, connection, clock, Optional.of(
                SecretStoreTestFixtures.stageOperation(SecretStoreTestFixtures.STAGE_OPERATION, binding)));
        StagedSecretReference staged;
        try (var material = new DestructibleSecretMaterial("canary-password".getBytes(StandardCharsets.UTF_8))) {
            staged = stageAuthority.stage(
                    SecretStoreTestFixtures.actor(),
                    SecretStoreTestFixtures.CONNECTION,
                    binding,
                    material,
                    SecretStoreTestFixtures.STAGE_OPERATION,
                    new Deadline(T0.plusSeconds(30)));
        }
        var withSecret = connection.attachCurrentSecret(connection.version(), staged.value());
        var active = withSecret.transition(withSecret.version(), ConnectionState.ACTIVE);
        var consumeAuthority = SecretStoreTestFixtures.authority(store, active, clock);

        consumeAuthority.consume(
                SecretStoreTestFixtures.actor(),
                SecretStoreTestFixtures.CONNECTION,
                SecretStoreTestFixtures.connectionTest(),
                new Deadline(T0.plusSeconds(1)),
                bytes -> { });
        var accessGrant = store.lastAccessGrant();

        clock.advanceTo(T0.plusSeconds(10));
        var freshDeadline = new Deadline(clock.instant().plusSeconds(30));

        assertThatThrownBy(() -> store.resolve(staged.value().reference(), accessGrant, freshDeadline))
                .isInstanceOf(DeadlineExceededException.class);
    }

    @Test
    void revokeRejectsAnAlreadyExpiredGrantDeadlineDespiteAFreshCallerDeadline() {
        var clock = new MutableClock(T0, ZoneOffset.UTC);
        var store = newStore(directory, clock);
        var binding = SecretStoreTestFixtures.binding(1);
        var connection = DatabaseConnection.reserve(
                        SecretStoreTestFixtures.WORKSPACE, SecretStoreTestFixtures.CONNECTION, DatabaseKind.POSTGRESQL)
                .reserveNextSecretVersion(new ConnectionVersion(1));
        var stageAuthority = SecretStoreTestFixtures.authority(store, connection, clock, Optional.of(
                SecretStoreTestFixtures.stageOperation(SecretStoreTestFixtures.STAGE_OPERATION, binding)));
        StagedSecretReference staged;
        try (var material = new DestructibleSecretMaterial("canary-password".getBytes(StandardCharsets.UTF_8))) {
            staged = stageAuthority.stage(
                    SecretStoreTestFixtures.actor(),
                    SecretStoreTestFixtures.CONNECTION,
                    binding,
                    material,
                    SecretStoreTestFixtures.STAGE_OPERATION,
                    new Deadline(T0.plusSeconds(30)));
        }
        var withSecret = connection.attachCurrentSecret(connection.version(), staged.value());
        var active = withSecret.transition(withSecret.version(), ConnectionState.ACTIVE);
        var deleting = active.transition(active.version(), ConnectionState.DELETING);
        var revokeAuthority = SecretStoreTestFixtures.authority(store, deleting, clock, Optional.of(
                SecretStoreTestFixtures.revokeOperation(
                        SecretStoreTestFixtures.REVOKE_OPERATION, binding, staged.value().reference())));

        revokeAuthority.revokeForOperation(
                SecretStoreTestFixtures.WORKSPACE,
                SecretStoreTestFixtures.REVOKE_OPERATION,
                SecretStoreTestFixtures.CONNECTION,
                staged.value(),
                new Deadline(T0.plusSeconds(1)));
        var revocationGrant = store.lastRevocationGrant();

        clock.advanceTo(T0.plusSeconds(10));
        var freshDeadline = new Deadline(clock.instant().plusSeconds(30));

        assertThatThrownBy(() -> store.revoke(staged.value().reference(), revocationGrant, freshDeadline))
                .isInstanceOf(DeadlineExceededException.class);
    }

    private static CapturingSecretStore newStore(Path directory, MutableClock clock) {
        return new CapturingSecretStore(new LocalAesGcmSecretStore(
                directory,
                KeyEncryptionKeyRing.of(Map.of("v1", AesGcmCipher.randomBytes(32)), "v1"),
                SecretReferenceKey.of(AesGcmCipher.randomBytes(32)),
                clock));
    }
}
