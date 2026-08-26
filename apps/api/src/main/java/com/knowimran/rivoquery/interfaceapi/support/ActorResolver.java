package com.knowimran.rivoquery.interfaceapi.support;

import com.knowimran.rivoquery.identity.ActorContext;
import com.knowimran.rivoquery.identity.AuthorizationDeniedException;
import com.knowimran.rivoquery.identity.WorkspaceActorResolver;
import com.knowimran.rivoquery.interfaceapi.auth.AuthenticatedPrincipal;
import com.knowimran.rivoquery.interfaceapi.auth.AuthenticationInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * The single call every workspace-scoped controller method makes first
 * (PD2-003): authentication has already run in {@link AuthenticationInterceptor}
 * (step 1), this resolves the exact workspace selector (step 2) and the
 * server-side membership (steps 3-4) via {@link WorkspaceActorResolver} (the
 * sanctioned entry point - {@code ArchitectureTest} forbids {@code interfaceapi}
 * from depending on {@code ActorContext.Authority} directly), producing the
 * immutable {@link ActorContext} every application-layer use case requires
 * (step 5) - strictly before any connection/permission/operation lookup.
 */
@Component
public final class ActorResolver {

    private final WorkspaceActorResolver resolver;

    public ActorResolver(WorkspaceActorResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    /**
     * Re-throws {@code resolver.resolve}'s {@link AuthorizationDeniedException}
     * (thrown when the caller has no current active membership in {@code
     * workspaceId}) as the more specific {@link WorkspaceAccessDeniedException}
     * so {@code SafeProblemHandler} can map it to the distinct {@code
     * WORKSPACE_ACCESS_DENIED} code - this call site is the only place an
     * {@code AuthorizationDeniedException} can mean "not a member of this
     * workspace" rather than "member, but not an administrator" (that check
     * always happens later, via {@code ActorContext.requireAdministrator()},
     * strictly after this method has already returned).
     */
    public ActorContext resolve(HttpServletRequest request) {
        AuthenticatedPrincipal principal = AuthenticationInterceptor.currentPrincipal(request);
        var workspaceId = WorkspaceHeaderResolver.requireExactly(request);
        try {
            return resolver.resolve(principal.issuer(), principal.subject(), workspaceId);
        } catch (AuthorizationDeniedException notAMember) {
            throw new WorkspaceAccessDeniedException();
        }
    }
}
