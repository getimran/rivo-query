package com.knowimran.rivoquery.platform.secrets;

import com.knowimran.rivoquery.connection.application.Deadline;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Shared deadline-usability check. A tiny standalone utility (rather than a
 * private method on {@link LocalAesGcmSecretStore}/{@link ManagedSecretStore}
 * themselves) so that neither adapter's own entry points ever call a method
 * whose declaring class is assignable to {@code SecretStore} - the
 * whole-codebase ArchUnit rule proving only {@code SecretCapabilityAuthority}
 * calls {@code SecretStore} matches on the called method's owner type, so
 * even an adapter's *own* private helper would otherwise register as such a
 * call once the adapter itself implements {@code SecretStore}.
 */
final class DeadlineChecks {
    private DeadlineChecks() {
    }

    static void requireUsable(Deadline deadline, Clock clock) {
        Objects.requireNonNull(deadline, "deadline").requireUsable(clock);
    }

    /** The earlier of two absolute instants, used to bound a lease by both a caller deadline and a grant deadline. */
    static Instant earlier(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
