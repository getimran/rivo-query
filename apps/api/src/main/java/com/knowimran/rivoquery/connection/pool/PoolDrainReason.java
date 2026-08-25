package com.knowimran.rivoquery.connection.pool;

public enum PoolDrainReason {
    CONFIGURATION_CHANGED,
    SECRET_ROTATED,
    POLICY_CHANGED,
    DISABLED,
    DELETING,
    SHUTDOWN
}
