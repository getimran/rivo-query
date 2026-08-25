package com.knowimran.rivoquery.connection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.connector.ConnectorCapabilities;
import com.knowimran.rivoquery.connection.connector.ConnectorOption;
import com.knowimran.rivoquery.connection.connector.ConnectorOutcomeCode;
import com.knowimran.rivoquery.connection.connector.ConnectorResult;
import com.knowimran.rivoquery.identity.ActorId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConnectorAuditBoundaryTest {
    private static final String ULID = "01J6MZ4H9ST5N7YQ2C8W3V0KFX";

    @Test
    void connectorOptionsAndOutcomesAreClosedServerOwnedEnums() {
        var capabilities = new ConnectorCapabilities(
                DatabaseKind.POSTGRESQL, Set.of(ConnectorOption.TCP_KEEP_ALIVE));
        assertThat(capabilities.safeOptions()).containsExactly(ConnectorOption.TCP_KEEP_ALIVE);
        assertThat(new ConnectorResult(true, ConnectorOutcomeCode.CONNECTION_TEST_SUCCEEDED).successful())
                .isTrue();
        assertThatThrownBy(() -> ConnectorOption.valueOf("PASSWORD_unique_canary"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConnectorOutcomeCode.valueOf("DRIVER_unique_canary"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConnectorResult(true, ConnectorOutcomeCode.CONNECTION_TEST_FAILED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void auditActionsOutcomesAndCategoriesCannotCarryCanaryText() {
        var event = new ConnectionAuditPort.Event(
                new WorkspaceId("wsp_" + ULID),
                new ActorId("act_" + ULID),
                new ConnectionId("con_" + ULID),
                new AuditRequestId("request-01"),
                ConnectionAuditAction.ROTATE_SECRET,
                ConnectionAuditOutcome.FAILED,
                ConnectionAuditCategory.SECRET_PROVIDER,
                Instant.parse("2026-08-20T12:00:00Z"));

        assertThat(event.action()).isEqualTo(ConnectionAuditAction.ROTATE_SECRET);
        assertThatThrownBy(() -> ConnectionAuditAction.valueOf("PASSWORD_unique_canary"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConnectionAuditOutcome.valueOf("DRIVER_unique_canary"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConnectionAuditCategory.valueOf("SECRET_unique_canary"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditRequestId(" padded "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
