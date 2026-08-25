package com.knowimran.rivoquery.platform.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TrustedEndpointValidationTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "db.example.com",
        "DB.EXAMPLE.com",
        "127.0.0.1",
        "a",
        "::1",
        "2001:db8::1"
    })
    void acceptsWellFormedHostnamesAndLiterals(String host) {
        assertThat(TrustedEndpointValidation.requireTrustedHost(host)).isEqualTo(host);
    }

    @Test
    void bracketsIpv6HostsForTheUrlAuthorityComponentButLeavesOthersUnchanged() {
        assertThat(TrustedEndpointValidation.toUrlHostComponent("2001:db8::1")).isEqualTo("[2001:db8::1]");
        assertThat(TrustedEndpointValidation.toUrlHostComponent("db.example.com")).isEqualTo("db.example.com");
        assertThat(TrustedEndpointValidation.toUrlHostComponent("127.0.0.1")).isEqualTo("127.0.0.1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"fe80::1/evil", "fe80::1&x=1", "fe80::1 evil", "fe80::1\r\ninjected"})
    void rejectsIpv6ShapedHostsCarryingMetacharacters(String host) {
        assertThatThrownBy(() -> TrustedEndpointValidation.requireTrustedHost(host))
                .isInstanceOf(InvalidConnectorEndpointException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "evil.com/?x=1",
        "evil.com#frag",
        "evil.com&socketFactory=x",
        "evil.com;DROP TABLE",
        "evil.com\r\nSet-Cookie: x",
        "evil.com\0",
        "evil.com:1234",
        "eviluser@evil.com",
        "eviluser:pw@evil.com",
        " evil.com",
        "evil.com ",
        "evil.com\t",
        "../../etc/passwd",
        "file:///etc/passwd"
    })
    void rejectsHostsWithMetacharactersOrInjectionAttempts(String host) {
        assertThatThrownBy(() -> TrustedEndpointValidation.requireTrustedHost(host))
                .isInstanceOf(InvalidConnectorEndpointException.class);
    }

    @Test
    void rejectsHostExceedingMaxLength() {
        String tooLong = "a".repeat(256);
        assertThatThrownBy(() -> TrustedEndpointValidation.requireTrustedHost(tooLong))
                .isInstanceOf(InvalidConnectorEndpointException.class);
    }

    @Test
    void portMustBeInValidRange() {
        assertThat(TrustedEndpointValidation.requireTrustedPort(1)).isEqualTo(1);
        assertThat(TrustedEndpointValidation.requireTrustedPort(65_535)).isEqualTo(65_535);
        assertThatThrownBy(() -> TrustedEndpointValidation.requireTrustedPort(0))
                .isInstanceOf(InvalidConnectorEndpointException.class);
        assertThatThrownBy(() -> TrustedEndpointValidation.requireTrustedPort(65_536))
                .isInstanceOf(InvalidConnectorEndpointException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"analytics", "analytics_db", "analytics-db", "analytics.v2", "A1"})
    void acceptsWellFormedDatabaseNames(String databaseName) {
        assertThat(TrustedEndpointValidation.requireTrustedDatabaseName(databaseName)).isEqualTo(databaseName);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "db&socketFactory=evil.Factory",
        "db?allowLoadLocalInfile=true",
        "db#frag",
        "db/../../etc/passwd",
        "db name",
        "db\r\ninjected",
        "db\0",
        "db;DROP TABLE users;--",
        "db'OR'1'='1"
    })
    void rejectsDatabaseNamesWithMetacharactersOrInjectionAttempts(String databaseName) {
        assertThatThrownBy(() -> TrustedEndpointValidation.requireTrustedDatabaseName(databaseName))
                .isInstanceOf(InvalidConnectorEndpointException.class);
    }

    @Test
    void rejectsDatabaseNameExceedingMaxLength() {
        String tooLong = "a".repeat(64);
        assertThatThrownBy(() -> TrustedEndpointValidation.requireTrustedDatabaseName(tooLong))
                .isInstanceOf(InvalidConnectorEndpointException.class);
    }

    @Test
    void exceptionMessageNeverEchoesTheUntrustedValue() {
        String secretLikeHost = "attacker-controlled-value-should-not-appear";
        assertThatThrownBy(() -> TrustedEndpointValidation.requireTrustedHost(secretLikeHost + "&x=1"))
                .isInstanceOf(InvalidConnectorEndpointException.class)
                .hasMessageNotContaining(secretLikeHost);
    }
}
