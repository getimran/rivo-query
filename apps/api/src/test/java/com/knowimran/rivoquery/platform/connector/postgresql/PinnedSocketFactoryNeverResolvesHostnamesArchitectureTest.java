package com.knowimran.rivoquery.platform.connector.postgresql;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

/**
 * Structural proof that {@link RivoQueryPinnedSocketFactory} (and everything else in this
 * package) never calls an ordinary hostname-resolving API. This is independent of, and stronger
 * than, an end-to-end/runtime resolver trap: it holds regardless of test-environment DNS/network
 * configuration and cannot be defeated by a code path that happens not to execute during a
 * particular test run.
 *
 * <p>Complements (does not replace) {@code PostgresqlPhysicalJdbcConnectorIT}'s end-to-end proof,
 * which connects using an {@code .invalid} original hostname (RFC 2606: guaranteed to never
 * resolve anywhere) - so a successful connection there is independent, environment-external
 * evidence that ordinary resolution of the original hostname never happened for the *whole*
 * pgjdbc + RivoQuery stack, not just this one class.
 */
class PinnedSocketFactoryNeverResolvesHostnamesArchitectureTest {

    private static final String PACKAGE = "com.knowimran.rivoquery.platform.connector.postgresql";

    @Test
    void neverCallsAHostnameResolvingApi() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(PACKAGE);

        var rule = noClasses().that().resideInAPackage(PACKAGE + "..")
                .should(neverResolveAHostname());

        assertThatCode(() -> rule.check(classes)).doesNotThrowAnyException();
    }

    /**
     * Flags every JDK API in this package that can resolve, embed, or leak a hostname into a
     * network destination, not merely {@code InetAddress.getByName}/{@code getAllByName} and a
     * String-hostname {@code InetSocketAddress} constructor:
     *
     * <ul>
     *   <li>{@code InetAddress.getHostName()}/{@code getCanonicalHostName()} - can trigger a
     *       reverse-DNS lookup.
     *   <li>{@code InetAddress.getByAddress(String, byte[])} - the hostname-hint overload; this
     *       code must only ever use the 1-arg {@code getByAddress(byte[])} overload (no lookup,
     *       per JDK contract, and no hostname string anywhere near a resolved address).
     *   <li>{@code InetSocketAddress.getHostName()} - can trigger a reverse-DNS lookup.
     *   <li>{@code InetSocketAddress.createUnresolved(String, int)} - accepts a raw hostname that a
     *       later {@code Socket.connect} could still resolve.
     *   <li>{@code new InetSocketAddress(String, ...)} and {@code new Socket(String, int)} - both
     *       resolve their hostname argument as part of construction/connection.
     * </ul>
     */
    private static ArchCondition<com.tngtech.archunit.core.domain.JavaClass> neverResolveAHostname() {
        return new ArchCondition<>("never call a hostname-resolving API") {
            @Override
            public void check(com.tngtech.archunit.core.domain.JavaClass item, ConditionEvents events) {
                for (JavaMethodCall call : item.getMethodCallsFromSelf()) {
                    String owner = call.getTargetOwner().getFullName();
                    String name = call.getName();
                    boolean violates =
                            (owner.equals("java.net.InetAddress")
                                            && (name.equals("getByName")
                                                    || name.equals("getAllByName")
                                                    || name.equals("getHostName")
                                                    || name.equals("getCanonicalHostName")
                                                    || (name.equals("getByAddress")
                                                            && call.getTarget().getRawParameterTypes().stream()
                                                                    .anyMatch(type -> type.getFullName()
                                                                            .equals("java.lang.String")))))
                                    || (owner.equals("java.net.InetSocketAddress")
                                            && (name.equals("getHostName") || name.equals("createUnresolved")));
                    if (violates) {
                        events.add(SimpleConditionEvent.violated(
                                item, item.getName() + " calls " + call.getTarget().getFullName()));
                    }
                }
                for (JavaConstructorCall call : item.getConstructorCallsFromSelf()) {
                    String owner = call.getTargetOwner().getFullName();
                    boolean takesAStringHost = call.getTarget().getRawParameterTypes().stream()
                            .anyMatch(type -> type.getFullName().equals("java.lang.String"));
                    if ((owner.equals("java.net.InetSocketAddress") || owner.equals("java.net.Socket"))
                            && takesAStringHost) {
                        events.add(SimpleConditionEvent.violated(
                                item,
                                item.getName() + " constructs a hostname-based " + call.getTargetOwner().getName()));
                    }
                }
            }
        };
    }
}
