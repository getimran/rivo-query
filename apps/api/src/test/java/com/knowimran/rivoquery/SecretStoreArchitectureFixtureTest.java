package com.knowimran.rivoquery;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class SecretStoreArchitectureFixtureTest {
    @Test
    void soleCallerRuleDetectsCallsThroughConcreteSecretStoreImplementations() {
        var fixtures = new ClassFileImporter().importClasses(
                ForbiddenConcreteCaller.class, ConcreteSecretStore.class);

        assertThatThrownBy(() -> ArchitectureTest.onlyAuthorityCallsSecretStoreRule().check(fixtures))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ForbiddenConcreteCaller");
    }

    private static final class ForbiddenConcreteCaller {
        SecretLease resolve(
                ConcreteSecretStore store,
                SecretReference reference,
                SecretAccessGrant grant,
                Deadline deadline) {
            return store.resolve(reference, grant, deadline);
        }
    }

    private static final class ConcreteSecretStore implements SecretStore {
        @Override
        public StagedSecretReference stage(
                SecretBinding binding,
                DestructibleSecretMaterial material,
                SecretMutationGrant grant,
                Deadline deadline) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SecretLease resolve(
                SecretReference reference, SecretAccessGrant grant, Deadline deadline) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void revoke(
                SecretReference reference, SecretRevocationGrant grant, Deadline deadline) {
            throw new UnsupportedOperationException();
        }
    }
}
