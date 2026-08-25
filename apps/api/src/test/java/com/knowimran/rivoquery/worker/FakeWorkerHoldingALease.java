package com.knowimran.rivoquery.worker;

import com.knowimran.rivoquery.connection.application.secret.SecretLease;

/**
 * ArchUnit fixture only: deliberately violates
 * {@code ArchitectureTest.secret_leases_do_not_escape_to_untrusted_boundaries}
 * by holding a {@link SecretLease} field. Placed in this exact package (not
 * a lookalike) so it genuinely falls inside the {@code ..worker..} pattern
 * that production rule scopes against — {@code SecretStoreEntryPointBoundaryTest}
 * runs the real rule instance against this class to prove the rule would
 * actually catch such a violation, not merely that a hand-rolled, unscoped
 * copy of the rule would. Never wired into the application graph; imported
 * only via an explicit {@code ClassFileImporter} class reference in tests.
 */
public final class FakeWorkerHoldingALease {
    private SecretLease lease;
}
