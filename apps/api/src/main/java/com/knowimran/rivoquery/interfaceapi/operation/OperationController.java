package com.knowimran.rivoquery.interfaceapi.operation;

import com.knowimran.rivoquery.connection.OperationId;
import com.knowimran.rivoquery.connection.application.DurableOperationRepository;
import com.knowimran.rivoquery.identity.ActorContext;
import com.knowimran.rivoquery.interfaceapi.generated.api.OperationsApi;
import com.knowimran.rivoquery.interfaceapi.generated.model.Operation;
import com.knowimran.rivoquery.interfaceapi.support.ActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OperationController implements OperationsApi {

    private final ActorResolver actorResolver;
    private final DurableOperationRepository operations;
    private final ObjectProvider<HttpServletRequest> requestProvider;

    /**
     * Constructor-injects {@link ObjectProvider} rather than {@link
     * HttpServletRequest} directly: the latter resolves to a request-scoped
     * proxy only in a real servlet {@code WebApplicationContext}, so a
     * non-web context (e.g. a {@code WebApplicationType.NONE} migration-only
     * integration test that still exercises the full {@code
     * RivoQueryApplication} component graph) would otherwise fail this
     * singleton's construction outright, even though such a context never
     * actually invokes a handler method. {@code ObjectProvider} defers the
     * lookup to first use, which only ever happens while handling a real
     * request.
     */
    public OperationController(
            ActorResolver actorResolver, DurableOperationRepository operations,
            ObjectProvider<HttpServletRequest> requestProvider) {
        this.actorResolver = Objects.requireNonNull(actorResolver, "actorResolver");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.requestProvider = Objects.requireNonNull(requestProvider, "requestProvider");
    }

    @Override
    public ResponseEntity<Operation> getOperation(
            String operationIdValue, String xRivoQueryWorkspaceId, String xRequestId) {
        ActorContext actor = actorResolver.resolve(requestProvider.getObject());
        OperationId operationId;
        try {
            operationId = new OperationId(operationIdValue);
        } catch (IllegalArgumentException malformed) {
            throw new OperationNotFoundException();
        }
        var operation = operations.findByWorkspaceAndId(actor.workspaceId(), operationId)
                .filter(candidate -> candidate.actorId().equals(actor.actorId()))
                .orElseThrow(OperationNotFoundException::new);
        return ResponseEntity.ok(OperationDtoMapper.toDto(operation));
    }
}
