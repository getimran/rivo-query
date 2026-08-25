package com.knowimran.rivoquery.platform.connector;

import com.knowimran.rivoquery.connection.DatabaseKind;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A fully server-built JDBC connection target: driver class, URL, and driver properties.
 *
 * <p>Every field is produced entirely by a {@link ConnectorDialect} from validated structured
 * input; none of it is ever a raw client-supplied URL, driver class name, socket factory, file
 * path, or arbitrary property map. {@link #enforceReadOnlyOnConnect()} is always {@code true};
 * it is never client- or caller-settable.
 *
 * <p>The "only a dialect can build a target" invariant is enforced structurally, not just by
 * convention: the compact constructor rejects any {@code driverClassName} other than the known
 * driver class for {@code kind}, and rejects any property key outside that dialect's own closed
 * {@link ConnectorProperty} allowlist. There is no way to construct an instance with an arbitrary
 * driver, URL scheme's driver class, or an out-of-allowlist property (e.g. {@code socketFactory})
 * regardless of what package the caller is in.
 */
public record JdbcConnectionTarget(
        DatabaseKind kind,
        String driverClassName,
        String jdbcUrl,
        Map<String, String> properties,
        boolean enforceReadOnlyOnConnect) {

    private static final Map<DatabaseKind, String> DIALECT_DRIVER_CLASS_NAMES = Map.of(
            DatabaseKind.POSTGRESQL, PostgresqlConnectorDialect.DRIVER_CLASS_NAME,
            DatabaseKind.MYSQL, MysqlConnectorDialect.DRIVER_CLASS_NAME);

    private static final Map<DatabaseKind, Set<String>> DIALECT_ALLOWED_PROPERTY_KEYS = Map.of(
            DatabaseKind.POSTGRESQL, allowedKeys(PostgresqlConnectorProperty.values()),
            DatabaseKind.MYSQL, allowedKeys(MysqlConnectorProperty.values()));

    public JdbcConnectionTarget {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(driverClassName, "driverClassName");
        if (driverClassName.isBlank()) {
            throw new IllegalArgumentException("driverClassName must not be blank");
        }
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        if (jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("jdbcUrl must not be blank");
        }
        properties = Map.copyOf(Objects.requireNonNull(properties, "properties"));
        if (!enforceReadOnlyOnConnect) {
            throw new IllegalArgumentException(
                    "read-only enforcement is mandatory and cannot be disabled");
        }
        String expectedDriverClassName = DIALECT_DRIVER_CLASS_NAMES.get(kind);
        if (expectedDriverClassName == null || !expectedDriverClassName.equals(driverClassName)) {
            throw new IllegalArgumentException(
                    "driverClassName is not the recognized dialect driver for " + kind);
        }
        Set<String> allowedPropertyKeys = DIALECT_ALLOWED_PROPERTY_KEYS.getOrDefault(kind, Set.of());
        if (!allowedPropertyKeys.containsAll(properties.keySet())) {
            throw new IllegalArgumentException(
                    "properties contain a key outside " + kind + "'s closed connector allowlist");
        }
    }

    private static Set<String> allowedKeys(ConnectorProperty[] values) {
        return Arrays.stream(values).map(ConnectorProperty::key).collect(Collectors.toUnmodifiableSet());
    }

    /** Defensive copy suitable for {@code DriverManager.getConnection(url, properties)}. */
    public Properties toDriverProperties() {
        var driverProperties = new Properties();
        properties.forEach(driverProperties::setProperty);
        return driverProperties;
    }

    @Override
    public String toString() {
        return "JdbcConnectionTarget[REDACTED]";
    }
}
