package com.knowimran.rivoquery.connection.pool;

import com.knowimran.rivoquery.connection.application.Deadline;

public interface TargetPoolManager {
    void stopNewBorrowsAndDrain(PoolKey key, PoolDrainReason reason, Deadline deadline);
}
