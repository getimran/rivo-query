package com.knowimran.rivoquery.platform.secrets;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowimran.rivoquery.ArchitectureTest;
import com.knowimran.rivoquery.connection.application.Deadline;
import com.knowimran.rivoquery.connection.application.secret.SecretCapabilityAuthority.SecretAccessGrant;
import com.knowimran.rivoquery.connection.application.secret.SecretLease;
import com.knowimran.rivoquery.connection.application.secret.SecretReference;
import com.knowimran.rivoquery.connection.application.secret.SecretStore;
import com.knowimran.rivoquery.connection.connector.FakeConnectorHoldingAGrant;
import com.knowimran.rivoquery.worker.FakeWorkerHoldingALease;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

/**
 * Extends the whole-codebase "who may touch {@code SecretStore}/grants"
 * guarantee ({@code ArchitectureTest}, {@code SecretCapabilityBoundaryTest})
 * with rules and fixtures specific to this package: only the local and
 * managed adapters here may implement {@code SecretStore}; this package
 * never reaches back into persistence/connector/pool/worker/transport
 * packages; and no public method here returns raw secret material bypassing
 * the bounded-lease consumer pattern.
 *
 * <p>The fixture tests below deliberately run the <em>actual</em>
 * {@code ArchitectureTest} rule objects/factory method (imported, not
 * copied) against deliberately-violating fixture classes that live in one
 * of those rules' real scoped packages ({@code ..worker..},
 * {@code ..connection.connector..}, or unscoped, matching each rule's own
 * scope). This proves the production rules would actually catch each kind
 * of violation, rather than proving that a hand-maintained, possibly
 * differently-scoped copy of the rule logic would — and it cannot silently
 * drift out of sync with {@code ArchitectureTest}, because there is only
 * one copy of the rule logic.
 */
@AnalyzeClasses(packages = "com.knowimran.rivoquery", importOptions = ImportOption.DoNotIncludeTests.class)
class SecretStoreEntryPointBoundaryTest {

    @ArchTest
    static final ArchRule only_local_and_managed_adapters_implement_secret_store =
            classes().that().implement(SecretStore.class)
                    .should().resideInAPackage("..platform.secrets..");

    @ArchTest
    static final ArchRule platform_secrets_never_reaches_into_untrusted_boundary_packages =
            noClasses().that().resideInAPackage("..platform.secrets..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..platform.persistence..",
                            "..connection.connector..",
                            "..connection.pool..",
                            "..worker..",
                            "..workers..",
                            "..interfaceapi..",
                            "..interfacemcp..");

    // Scoped to SecretLease implementations specifically (the type callers
    // actually receive and are meant to consume only through a bounded
    // callback), not every internal envelope/DTO record in this package:
    // LocalSecretEnvelope/WrappedDataKey are ciphertext-shaped, never
    // plaintext, and ManagedSecretProvider.readVersion legitimately returns
    // raw provider bytes at the low-level provider boundary that
    // ManagedSecretStore immediately wraps into a BoundedSecretLease.
    @ArchTest
    static final ArchRule secret_lease_implementations_never_expose_a_public_byte_array_accessor =
            noClasses().that().implement(SecretLease.class)
                    .should(new ArchCondition<JavaClass>("declare a public byte[]-returning method") {
                        @Override
                        public void check(JavaClass item, ConditionEvents events) {
                            item.getMethods().stream()
                                    .filter(method -> method.getModifiers().contains(JavaModifier.PUBLIC))
                                    .filter(method -> method.getRawReturnType().isEquivalentTo(byte[].class))
                                    .forEach(method -> events.add(SimpleConditionEvent.violated(
                                            item, item.getName() + "." + method.getName() + " returns byte[]")));
                        }
                    });

    @Test
    void aHypotheticalPersistenceClassCallingSecretStoreResolveIsCaughtByTheWholeCodebaseRule() {
        var fixtures = new ClassFileImporter().importClasses(
                FakePersistenceCallSite.class, FakeConcreteSecretStore.class);

        assertThatThrownBy(() -> ArchitectureTest.onlyAuthorityCallsSecretStoreRule().check(fixtures))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("FakePersistenceCallSite");
    }

    @Test
    void aHypotheticalCallSiteUsingAMethodReferenceToSecretStoreResolveIsCaughtByTheWholeCodebaseRule() {
        var fixtures = new ClassFileImporter().importClasses(
                FakeCallSiteUsingMethodReference.class, FakeConcreteSecretStore.class);

        assertThatThrownBy(() -> ArchitectureTest.onlyAuthorityCallsSecretStoreRule().check(fixtures))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("FakeCallSiteUsingMethodReference");
    }

    @Test
    void aHypotheticalWorkerClassHoldingASecretLeaseFieldIsCaughtByTheLeaseEscapeRule() {
        var fixtures = new ClassFileImporter().importClasses(FakeWorkerHoldingALease.class);

        assertThatThrownBy(() ->
                        ArchitectureTest.secret_leases_do_not_escape_to_untrusted_boundaries.check(fixtures))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("FakeWorkerHoldingALease");
    }

    @Test
    void aHypotheticalConnectorClassHoldingAnAccessGrantFieldIsCaughtByTheGrantEscapeRule() {
        var fixtures = new ClassFileImporter().importClasses(FakeConnectorHoldingAGrant.class);

        assertThatThrownBy(() ->
                        ArchitectureTest.untrusted_boundaries_do_not_depend_on_secret_grants.check(fixtures))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("FakeConnectorHoldingAGrant");
    }

    /** Simulates a hypothetical `platform.persistence`/connector/worker-shaped class. */
    private static final class FakePersistenceCallSite {
        SecretLease resolve(FakeConcreteSecretStore store, SecretReference reference, SecretAccessGrant grant, Deadline deadline) {
            return store.resolve(reference, grant, deadline);
        }
    }

    /**
     * Simulates a hypothetical call site that reaches {@code SecretStore}
     * through a bound method reference rather than a direct call. This
     * compiles to {@code invokedynamic} and ArchUnit models it as a
     * {@code JavaMethodReference}, a kind of access disjoint from
     * {@code JavaMethodCall} — proving the guard's
     * {@code getMethodReferencesFromSelf()} check, not just its
     * {@code getMethodCallsFromSelf()} check.
     */
    private static final class FakeCallSiteUsingMethodReference {
        SecretResolver resolver(FakeConcreteSecretStore store) {
            return store::resolve;
        }
    }

    @FunctionalInterface
    private interface SecretResolver {
        SecretLease resolve(SecretReference reference, SecretAccessGrant grant, Deadline deadline);
    }

    private static final class FakeConcreteSecretStore implements SecretStore {
        @Override
        public com.knowimran.rivoquery.connection.application.secret.StagedSecretReference stage(
                com.knowimran.rivoquery.connection.application.secret.SecretBinding binding,
                com.knowimran.rivoquery.connection.application.secret.DestructibleSecretMaterial material,
                com.knowimran.rivoquery.connection.application.secret.SecretCapabilityAuthority.SecretMutationGrant grant,
                Deadline deadline) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SecretLease resolve(SecretReference reference, SecretAccessGrant grant, Deadline deadline) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void revoke(
                SecretReference reference,
                com.knowimran.rivoquery.connection.application.secret.SecretCapabilityAuthority.SecretRevocationGrant grant,
                Deadline deadline) {
            throw new UnsupportedOperationException();
        }
    }
}
