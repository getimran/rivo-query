package com.knowimran.rivoquery.connection.application.destination;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

/**
 * A prior review of {@code StructuredEndpoint} found it only checked non-blank/stripped, so JDBC
 * URL metacharacters (?, &amp;, /, ;, whitespace) could reach host/database fields. These tests
 * prove a proper RFC 1123 hostname/IP-literal grammar and a safe database-identifier grammar are
 * now enforced, and that injection-shaped inputs are rejected.
 */
class StructuredEndpointTest {

    @Test
    void acceptsOrdinaryHostnamesAndPreservesOriginalCasing() {
        assertThatCode(() -> new StructuredEndpoint("db.example.com", 5432, "analytics")).doesNotThrowAnyException();
        assertThatCode(() -> new StructuredEndpoint("DB.Example.com", 5432, "analytics")).doesNotThrowAnyException();
        assertThatCode(() -> new StructuredEndpoint("localhost", 5432, "analytics")).doesNotThrowAnyException();
        assertThatCode(() -> new StructuredEndpoint("a-b.example.co", 5432, "analytics")).doesNotThrowAnyException();
    }

    @Test
    void acceptsStrictIpLiterals() {
        assertThatCode(() -> new StructuredEndpoint("10.0.0.1", 5432, "analytics")).doesNotThrowAnyException();
        assertThatCode(() -> new StructuredEndpoint("::1", 5432, "analytics")).doesNotThrowAnyException();
        assertThatCode(() -> new StructuredEndpoint("2001:db8::1", 5432, "analytics")).doesNotThrowAnyException();
    }

    @Test
    void rejectsJdbcUrlFragmentSmuggledThroughHost() {
        assertThatThrownBy(() -> new StructuredEndpoint("db.example.com&loggerFile=/tmp/x", 5432, "analytics"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructuredEndpoint("db.example.com?ssl=false", 5432, "analytics"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructuredEndpoint("db.example.com/../evil", 5432, "analytics"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructuredEndpoint("db.example.com;DROP TABLE x", 5432, "analytics"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructuredEndpoint("user@db.example.com", 5432, "analytics"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructuredEndpoint(" db.example.com", 5432, "analytics"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructuredEndpoint("[db.example.com]", 5432, "analytics"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsJdbcUrlFragmentSmuggledThroughDatabaseName() {
        assertThatThrownBy(() -> new StructuredEndpoint("db.example.com", 5432, "app?ssl=false&socketFactory=evil.Factory"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructuredEndpoint("db.example.com", 5432, "app/../../etc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructuredEndpoint("db.example.com", 5432, "app;DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructuredEndpoint("db.example.com", 5432, "app db"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructuredEndpoint("db.example.com", 5432, "public.users"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMalformedHostnameGrammar() {
        assertThatThrownBy(() -> new StructuredEndpoint("-abc.example.com", 5432, "analytics"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructuredEndpoint("abc-.example.com", 5432, "analytics"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructuredEndpoint("db..example.com", 5432, "analytics"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructuredEndpoint(".example.com", 5432, "analytics"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructuredEndpoint("example.com.", 5432, "analytics"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructuredEndpoint("a".repeat(64) + ".com", 5432, "analytics"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAlternativeIpv4NotationAndIpv6ZoneIds() {
        assertThatThrownBy(() -> new StructuredEndpoint("010.0.0.1", 5432, "analytics"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructuredEndpoint("fe80::1%eth0", 5432, "analytics"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMalformedDatabaseNameGrammar() {
        assertThatThrownBy(() -> new StructuredEndpoint("db.example.com", 5432, "1abc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructuredEndpoint("db.example.com", 5432, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructuredEndpoint("db.example.com", 5432, "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsOrdinaryDatabaseIdentifiers() {
        assertThatCode(() -> new StructuredEndpoint("db.example.com", 5432, "analytics")).doesNotThrowAnyException();
        assertThatCode(() -> new StructuredEndpoint("db.example.com", 5432, "_secret_db")).doesNotThrowAnyException();
        assertThatCode(() -> new StructuredEndpoint("db.example.com", 5432, "db123")).doesNotThrowAnyException();
    }

    /**
     * Regression for a prior review finding: Unicode-aware digit checks (fullwidth or Arabic-Indic
     * digit variants) could let a client vet one string as an IP literal while a downstream
     * component (e.g. TLS SNI in the JDBC URL) dials the original, differently-interpreted
     * non-ASCII string.
     */
    @Test
    void rejectsNonAsciiDigitVariantsOfIpLiteralsRatherThanNormalizingAndAccepting() {
        assertThatThrownBy(() -> new StructuredEndpoint("１２７.0.0.1", 5432, "analytics")) // fullwidth "127"
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructuredEndpoint("٩٣.184.216.34", 5432, "analytics")) // Arabic-Indic "93"
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructuredEndpoint("::ffff:１２７.0.0.1", 5432, "analytics"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPortOutOfRange() {
        assertThatThrownBy(() -> new StructuredEndpoint("db.example.com", 0, "analytics"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructuredEndpoint("db.example.com", 65_536, "analytics"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
