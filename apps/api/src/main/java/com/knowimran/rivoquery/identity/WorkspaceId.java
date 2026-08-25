package com.knowimran.rivoquery.identity;

public record WorkspaceId(String value) {
    public WorkspaceId {
        value = CanonicalOpaqueId.require(value, "wsp");
    }
}
