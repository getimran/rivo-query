package com.knowimran.rivoquery.platform.connector;

/**
 * A single member of a dialect's closed, connector-controlled JDBC property allowlist.
 *
 * <p>Implementations are enums (one per {@code DatabaseKind}); there is no way to construct an
 * arbitrary key at runtime, and no client input is ever accepted as a property key or value.
 */
public interface ConnectorProperty {
    String key();
}
