package com.knowimran.rivoquery.connection.connector;

import com.knowimran.rivoquery.connection.DatabaseKind;

/**
 * Resolves the single {@link DatabaseConnector} bound to a given
 * {@link DatabaseKind}. Added for the P2-100 lifecycle saga, which needs to
 * pick the correct dialect-bound connector without depending on
 * {@code platform.connector} directly (core modules never depend on
 * {@code platform}; see {@code ArchitectureTest}).
 */
public interface DatabaseConnectorSelector {
    DatabaseConnector select(DatabaseKind kind);
}
