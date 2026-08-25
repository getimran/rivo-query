package com.knowimran.rivoquery.identity;

public record ActorId(String value) {
    public ActorId {
        value = CanonicalOpaqueId.require(value, "act");
    }
}
