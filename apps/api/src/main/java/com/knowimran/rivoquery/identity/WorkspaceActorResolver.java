package com.knowimran.rivoquery.identity;

import com.knowimran.rivoquery.identity.application.CurrentMembershipRepository;
import java.util.Objects;

/**
 * The sanctioned entry point for resolving an {@link ActorContext} from
 * outside the {@code identity} package. {@code
 * ArchitectureTest.transport_cannot_mint_actor_contexts} forbids {@code
 * interfaceapi}/{@code interfacemcp} from depending on {@link
 * ActorContext.Authority} directly (by class name), so transport code goes
 * through this thin, reviewable wrapper instead - keeping the exact identity
 * boundary rule ("authenticate first, then resolve server-side membership,
 * never trust a caller-asserted role") in one place inside {@code identity}.
 */
public final class WorkspaceActorResolver {
    private final ActorContext.Authority authority;

    public WorkspaceActorResolver(CurrentMembershipRepository memberships) {
        this.authority = new ActorContext.Authority(Objects.requireNonNull(memberships, "memberships"));
    }

    public ActorContext resolve(String issuer, String subject, WorkspaceId workspaceId) {
        return authority.resolve(issuer, subject, workspaceId);
    }
}
