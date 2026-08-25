package com.knowimran.rivoquery.connection.connector;

import com.knowimran.rivoquery.connection.DatabaseKind;
import java.util.Objects;
import java.util.Set;

public record ConnectorCapabilities(DatabaseKind kind, Set<ConnectorOption> safeOptions) {
    public ConnectorCapabilities {
        Objects.requireNonNull(kind, "kind");
        safeOptions = Set.copyOf(Objects.requireNonNull(safeOptions, "safeOptions"));
    }
}
