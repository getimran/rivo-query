package com.knowimran.rivoquery.connection;

import java.util.EnumSet;
import java.util.Set;

public enum ConnectionState {
    PENDING_SECRET,
    PENDING_VALIDATION,
    ACTIVE,
    FAILED_VALIDATION,
    DISABLED,
    DELETING,
    DELETED;

    // PENDING_SECRET never carries a bound secret (a per-state invariant
    // DatabaseConnection's constructor enforces), so PENDING_VALIDATION -
    // which always requires one - is unconditionally unreachable from here
    // via the generic transition(); DatabaseConnection.attachCurrentSecret
    // is the only path from PENDING_SECRET into PENDING_VALIDATION, since it
    // supplies the secret as part of the same atomic move.
    private static final Set<ConnectionState> FROM_PENDING_SECRET = EnumSet.of(DELETING);
    private static final Set<ConnectionState> FROM_PENDING =
            EnumSet.of(ACTIVE, FAILED_VALIDATION, DISABLED, DELETING);
    private static final Set<ConnectionState> FROM_ACTIVE =
            EnumSet.of(PENDING_VALIDATION, DISABLED, DELETING);
    private static final Set<ConnectionState> FROM_FAILED =
            EnumSet.of(PENDING_VALIDATION, DISABLED, DELETING);
    private static final Set<ConnectionState> FROM_DISABLED =
            EnumSet.of(PENDING_VALIDATION, DELETING);
    private static final Set<ConnectionState> FROM_DELETING = EnumSet.of(DELETED);

    public boolean canTransitionTo(ConnectionState target) {
        if (target == null || target == this) {
            return false;
        }
        return switch (this) {
            case PENDING_SECRET -> FROM_PENDING_SECRET.contains(target);
            case PENDING_VALIDATION -> FROM_PENDING.contains(target);
            case ACTIVE -> FROM_ACTIVE.contains(target);
            case FAILED_VALIDATION -> FROM_FAILED.contains(target);
            case DISABLED -> FROM_DISABLED.contains(target);
            case DELETING -> FROM_DELETING.contains(target);
            case DELETED -> false;
        };
    }

    public boolean isPublicState() {
        return this != PENDING_SECRET && this != DELETED;
    }
}
