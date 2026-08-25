package com.knowimran.rivoquery.identity;

public record MembershipId(String value) {
    public MembershipId {
        value = CanonicalOpaqueId.require(value, "mem");
    }
}
