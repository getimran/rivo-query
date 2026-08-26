package com.knowimran.rivoquery.interfaceapi.permission;

/**
 * The {@code membershipId} path segment of a connection-permission mutation
 * does not identify an eligible grant target. Deliberately a single,
 * indistinguishable signal for every ineligible case the OpenAPI contract
 * names together ("unknown, inactive, administrator, or foreign memberships
 * are indistinguishable"): the id does not exist at all, exists but is
 * {@code INACTIVE}, exists but belongs to a different workspace than the
 * one selected, or exists and is {@code ACTIVE} but has role {@code
 * ADMINISTRATOR} (administrators derive MANAGE from membership itself and
 * are never eligible for an explicit, non-MANAGE grant row).
 */
public final class MembershipNotEligibleException extends RuntimeException {
    public MembershipNotEligibleException() {
        super("membership is not an eligible connection-permission grant target");
    }
}
