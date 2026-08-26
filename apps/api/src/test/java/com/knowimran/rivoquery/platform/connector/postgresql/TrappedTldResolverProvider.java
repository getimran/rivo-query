package com.knowimran.rivoquery.platform.connector.postgresql;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * A JVM-wide DNS resolver trap, registered via {@code
 * META-INF/services/java.net.spi.InetAddressResolverProvider} (test scope only - see JEP 418).
 * Every lookup is delegated unchanged to the platform's built-in resolver, <b>except</b> a
 * hostname ending in {@link #TRAP_SUFFIX}, which is recorded and rejected with an {@link
 * UnknownHostException} without the built-in resolver ever being consulted.
 *
 * <p>This is a stronger, definitive proof than the {@code .invalid}-TLD-only approach elsewhere in
 * this test suite: RFC 2606's {@code .invalid} TLD only proves a hostname <em>would fail</em> if
 * resolution were ever attempted (an indirect, real-but-incomplete signal - a lookup that failed
 * silently or non-fatally somewhere would not be caught by it). This trap directly records whether
 * {@link InetAddressResolver#lookupByName} was ever invoked for the trapped hostname at all, so a
 * test can assert zero recorded lookups after a full operation completes - proving no lookup was
 * attempted, not merely that one would have failed had it happened.
 *
 * <p>Because this is registered JVM-wide for the entire test process (both this module's unit and
 * integration tests share one JVM fork per Surefire/Failsafe execution), it is deliberately a
 * behavior-preserving pass-through for every hostname except {@link #TRAP_SUFFIX} - so it cannot
 * affect any other test's real DNS/Testcontainers/Docker-host resolution.
 */
public final class TrappedTldResolverProvider extends InetAddressResolverProvider {

    /**
     * Every hostname used by this trap's tests ends in this suffix. It is deliberately also under
     * the RFC 2606 {@code .invalid} TLD, so both proofs (guaranteed-never-resolves and
     * definitively-never-attempted) hold for the exact same hostname.
     */
    static final String TRAP_SUFFIX = ".rivoquery-resolver-trap.invalid";

    private static final List<String> RECORDED_LOOKUPS = new CopyOnWriteArrayList<>();

    /** Invoked by {@link java.util.ServiceLoader} - must be public and have a public no-arg constructor. */
    public TrappedTldResolverProvider() {}

    @Override
    public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver builtin = configuration.builtinResolver();
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy)
                    throws UnknownHostException {
                if (isTrapped(host)) {
                    RECORDED_LOOKUPS.add(host);
                    throw new UnknownHostException(
                            host + ": trapped by " + TrappedTldResolverProvider.class.getSimpleName()
                                    + " - no real lookup was attempted");
                }
                return builtin.lookupByName(host, lookupPolicy);
            }

            @Override
            public String lookupByAddress(byte[] addr) throws UnknownHostException {
                return builtin.lookupByAddress(addr);
            }
        };
    }

    @Override
    public String name() {
        return "rivoquery-test-resolver-trap";
    }

    private static boolean isTrapped(String host) {
        return host != null && host.toLowerCase(Locale.ROOT).endsWith(TRAP_SUFFIX);
    }

    /** Every trapped hostname recorded so far, across the whole JVM process. */
    static List<String> recordedLookups() {
        return List.copyOf(RECORDED_LOOKUPS);
    }

    /** Test isolation: call before/after a test that asserts on {@link #recordedLookups()}. */
    static void reset() {
        RECORDED_LOOKUPS.clear();
    }
}
