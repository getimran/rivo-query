package com.knowimran.rivoquery.platform.connector.postgresql;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import org.junit.jupiter.api.Test;

/**
 * Structural proof that {@link RivoQueryPinnedSocketFactory} (and everything else in this package)
 * never calls an ordinary hostname-resolving API - independent of, and stronger than, an end-to-end
 * resolver-trap proof: it holds regardless of test-environment DNS/network configuration and cannot
 * be defeated by a code path that happens not to execute during a particular test run.
 *
 * <p><b>Fixed a vacuous rule.</b> This rule used to be written as {@code
 * noClasses().that().resideInAPackage(...).should(neverResolveAHostname())}. ArchUnit's {@code
 * noClasses().should(X)} wraps {@code X} in {@code ArchConditions.never(X)}, which inverts event
 * polarity: a "violated" event from the inner condition becomes an "allowed" (satisfied) event on the
 * outer rule. Because {@link #notCallAHostnameResolvingApi()} only ever emits {@code
 * SimpleConditionEvent.violated(...)} (never a satisfied event - the same shape as every other "must
 * not call X" condition in this codebase, e.g. {@code ArchitectureTest#notCallSecretStoreMethods}),
 * the outer {@code noClasses()} rule could never receive a violated event and could therefore never
 * fail - a permanently-vacuous test that passed even against code deliberately calling {@code
 * InetAddress.getAllByName}, {@code new Socket(String,int)}, and {@code new
 * InetSocketAddress(String,int)} directly in this package. Found by the P2-120 adversarial review,
 * which proved it by reproducing the identical defect (independently introduced) in the MySQL
 * connector's copy of this test; both are fixed identically.
 *
 * <p>The fix is the positive form already used elsewhere in this codebase for exactly this condition
 * shape (see {@code ArchitectureTest#onlyAuthorityCallsSecretStoreRule}/{@code
 * notCallSecretStoreMethods}): {@code classes().that().resideInAPackage(...).should(condition)} does
 * not invert polarity, so a violated event on a matched class fails the rule directly. {@link
 * #aFixtureClassCallingAForbiddenHostnameResolutionApiIsCaughtByTheRule()} proves this exact
 * production rule instance (not a hand-maintained copy) actually fails against a
 * deliberately-violating fixture, mirroring {@code SecretStoreEntryPointBoundaryTest}'s
 * temporarily-broken-then-fixed fixture proof pattern.
 *
 * <p>Complements (does not replace) {@code PostgresqlPhysicalJdbcConnectorIT}'s end-to-end proof,
 * which connects using an {@code .invalid} original hostname (RFC 2606: guaranteed to never resolve
 * anywhere) - so a successful connection there is independent, environment-external evidence that
 * ordinary resolution of the original hostname never happened for the *whole* pgjdbc + RivoQuery
 * stack, not just this one class.
 */
class PinnedSocketFactoryNeverResolvesHostnamesArchitectureTest {

    private static final String PACKAGE = "com.knowimran.rivoquery.platform.connector.postgresql";

    @Test
    void neverCallsAHostnameResolvingApi() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(PACKAGE);

        assertThatCode(() -> rule().check(classes)).doesNotThrowAnyException();
    }

    @Test
    void aFixtureClassCallingAForbiddenHostnameResolutionApiIsCaughtByTheRule() {
        JavaClasses fixtures = new ClassFileImporter()
                .importClasses(FixtureClassCallingAForbiddenHostnameResolutionApi.class);

        assertThatThrownBy(() -> rule().check(fixtures))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("FixtureClassCallingAForbiddenHostnameResolutionApi");
    }

    /**
     * Package-private (not private) so the fixture test above runs the exact production rule
     * instance, not a second, driftable copy of the same logic - mirrors {@code
     * ArchitectureTest#onlyAuthorityCallsSecretStoreRule}.
     */
    static ArchRule rule() {
        return classes().that().resideInAPackage(PACKAGE + "..").should(notCallAHostnameResolvingApi());
    }

    /**
     * Deliberately calls three of the forbidden APIs {@link #notCallAHostnameResolvingApi()} flags -
     * never invoked at runtime, detected purely from its bytecode. This reproduces the exact
     * technique the adversarial review used to prove the previous {@code noClasses()}-based rule
     * was vacuous (a class compiled directly into this package that calls {@code
     * InetAddress.getAllByName}, {@code new Socket(String,int)}, and {@code new
     * InetSocketAddress(String,int)}), kept here permanently as a regression guard against the same
     * bug reappearing.
     */
    static final class FixtureClassCallingAForbiddenHostnameResolutionApi {
        @SuppressWarnings("unused")
        void resolve(String host, int port) throws IOException {
            InetAddress.getAllByName(host);
            new Socket(host, port);
            new InetSocketAddress(host, port);
        }
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
    private static ArchCondition<JavaClass> notCallAHostnameResolvingApi() {
        return new ArchCondition<>("not call a hostname-resolving API") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
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
