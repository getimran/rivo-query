package com.knowimran.rivoquery.platform.destination.pool;

import com.knowimran.rivoquery.connection.application.destination.DestinationAssessment;
import com.knowimran.rivoquery.connection.pool.PoolKey;
import java.util.Objects;

/**
 * The destination binding a pool is currently lending against: its key, and the assessment
 * (fingerprint, policy version, expiry) that was current the last time the binding was validated
 * or renewed.
 */
public record PoolDestinationBinding(PoolKey key, DestinationAssessment assessment) {
    public PoolDestinationBinding {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(assessment, "assessment");
    }

    @Override
    public String toString() {
        return "PoolDestinationBinding[REDACTED]";
    }
}
