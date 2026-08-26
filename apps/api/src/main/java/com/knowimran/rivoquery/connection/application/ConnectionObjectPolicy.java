package com.knowimran.rivoquery.connection.application;

import java.util.Objects;
import java.util.Set;

/**
 * Non-secret display/administration metadata mirroring the OpenAPI {@code
 * ObjectAccessPolicy} schema. {@code allowedTables} empty means "all
 * non-system tables inside allowed schemas", matching the contract.
 */
public record ConnectionObjectPolicy(Set<String> allowedSchemas, Set<String> allowedTables) {
    public ConnectionObjectPolicy {
        allowedSchemas = Set.copyOf(Objects.requireNonNull(allowedSchemas, "allowedSchemas"));
        allowedTables = Set.copyOf(Objects.requireNonNull(allowedTables, "allowedTables"));
        if (allowedSchemas.isEmpty()) {
            throw new IllegalArgumentException("allowedSchemas must not be empty");
        }
        if (allowedSchemas.size() > 128) {
            throw new IllegalArgumentException("allowedSchemas exceeds the maximum of 128");
        }
        if (allowedTables.size() > 512) {
            throw new IllegalArgumentException("allowedTables exceeds the maximum of 512");
        }
    }
}
