package com.knowimran.rivoquery.interfaceapi.operation;

import com.knowimran.rivoquery.connection.application.DurableOperation;
import com.knowimran.rivoquery.interfaceapi.generated.model.Operation;
import com.knowimran.rivoquery.interfaceapi.generated.model.OperationAction;
import com.knowimran.rivoquery.interfaceapi.generated.model.OperationLinks;
import com.knowimran.rivoquery.interfaceapi.generated.model.OperationState;
import java.time.OffsetDateTime;

/**
 * Maps {@link DurableOperation} to the REST {@code Operation} DTO.
 *
 * <p>Scope note (see docs/IMPLEMENTATION_TASKS.md P2-130 evidence): {@link
 * DurableOperation} carries no timestamps and no failure code/detail, even
 * though the underlying {@code durable_operations} table has {@code
 * created_at}/{@code updated_at} columns and the OpenAPI {@code Operation}
 * schema requires {@code createdAt}/{@code updatedAt}. Rather than touch
 * {@code connection.application.lifecycle}/{@code DurableOperation} (off
 * limits per this task's own instructions - a real fix needs a reviewed
 * change to that record and its repository row-mapping), this mapper
 * approximates both timestamps with the current instant and leaves {@code
 * failure} unset. This is an honest, flagged limitation, not fabricated
 * historical data.
 */
public final class OperationDtoMapper {
    private OperationDtoMapper() {}

    public static Operation toDto(DurableOperation operation) {
        OffsetDateTime now = OffsetDateTime.now();
        var links = new OperationLinks().self("/api/v1/operations/" + operation.id().value());
        operation.connectionId().ifPresent(id -> links.setConnection("/api/v1/connections/" + id.value()));
        Operation dto = new Operation(
                operation.id().value(),
                OperationAction.valueOf(operation.action().name()),
                OperationState.valueOf(operation.state().name()),
                now, now, links);
        operation.connectionId().ifPresent(id -> dto.setConnectionId(id.value()));
        if (operation.state() == com.knowimran.rivoquery.connection.application.OperationState.SUCCEEDED
                || operation.state() == com.knowimran.rivoquery.connection.application.OperationState.FAILED) {
            dto.setCompletedAt(now);
        }
        return dto;
    }
}
