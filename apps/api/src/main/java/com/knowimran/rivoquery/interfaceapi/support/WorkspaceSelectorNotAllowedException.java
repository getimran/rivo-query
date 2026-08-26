package com.knowimran.rivoquery.interfaceapi.support;

/**
 * Thrown when {@code X-RivoQuery-Workspace-Id} is supplied to the unscoped
 * workspace-discovery operation (PD2-002/PD2-003): that operation derives its
 * result entirely from server-side memberships and never accepts a selector.
 */
public final class WorkspaceSelectorNotAllowedException extends RuntimeException {
    public WorkspaceSelectorNotAllowedException() {
        super("workspace discovery does not accept a workspace selector");
    }
}
