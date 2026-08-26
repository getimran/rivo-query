package com.knowimran.rivoquery.interfaceapi.workspace;

import com.knowimran.rivoquery.identity.ActorContext;
import com.knowimran.rivoquery.identity.MembershipId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import com.knowimran.rivoquery.identity.application.CurrentMembershipRepository;
import com.knowimran.rivoquery.identity.application.MembershipDirectoryEntry;
import com.knowimran.rivoquery.identity.application.WorkspaceDirectory;
import com.knowimran.rivoquery.interfaceapi.auth.AuthenticatedPrincipal;
import com.knowimran.rivoquery.interfaceapi.auth.AuthenticationInterceptor;
import com.knowimran.rivoquery.interfaceapi.generated.api.WorkspacesApi;
import com.knowimran.rivoquery.interfaceapi.generated.model.MembershipPage;
import com.knowimran.rivoquery.interfaceapi.generated.model.MembershipSummary;
import com.knowimran.rivoquery.interfaceapi.generated.model.WorkspacePage;
import com.knowimran.rivoquery.interfaceapi.generated.model.WorkspaceSummary;
import com.knowimran.rivoquery.interfaceapi.support.ActorResolver;
import com.knowimran.rivoquery.interfaceapi.support.ValidationFailedException;
import com.knowimran.rivoquery.interfaceapi.support.WorkspaceHeaderResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wraps {@link CurrentMembershipRepository}/{@link WorkspaceDirectory}.
 *
 * <p>{@code discoverWorkspaces} is deliberately unscoped (PD2-002/PD2-003):
 * it authenticates but never resolves an {@link ActorContext} - there is no
 * selected workspace yet, and {@link WorkspaceHeaderResolver#requireAbsent}
 * rejects a caller who supplies one. Its result set comes entirely from
 * {@link CurrentMembershipRepository#findActiveMembershipsForActor}, which
 * has no cursor/limit parameters of its own (a plain full list, scoped only
 * to the authenticated actor's own memberships - see that port's Javadoc);
 * pagination is therefore applied in-memory here, ordered by {@code
 * workspaceId} (each actor has at most one active membership per workspace,
 * so this ordering key is unique). This is a deliberate, bounded scope
 * decision - see docs/IMPLEMENTATION_TASKS.md P2-130 evidence - safe because
 * the number of workspaces a single actor belongs to is not expected to
 * approach a scale where in-memory sorting/filtering matters.
 *
 * <p>{@code listWorkspaceMemberships} is administrator-only and workspace
 * scoped, backed by {@link CurrentMembershipRepository#findActiveMemberships}'s
 * real database-level cursor pagination.
 */
@RestController
public class WorkspaceController implements WorkspacesApi {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final String WORKSPACE_DISPLAY_NAME_UNAVAILABLE = "(workspace name unavailable)";

    private final CurrentMembershipRepository memberships;
    private final WorkspaceDirectory directory;
    private final ActorResolver actorResolver;
    private final ObjectProvider<HttpServletRequest> requestProvider;

    /** See {@code OperationController}'s identical Javadoc for why {@link ObjectProvider} rather than {@link HttpServletRequest}. */
    public WorkspaceController(
            CurrentMembershipRepository memberships,
            WorkspaceDirectory directory,
            ActorResolver actorResolver,
            ObjectProvider<HttpServletRequest> requestProvider) {
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.actorResolver = Objects.requireNonNull(actorResolver, "actorResolver");
        this.requestProvider = Objects.requireNonNull(requestProvider, "requestProvider");
    }

    private HttpServletRequest request() {
        return requestProvider.getObject();
    }

    @Override
    public ResponseEntity<WorkspacePage> discoverWorkspaces(String xRequestId, String cursor, Integer limit) {
        HttpServletRequest request = request();
        AuthenticatedPrincipal principal = AuthenticationInterceptor.currentPrincipal(request);
        WorkspaceHeaderResolver.requireAbsent(request);

        List<MembershipDirectoryEntry> all = memberships.findActiveMembershipsForActor(
                principal.issuer(), principal.subject());
        List<MembershipDirectoryEntry> sorted = all.stream()
                .sorted(Comparator.comparing(entry -> entry.workspaceId().value()))
                .toList();

        Optional<String> after = parseWorkspaceCursor(cursor);
        int pageSize = limit == null ? DEFAULT_PAGE_SIZE : limit;
        List<MembershipDirectoryEntry> page = sorted.stream()
                .filter(entry -> after.isEmpty() || entry.workspaceId().value().compareTo(after.orElseThrow()) > 0)
                .limit(pageSize)
                .toList();

        List<WorkspaceSummary> items = page.stream().map(this::toWorkspaceSummary).toList();
        WorkspacePage body = new WorkspacePage(items, true);
        if (page.size() == pageSize && !page.isEmpty()) {
            body.setNextCursor(page.get(page.size() - 1).workspaceId().value());
        }
        return ResponseEntity.ok(body);
    }

    @Override
    public ResponseEntity<MembershipPage> listWorkspaceMemberships(
            String xRivoQueryWorkspaceId, String xRequestId, String cursor, Integer limit) {
        ActorContext actor = actorResolver.resolve(request());
        actor.requireAdministrator();

        Optional<MembershipId> after = parseMembershipCursor(cursor);
        int pageSize = limit == null ? DEFAULT_PAGE_SIZE : limit;
        List<MembershipDirectoryEntry> page = memberships.findActiveMemberships(actor.workspaceId(), after, pageSize);

        List<MembershipSummary> items = page.stream().map(WorkspaceController::toMembershipSummary).toList();
        MembershipPage body = new MembershipPage(items);
        if (page.size() == pageSize && !page.isEmpty()) {
            body.setNextCursor(page.get(page.size() - 1).membershipId().value());
        }
        return ResponseEntity.ok(body);
    }

    private WorkspaceSummary toWorkspaceSummary(MembershipDirectoryEntry entry) {
        String displayName = directory.displayNameOf(entry.workspaceId()).orElse(WORKSPACE_DISPLAY_NAME_UNAVAILABLE);
        return new WorkspaceSummary(
                entry.workspaceId().value(), displayName, toDto(entry.role()));
    }

    private static MembershipSummary toMembershipSummary(MembershipDirectoryEntry entry) {
        return new MembershipSummary(entry.membershipId().value(), entry.displayName(), toDto(entry.role()));
    }

    private static com.knowimran.rivoquery.interfaceapi.generated.model.WorkspaceRole toDto(
            com.knowimran.rivoquery.identity.WorkspaceRole role) {
        return com.knowimran.rivoquery.interfaceapi.generated.model.WorkspaceRole.valueOf(role.name());
    }

    private static Optional<String> parseWorkspaceCursor(String cursor) {
        if (cursor == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new WorkspaceId(cursor).value());
        } catch (IllegalArgumentException malformed) {
            throw new ValidationFailedException("cursor is not a valid workspace identifier");
        }
    }

    private static Optional<MembershipId> parseMembershipCursor(String cursor) {
        if (cursor == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new MembershipId(cursor));
        } catch (IllegalArgumentException malformed) {
            throw new ValidationFailedException("cursor is not a valid membership identifier");
        }
    }
}
