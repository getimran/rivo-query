package com.knowimran.rivoquery.interfaceapi.support;

/**
 * Thrown by {@link ActorResolver#resolve} when the authenticated caller has
 * no current active membership in the selected workspace (the caller-known
 * shape of {@code ActorContext.Authority.resolve}'s {@code
 * AuthorizationDeniedException} - see {@link ActorResolver} for why this is
 * the one call site where that generic exception can be re-thrown as this
 * more specific, REST-mappable one: every other {@code AuthorizationDeniedException}
 * reaching {@code SafeProblemHandler} originates from {@code
 * ActorContext.requireAdministrator()} instead, after {@code resolve} has
 * already returned successfully).
 */
public final class WorkspaceAccessDeniedException extends RuntimeException {
    public WorkspaceAccessDeniedException() {
        super("actor has no active membership in the selected workspace");
    }
}
