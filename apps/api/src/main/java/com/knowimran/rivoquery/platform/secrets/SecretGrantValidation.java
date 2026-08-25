package com.knowimran.rivoquery.platform.secrets;

import com.knowimran.rivoquery.connection.application.secret.SecretBinding;
import com.knowimran.rivoquery.connection.application.secret.SecretCapabilityAuthority.SecretAccessGrant;
import com.knowimran.rivoquery.connection.application.secret.SecretCapabilityAuthority.SecretMutationGrant;
import com.knowimran.rivoquery.connection.application.secret.SecretCapabilityAuthority.SecretRevocationGrant;
import com.knowimran.rivoquery.connection.application.secret.SecretReference;
import java.util.Objects;

/**
 * Shared pre-storage grant/binding/reference equality checks used identically
 * by every {@code SecretStore} adapter in this package. This is the "binding
 * equivalence" the local and production adapters must both provide: neither
 * touches storage before these checks pass. This class only ever consumes
 * the sealed grant interfaces to verify a binding; it has no way to
 * construct one (grant construction is private to
 * {@code SecretCapabilityAuthority}).
 */
final class SecretGrantValidation {
    private SecretGrantValidation() {
    }

    static void requireStageGrantMatches(SecretBinding binding, SecretMutationGrant grant) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(grant, "grant");
        if (!grant.binding().equals(binding)) {
            throw new SecretGrantMismatchException();
        }
    }

    static void requireAccessGrantMatches(SecretReference reference, SecretAccessGrant grant) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(grant, "grant");
        if (!grant.reference().equals(reference)) {
            throw new SecretGrantMismatchException();
        }
        requireBindingFingerprintMatches(grant.binding(), reference);
    }

    static void requireRevocationGrantMatches(SecretReference reference, SecretRevocationGrant grant) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(grant, "grant");
        if (!grant.reference().equals(reference)) {
            throw new SecretGrantMismatchException();
        }
        requireBindingFingerprintMatches(grant.binding(), reference);
    }

    static void requireBindingFingerprintMatches(SecretBinding binding, SecretReference reference) {
        if (!SecretBindingCodec.fingerprint(binding).equals(reference.bindingFingerprint())) {
            throw new SecretGrantMismatchException();
        }
        if (binding.version() != reference.version()) {
            throw new SecretGrantMismatchException();
        }
    }
}
