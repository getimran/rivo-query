package com.knowimran.rivoquery.platform.destination.policy;

import com.knowimran.rivoquery.connection.application.destination.AddressFamily;
import com.knowimran.rivoquery.connection.application.destination.VettedAddress;
import java.util.Optional;

/** Public facade over the package-private strict IPv4/IPv6 literal parsers. */
public final class IpLiteral {
    private IpLiteral() {}

    public static Optional<VettedAddress> tryParse(String host) {
        if (Ipv4Literal.isStrict(host)) {
            return Optional.of(new VettedAddress(AddressFamily.IPV4, Ipv4Literal.parse(host)));
        }
        if (Ipv6Literal.isStrict(host)) {
            return Optional.of(new VettedAddress(AddressFamily.IPV6, Ipv6Literal.parse(host)));
        }
        return Optional.empty();
    }
}
