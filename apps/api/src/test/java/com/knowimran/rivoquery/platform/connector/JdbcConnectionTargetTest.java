package com.knowimran.rivoquery.platform.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowimran.rivoquery.connection.DatabaseKind;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JdbcConnectionTargetTest {

    @Test
    void rejectsBlankDriverClassNameOrUrl() {
        assertThatThrownBy(() -> new JdbcConnectionTarget(
                        DatabaseKind.POSTGRESQL, " ", "jdbc:postgresql://h:1/d", Map.of(), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JdbcConnectionTarget(
                        DatabaseKind.POSTGRESQL, "org.postgresql.Driver", " ", Map.of(), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readOnlyEnforcementCanNeverBeDisabled() {
        assertThatThrownBy(() -> new JdbcConnectionTarget(
                        DatabaseKind.POSTGRESQL,
                        "org.postgresql.Driver",
                        "jdbc:postgresql://h:1/d",
                        Map.of(),
                        false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void propertiesAreDefensivelyCopiedAndImmutable() {
        var mutableSource = new java.util.HashMap<String, String>();
        mutableSource.put("sslmode", "verify-full");
        var target = new JdbcConnectionTarget(
                DatabaseKind.POSTGRESQL, "org.postgresql.Driver", "jdbc:postgresql://h:1/d", mutableSource, true);

        mutableSource.put("sslmode", "disable");
        mutableSource.put("socketFactory", "evil.Factory");

        assertThat(target.properties()).containsExactly(Map.entry("sslmode", "verify-full"));
        assertThatThrownBy(() -> target.properties().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void toDriverPropertiesProducesAnEquivalentIndependentCopy() {
        var target = new JdbcConnectionTarget(
                DatabaseKind.POSTGRESQL,
                "org.postgresql.Driver",
                "jdbc:postgresql://h:1/d",
                Map.of("sslmode", "verify-full"),
                true);

        var driverProperties = target.toDriverProperties();

        assertThat(driverProperties.getProperty("sslmode")).isEqualTo("verify-full");
        driverProperties.setProperty("sslmode", "disable");
        assertThat(target.properties().get("sslmode")).isEqualTo("verify-full");
    }

    @Test
    void rejectsAnUnrecognizedDriverClassNameEvenIfWellFormed() {
        assertThatThrownBy(() -> new JdbcConnectionTarget(
                        DatabaseKind.POSTGRESQL,
                        "com.mysql.cj.jdbc.Driver",
                        "jdbc:postgresql://h:1/d",
                        Map.of(),
                        true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JdbcConnectionTarget(
                        DatabaseKind.POSTGRESQL,
                        "some.completely.Unknown$Driver",
                        "jdbc:postgresql://h:1/d",
                        Map.of(),
                        true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAPropertyKeyOutsideTheDialectsOwnClosedAllowlist() {
        assertThatThrownBy(() -> new JdbcConnectionTarget(
                        DatabaseKind.POSTGRESQL,
                        "org.postgresql.Driver",
                        "jdbc:postgresql://h:1/d",
                        Map.of("socketFactory", "evil.Factory"),
                        true))
                .isInstanceOf(IllegalArgumentException.class);
        // A MySQL-only key applied to a POSTGRESQL-kind target must also be rejected: the
        // allowlist is per-dialect, not the union of every known dialect's keys.
        assertThatThrownBy(() -> new JdbcConnectionTarget(
                        DatabaseKind.POSTGRESQL,
                        "org.postgresql.Driver",
                        "jdbc:postgresql://h:1/d",
                        Map.of("allowLoadLocalInfile", "false"),
                        true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toStringIsRedacted() {
        var target = new JdbcConnectionTarget(
                DatabaseKind.POSTGRESQL,
                "org.postgresql.Driver",
                "jdbc:postgresql://internal-host:5432/secretdb",
                Map.of(),
                true);

        assertThat(target.toString()).isEqualTo("JdbcConnectionTarget[REDACTED]");
    }
}
