package com.knowimran.rivoquery.connection.application.lifecycle;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowimran.rivoquery.ArchitectureTest;
import com.knowimran.rivoquery.connection.OperationId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

/**
 * Proves {@code ArchitectureTest
 * .only_connection_lifecycle_service_calls_secret_operation_authorization_registry_mutators}
 * actually catches a violation (not just passes vacuously) - same discipline
 * as {@code SecretStoreArchitectureFixtureTest} for the analogous {@code
 * SecretStore} rule.
 */
class SecretOperationAuthorizationRegistryArchitectureFixtureTest {
    @Test
    void mutatorRuleDetectsCallsFromOutsideConnectionLifecycleService() {
        var fixtures = new ClassFileImporter().importClasses(
                ForbiddenConcreteCaller.class, SecretOperationAuthorizationRegistry.class);

        assertThatThrownBy(() -> ArchitectureTest
                        .onlyLifecycleServiceCallsAuthorizationRegistryMutatorsRule()
                        .check(fixtures))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ForbiddenConcreteCaller");
    }

    /**
     * Deliberately placed in the same package as {@link
     * SecretOperationAuthorizationRegistry} (its mutators are
     * package-private) to prove the production rule still catches a
     * violation from a class sitting right next to the one trusted caller -
     * the strongest version of this check.
     */
    private static final class ForbiddenConcreteCaller {
        void call(SecretOperationAuthorizationRegistry registry, WorkspaceId workspaceId, OperationId operationId) {
            registry.release(workspaceId, operationId);
        }
    }
}
