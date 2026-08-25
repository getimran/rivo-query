package com.knowimran.rivoquery.connection.application.destination;

import java.util.Arrays;
import java.util.Objects;

public final class VettedAddress {
    private final AddressFamily family;
    private final byte[] address;

    public VettedAddress(AddressFamily family, byte[] address) {
        this.family = Objects.requireNonNull(family, "family");
        Objects.requireNonNull(address, "address");
        if (address.length != family.byteLength()) {
            throw new IllegalArgumentException("address length does not match family");
        }
        this.address = address.clone();
    }

    public AddressFamily family() {
        return family;
    }

    public byte[] addressBytes() {
        return address.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof VettedAddress that
                && family == that.family
                && Arrays.equals(address, that.address);
    }

    @Override
    public int hashCode() {
        return 31 * family.hashCode() + Arrays.hashCode(address);
    }

    @Override
    public String toString() {
        return "VettedAddress[REDACTED]";
    }
}
