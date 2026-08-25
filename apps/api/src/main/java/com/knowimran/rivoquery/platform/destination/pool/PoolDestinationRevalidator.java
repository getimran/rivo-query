package com.knowimran.rivoquery.platform.destination.pool;

import com.knowimran.rivoquery.connection.application.destination.DestinationAssessment;
import com.knowimran.rivoquery.connection.application.destination.DestinationRequest;

/**
 * Revalidates a pool's destination binding before a new logical borrow. Per ADR-0013: an
 * unexpired binding is reused without new DNS traffic; an expired binding blocks new borrows,
 * obtains a fresh resolution, and is either renewed (unchanged, still allowed) or the pool is
 * quarantined and drained (changed, policy changed, or resolution/policy failed).
 */
public interface PoolDestinationRevalidator {
    /**
     * @return a current, usable assessment when the borrow may proceed.
     * @throws RuntimeException (a {@code DestinationValidationException} or the policy's own
     *     failure) when the binding could not be renewed; the caller must not lend a connection
     *     built from the stale binding. The pool has already been signalled to stop new borrows
     *     and drain before this method returns abnormally.
     */
    DestinationAssessment ensureFreshForBorrow(PoolDestinationBinding binding, DestinationRequest request);
}
