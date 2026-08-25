package com.knowimran.rivoquery;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.knowimran.rivoquery.connection.application.lifecycle.ConnectionLifecycleService;
import com.knowimran.rivoquery.connection.application.lifecycle.SecretOperationAuthorizationRegistry;
import com.knowimran.rivoquery.connection.application.secret.SecretCapabilityAuthority;
import com.knowimran.rivoquery.connection.application.secret.SecretStore;

@AnalyzeClasses(
        packages = "com.knowimran.rivoquery",
        importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

    private static final String ROOT_PACKAGE = "com.knowimran.rivoquery..";
    private static final String[] CORE_PACKAGES = {
        "..application..",
        "..identity..",
        "..connection..",
        "..catalog..",
        "..generation..",
        "..querypolicy..",
        "..execution..",
        "..audit.."
    };

    @ArchTest
    static final ArchRule production_classes_stay_under_the_approved_package_root =
            classes().should().resideInAPackage(ROOT_PACKAGE);

    @ArchTest
    static final ArchRule modules_are_free_of_cycles =
            slices().matching("com.knowimran.rivoquery.(*)..")
                    .should().beFreeOfCycles();

    @ArchTest
    static final ArchRule core_modules_do_not_depend_on_adapters_or_frameworks =
            noClasses().that().resideInAnyPackage(CORE_PACKAGES)
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..interfaceapi..",
                            "..interfacemcp..",
                            "..platform..",
                            "org.springframework..",
                            "java.sql..",
                            "javax.sql..",
                            "jakarta.persistence..");

    @ArchTest
    static final ArchRule rest_and_mcp_adapters_are_isolated =
            noClasses().that().resideInAPackage("..interfaceapi..")
                    .should().dependOnClassesThat().resideInAPackage("..interfacemcp..");

    @ArchTest
    static final ArchRule mcp_and_rest_adapters_are_isolated =
            noClasses().that().resideInAPackage("..interfacemcp..")
                    .should().dependOnClassesThat().resideInAPackage("..interfaceapi..");

    @ArchTest
    static final ArchRule platform_does_not_depend_on_transport_adapters =
            noClasses().that().resideInAPackage("..platform..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..interfaceapi..", "..interfacemcp..");

    @ArchTest
    static final ArchRule rest_adapter_depends_on_application_ports_not_platform_implementations =
            noClasses().that().resideInAPackage("..interfaceapi..")
                    .should().dependOnClassesThat().resideInAPackage("..platform..");

    @ArchTest
    static final ArchRule only_secret_capability_authority_calls_secret_store =
            onlyAuthorityCallsSecretStoreRule();

    @ArchTest
    static final ArchRule only_connection_lifecycle_service_calls_secret_operation_authorization_registry_mutators =
            onlyLifecycleServiceCallsAuthorizationRegistryMutatorsRule();

    @ArchTest
    public static final ArchRule untrusted_boundaries_do_not_depend_on_secret_grants =
            noClasses().that().resideInAnyPackage(
                            "..interfaceapi..",
                            "..interfacemcp..",
                            "..platform.persistence..",
                            "..connection.connector..",
                            "..connection.pool..",
                            "..worker..",
                            "..workers..")
                    .should().dependOnClassesThat().haveNameMatching(
                            ".*SecretCapabilityAuthority\\$Secret(Access|Mutation|Revocation)Grant");

    @ArchTest
    public static final ArchRule secret_leases_do_not_escape_to_untrusted_boundaries =
            noClasses().that().resideInAnyPackage(
                            "..interfaceapi..",
                            "..interfacemcp..",
                            "..platform.persistence..",
                            "..connection.connector..",
                            "..connection.pool..",
                            "..worker..",
                            "..workers..")
                    .should().dependOnClassesThat().haveSimpleName("SecretLease");

    @ArchTest
    static final ArchRule transport_cannot_mint_actor_contexts =
            noClasses().that().resideInAnyPackage("..interfaceapi..", "..interfacemcp..")
                    .should().dependOnClassesThat().haveNameMatching(".*ActorContext\\$Authority");

    /**
     * Public so {@code SecretStoreEntryPointBoundaryTest} can run the exact
     * production rule instance against deliberately-violating fixtures,
     * rather than maintaining a second, driftable copy of this logic.
     */
    public static ArchRule onlyAuthorityCallsSecretStoreRule() {
        return classes().that().doNotHaveFullyQualifiedName(SecretCapabilityAuthority.class.getName())
                .should(notCallSecretStoreMethods());
    }

    /**
     * Flags both direct calls ({@code JavaMethodCall}, e.g.
     * {@code store.resolve(...)}) and method references
     * ({@code JavaMethodReference}, e.g. {@code store::resolve}, which the
     * compiler lowers to {@code invokedynamic} and ArchUnit models as a
     * distinct, disjoint kind of access) that target a {@link SecretStore}
     * method. A rule that inspected only {@code getMethodCallsFromSelf()}
     * would silently let a method-reference-based call site evade this
     * guard entirely.
     */
    private static ArchCondition<JavaClass> notCallSecretStoreMethods() {
        return new ArchCondition<>("not call or reference SecretStore methods") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                item.getMethodCallsFromSelf().stream()
                        .filter(call -> call.getTargetOwner().isAssignableTo(SecretStore.class))
                        .forEach(call -> events.add(SimpleConditionEvent.violated(
                                item, item.getName() + " calls " + call.getTarget().getFullName())));
                item.getMethodReferencesFromSelf().stream()
                        .filter(reference -> reference.getTargetOwner().isAssignableTo(SecretStore.class))
                        .forEach(reference -> events.add(SimpleConditionEvent.violated(
                                item, item.getName() + " references " + reference.getTarget().getFullName())));
            }
        };
    }

    /**
     * Public so a fixture test can run the exact production rule instance
     * against a deliberately-violating fixture, rather than maintaining a
     * second, driftable copy of this logic - mirrors {@link
     * #onlyAuthorityCallsSecretStoreRule}.
     */
    public static ArchRule onlyLifecycleServiceCallsAuthorizationRegistryMutatorsRule() {
        return classes().that().doNotHaveFullyQualifiedName(ConnectionLifecycleService.class.getName())
                .should(notCallAuthorizationRegistryMutators());
    }

    private static ArchCondition<JavaClass> notCallAuthorizationRegistryMutators() {
        return new ArchCondition<>("not call SecretOperationAuthorizationRegistry.register/release") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                item.getMethodCallsFromSelf().stream()
                        .filter(call -> call.getTargetOwner().isAssignableTo(SecretOperationAuthorizationRegistry.class))
                        .filter(call -> call.getName().equals("register") || call.getName().equals("release"))
                        .forEach(call -> events.add(SimpleConditionEvent.violated(
                                item, item.getName() + " calls " + call.getTarget().getFullName())));
                item.getMethodReferencesFromSelf().stream()
                        .filter(reference -> reference.getTargetOwner().isAssignableTo(SecretOperationAuthorizationRegistry.class))
                        .filter(reference -> reference.getName().equals("register") || reference.getName().equals("release"))
                        .forEach(reference -> events.add(SimpleConditionEvent.violated(
                                item, item.getName() + " references " + reference.getTarget().getFullName())));
            }
        };
    }
}
