package com.knowimran.rivoquery.platform.destination.dns;

import java.util.List;
import java.util.Objects;

/**
 * The complete, bounded CNAME-plus-A/AAAA resolution outcome for one name. A resolver must never
 * return an instance with an empty {@code answers} list; an empty result is a fail-closed
 * condition ({@link DnsResolutionException}), not a permissive empty success.
 */
public record DnsResolution(List<String> cnameChain, List<DnsAnswer> answers) {
    public DnsResolution {
        cnameChain = List.copyOf(Objects.requireNonNull(cnameChain, "cnameChain"));
        answers = List.copyOf(Objects.requireNonNull(answers, "answers"));
        if (answers.isEmpty()) {
            throw new IllegalArgumentException("dns resolution must contain at least one answer");
        }
    }

    @Override
    public String toString() {
        return "DnsResolution[REDACTED]";
    }
}
