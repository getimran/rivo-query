package com.knowimran.rivoquery;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "com.knowimran.rivoquery",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

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
}
