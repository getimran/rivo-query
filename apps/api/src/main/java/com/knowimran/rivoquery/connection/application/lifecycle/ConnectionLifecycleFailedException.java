package com.knowimran.rivoquery.connection.application.lifecycle;

import com.knowimran.rivoquery.connection.application.ConnectionAuditCategory;
import java.util.Objects;

/**
 * Lifecycle-layer wrapper for a failure that originated in a platform
 * adapter (e.g. {@code SecretProviderException}, {@code
 * DestinationValidationException}, or an unchecked failure from a {@code
 * DatabaseConnector} implementation) before it is allowed to leave {@link
 * ConnectionLifecycleService}.
 *
 * <p>{@code ArchitectureTest.rest_adapter_depends_on_application_ports_not_platform_implementations}
 * forbids the future REST/MCP error-mapping layer (P2-130) from depending on
 * {@code ..platform..} at all, so it can never legally write {@code catch
 * (SecretProviderException)} or {@code catch (DestinationValidationException)}
 * itself. This class is therefore the trust boundary: every platform
 * exception this saga catches is translated here into a stable, closed
 * {@link ConnectionAuditCategory} plus a fixed, category-keyed safe message
 * - never the platform exception's own message - so P2-130 can map outcomes
 * by category without ever importing a platform type. The underlying cause
 * is retained only via {@link #getCause()} for internal diagnostics/logging
 * and must never be rendered to a client.
 *
 * <p>A small set of this saga's own already-safe, already-stable exception
 * types (e.g. {@link ConnectorTestFailedException}, {@link
 * ConnectionNotFoundException}, {@code StaleConnectionVersionException},
 * {@code InvalidConnectionTransitionException}, {@link
 * ConnectionEndpointNotYetPersistedException}) are deliberately NOT wrapped
 * in this type - they already live in core packages P2-130 may depend on and
 * already carry closed, safe detail - see {@code
 * ConnectionLifecycleService}'s private {@code safeguard} helper for the
 * exact allow-list.
 */
public final class ConnectionLifecycleFailedException extends RuntimeException {
    private final ConnectionAuditCategory category;

    public ConnectionLifecycleFailedException(ConnectionAuditCategory category, Throwable cause) {
        super(safeMessage(category), cause);
        this.category = Objects.requireNonNull(category, "category");
    }

    public ConnectionAuditCategory category() {
        return category;
    }

    private static String safeMessage(ConnectionAuditCategory category) {
        return switch (Objects.requireNonNull(category, "category")) {
            case AUTHORIZATION -> "operation was not authorized";
            case VALIDATION -> "request failed validation";
            case DESTINATION_POLICY -> "destination could not be validated";
            case SECRET_PROVIDER -> "secret operation did not succeed";
            case CONNECTOR -> "connector operation did not succeed";
            case CONCURRENCY -> "connection state changed concurrently";
            case INTERNAL -> "an internal error occurred";
        };
    }
}
