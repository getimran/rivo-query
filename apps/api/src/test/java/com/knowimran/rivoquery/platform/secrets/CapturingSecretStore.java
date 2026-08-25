package com.knowimran.rivoquery.platform.secrets;

import com.knowimran.rivoquery.connection.application.Deadline;
import com.knowimran.rivoquery.connection.application.secret.DestructibleSecretMaterial;
import com.knowimran.rivoquery.connection.application.secret.SecretBinding;
import com.knowimran.rivoquery.connection.application.secret.SecretCapabilityAuthority.SecretAccessGrant;
import com.knowimran.rivoquery.connection.application.secret.SecretCapabilityAuthority.SecretMutationGrant;
import com.knowimran.rivoquery.connection.application.secret.SecretCapabilityAuthority.SecretRevocationGrant;
import com.knowimran.rivoquery.connection.application.secret.SecretLease;
import com.knowimran.rivoquery.connection.application.secret.SecretReference;
import com.knowimran.rivoquery.connection.application.secret.SecretStore;
import com.knowimran.rivoquery.connection.application.secret.StagedSecretReference;
import java.util.Objects;

/**
 * Test-only {@code SecretStore} decorator that transparently delegates to a
 * real adapter while capturing the most recently minted grant of each kind.
 * Lets a test drive a real {@code SecretCapabilityAuthority} to legitimately
 * mint a grant (grant construction is otherwise private to that class), then
 * replay the captured grant directly against the real adapter's own entry
 * points with a mismatched reference/binding - simulating a forged or
 * cross-tenant/cross-connection/stale-version replayed call that never went
 * back through the authority - without this test ever constructing a grant
 * itself.
 */
final class CapturingSecretStore implements SecretStore {
    private final SecretStore delegate;
    private volatile SecretMutationGrant lastMutationGrant;
    private volatile SecretAccessGrant lastAccessGrant;
    private volatile SecretRevocationGrant lastRevocationGrant;

    CapturingSecretStore(SecretStore delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public StagedSecretReference stage(
            SecretBinding binding, DestructibleSecretMaterial material, SecretMutationGrant grant, Deadline deadline) {
        lastMutationGrant = grant;
        return delegate.stage(binding, material, grant, deadline);
    }

    @Override
    public SecretLease resolve(SecretReference reference, SecretAccessGrant grant, Deadline deadline) {
        lastAccessGrant = grant;
        return delegate.resolve(reference, grant, deadline);
    }

    @Override
    public void revoke(SecretReference reference, SecretRevocationGrant grant, Deadline deadline) {
        lastRevocationGrant = grant;
        delegate.revoke(reference, grant, deadline);
    }

    SecretMutationGrant lastMutationGrant() {
        return lastMutationGrant;
    }

    SecretAccessGrant lastAccessGrant() {
        return lastAccessGrant;
    }

    SecretRevocationGrant lastRevocationGrant() {
        return lastRevocationGrant;
    }
}
