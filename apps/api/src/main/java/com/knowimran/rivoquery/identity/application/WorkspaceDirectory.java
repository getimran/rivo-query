package com.knowimran.rivoquery.identity.application;

import com.knowimran.rivoquery.identity.WorkspaceId;
import java.util.Optional;

/**
 * Read-only lookup of a workspace's own display name ({@code
 * workspaces.display_name}, P2-060 schema). Workspace provisioning itself is
 * out of Phase 2 scope (docs/product-decisions/phase-2.md); this port only
 * ever reads.
 */
public interface WorkspaceDirectory {
    Optional<String> displayNameOf(WorkspaceId workspaceId);
}
