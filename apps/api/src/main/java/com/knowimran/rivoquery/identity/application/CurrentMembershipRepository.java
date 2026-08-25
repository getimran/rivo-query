package com.knowimran.rivoquery.identity.application;

import com.knowimran.rivoquery.identity.CurrentMembership;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.util.Optional;

public interface CurrentMembershipRepository {
    Optional<CurrentMembership> findCurrentActiveMembership(String issuer, String subject, WorkspaceId workspaceId);
}
