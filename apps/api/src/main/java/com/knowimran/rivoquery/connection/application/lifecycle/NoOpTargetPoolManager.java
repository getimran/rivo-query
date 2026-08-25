package com.knowimran.rivoquery.connection.application.lifecycle;

import com.knowimran.rivoquery.connection.application.Deadline;
import com.knowimran.rivoquery.connection.pool.PoolDrainReason;
import com.knowimran.rivoquery.connection.pool.PoolKey;
import com.knowimran.rivoquery.connection.pool.TargetPoolManager;

/**
 * No-op {@link TargetPoolManager} placeholder. No real connection-pool
 * implementation exists yet (see P5-100, which owns pool creation, capacity
 * budgets, JIT lease, and idle eviction/drain/shutdown); until it lands,
 * there is no live pool for any {@link PoolKey} to actually drain, so
 * draining one is correctly a no-op. The P2-100 lifecycle saga still calls
 * {@link #stopNewBorrowsAndDrain} with the correct key/reason at the correct
 * lifecycle points (disable, delete, secret rotation) so that wiring a real
 * {@link TargetPoolManager} in later than the saga itself needs no call-site
 * changes.
 */
public final class NoOpTargetPoolManager implements TargetPoolManager {
    @Override
    public void stopNewBorrowsAndDrain(PoolKey key, PoolDrainReason reason, Deadline deadline) {
        // Intentionally empty: no pool implementation exists yet (P5-100).
    }
}
