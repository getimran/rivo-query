package com.knowimran.rivoquery.connection.application.destination;

public enum AddressFamily {
    IPV4(4),
    IPV6(16);

    private final int byteLength;

    AddressFamily(int byteLength) {
        this.byteLength = byteLength;
    }

    int byteLength() {
        return byteLength;
    }
}
