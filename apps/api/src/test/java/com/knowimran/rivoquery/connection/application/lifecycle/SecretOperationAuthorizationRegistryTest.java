package com.knowimran.rivoquery.connection.application.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.OperationId;
import com.knowimran.rivoquery.connection.application.secret.DurableSecretAuthorization.AuthorizedOperation;
import com.knowimran.rivoquery.connection.application.secret.SecretBinding;
import com.knowimran.rivoquery.connection.application.secret.SecretOperationKind;
import com.knowimran.rivoquery.connection.application.secret.SecretPurpose;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SecretOperationAuthorizationRegistryTest {
    private static final String ULID = "01J8QK3H0RT8N4YB2C6W9V1MFP";
    private static final WorkspaceId WORKSPACE = new WorkspaceId("wsp_" + ULID);
    private static final ConnectionId CONNECTION = new ConnectionId("con_" + ULID);
    private static final OperationId OPERATION = new OperationId("op_" + ULID);
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");

    @Test
    void findReturnsExactlyWhatWasRegisteredUntilReleased() {
        var clock = new AtomicReference<>(Clock.fixed(NOW, ZoneOffset.UTC));
        var registry = new SecretOperationAuthorizationRegistry(new DelegatingClock(clock), Duration.ofMinutes(5));
        var operation = operation();

        assertThat(registry.findByWorkspaceAndId(WORKSPACE, OPERATION)).isEmpty();

        registry.register(operation);
        assertThat(registry.findByWorkspaceAndId(WORKSPACE, OPERATION)).contains(operation);
        assertThat(registry.findByWorkspaceAndId(new WorkspaceId("wsp_01J8QK3H0RT8N4YB2C6W9V1MFQ"), OPERATION))
                .isEmpty();

        registry.release(WORKSPACE, OPERATION);
        assertThat(registry.findByWorkspaceAndId(WORKSPACE, OPERATION)).isEmpty();
    }

    @Test
    void entriesExpireAfterTheirTimeToLiveWithoutExplicitRelease() {
        var clock = new AtomicReference<>(Clock.fixed(NOW, ZoneOffset.UTC));
        var registry = new SecretOperationAuthorizationRegistry(new DelegatingClock(clock), Duration.ofMinutes(1));

        registry.register(operation());
        assertThat(registry.findByWorkspaceAndId(WORKSPACE, OPERATION)).isPresent();

        clock.set(Clock.fixed(NOW.plus(Duration.ofMinutes(2)), ZoneOffset.UTC));
        assertThat(registry.findByWorkspaceAndId(WORKSPACE, OPERATION)).isEmpty();
    }

    @Test
    void constructorRejectsNonPositiveTimeToLive() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new SecretOperationAuthorizationRegistry(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AuthorizedOperation operation() {
        var binding = new SecretBinding(WORKSPACE, CONNECTION, SecretPurpose.TARGET_DATABASE_CONNECTION, 1);
        return new AuthorizedOperation(
                WORKSPACE, OPERATION, Optional.empty(), SecretOperationKind.STAGE, binding,
                Optional.empty(), Optional.empty(), false);
    }

    /** Lets a single test swap the effective "now" between register/find calls. */
    private static final class DelegatingClock extends Clock {
        private final AtomicReference<Clock> delegate;

        DelegatingClock(AtomicReference<Clock> delegate) {
            this.delegate = delegate;
        }

        @Override
        public java.time.ZoneId getZone() {
            return delegate.get().getZone();
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return delegate.get().withZone(zone);
        }

        @Override
        public Instant instant() {
            return delegate.get().instant();
        }
    }
}
