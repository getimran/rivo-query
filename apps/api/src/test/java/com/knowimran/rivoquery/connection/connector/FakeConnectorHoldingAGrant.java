package com.knowimran.rivoquery.connection.connector;

import com.knowimran.rivoquery.connection.application.secret.SecretCapabilityAuthority.SecretAccessGrant;

/**
 * ArchUnit fixture only: deliberately violates
 * {@code ArchitectureTest.untrusted_boundaries_do_not_depend_on_secret_grants}
 * by holding a {@link SecretAccessGrant} field. Placed in this exact
 * package (not a lookalike) so it genuinely falls inside the
 * {@code ..connection.connector..} pattern that production rule scopes
 * against — {@code SecretStoreEntryPointBoundaryTest} runs the real rule
 * instance against this class to prove the rule would actually catch such
 * a violation, not merely that a hand-rolled, unscoped copy of the rule
 * would. Never wired into the application graph; imported only via an
 * explicit {@code ClassFileImporter} class reference in tests.
 */
public final class FakeConnectorHoldingAGrant {
    private SecretAccessGrant grant;
}
