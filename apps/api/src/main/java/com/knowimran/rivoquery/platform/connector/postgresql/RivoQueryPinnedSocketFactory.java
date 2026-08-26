package com.knowimran.rivoquery.platform.connector.postgresql;

import com.knowimran.rivoquery.platform.connector.PostgresqlConnectorProperty;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import javax.net.SocketFactory;

/**
 * The RivoQuery-controlled, non-user-configurable pgjdbc socket hook (ADR-0013).
 *
 * <p><b>How this pins the physical socket without ever resolving the logical hostname.</b>
 * pgjdbc's {@code PGStream.createSocket(int)} calls {@link SocketFactory#createSocket()} (the
 * no-argument overload only - confirmed by disassembling the pinned driver version, see {@link
 * PostgresqlPhysicalJdbcConnector}'s class Javadoc) and then, <em>only if the returned socket is
 * not already connected</em>, resolves {@code hostSpec.getHost()} itself and connects it. This
 * class always returns an <b>already-connected</b> socket from {@link #createSocket()}, so pgjdbc
 * never reaches its own resolve/connect branch - there is no code path left for ordinary driver or
 * OS DNS resolution to run, not on the first attempt and not on a retry, because a retry (trying
 * the next address in {@link #pinnedAddresses}) never calls a name-resolving API either: every
 * candidate is turned into a {@link Socket} target via {@link InetAddress#getByAddress(byte[])},
 * which the JDK documents as performing no reverse or forward name-service lookup at all.
 *
 * <p>TLS hostname verification is a separate, independent step pgjdbc performs afterwards
 * ({@code MakeSSL.convert}/{@code verifyPeerName}), driven entirely by {@code hostSpec.getHost()}
 * (the original JDBC URL host - never this socket's actual remote address). Pinning the transport
 * address here does not touch that verification path at all, which is exactly the property ADR-
 * 0013 requires: the socket dials a vetted IP, but full chain and hostname verification still runs
 * against the canonical original hostname.
 *
 * <p><b>Fail-closed if the driver ever stops using the no-arg contract.</b> Every other {@code
 * SocketFactory} overload (the four hostname/{@link InetAddress}-parameterized ones) throws
 * immediately rather than attempting a connection. If a future pgjdbc version changed {@code
 * PGStream} to call one of those instead, this factory would fail loudly - never silently fall
 * back to an unpinned, potentially DNS-resolving connect. {@code
 * RivoQueryPinnedSocketFactoryTest} pins this behavior with a regression test.
 *
 * <p><b>Forward-looking note for pooled/long-lived connections (out of scope here).</b> pgjdbc
 * reuses the same {@code SocketFactory} instance for a connection's internal query-cancellation
 * socket, which this factory would dial through the same absolute {@link #deadline} captured at
 * construction time. That is correct for this task's one-shot {@code probe()} use, but whoever
 * wires connection pooling (e.g. P2-100) must account for this factory - and its absolute deadline
 * - being tied to a single physical-connection lifetime, not reusable indefinitely across borrows.
 */
public final class RivoQueryPinnedSocketFactory extends SocketFactory {

    private final List<InetAddress> pinnedAddresses;
    private final int port;
    private final Instant deadline;
    private final int perAttemptTimeoutMillis;
    private final Clock deadlineClock;

    /**
     * Invoked by pgjdbc via reflection ({@code java.util.Properties}-arg constructor,
     * {@code org.postgresql.util.ObjectFactory#instantiate}) once per physical connection attempt.
     * Every value this constructor reads is {@code String}-encoded by {@link
     * PostgresqlPhysicalJdbcConnector} (see that class's Javadoc for why: pgjdbc's own connect
     * pipeline was empirically observed to drop non-{@code String}-valued {@link Properties}
     * entries before this factory is instantiated).
     */
    public RivoQueryPinnedSocketFactory(Properties properties) {
        this(properties, Clock.systemUTC());
    }

    /** Test-only: injects a controllable clock to deterministically exercise deadline exhaustion. */
    RivoQueryPinnedSocketFactory(Properties properties, Clock deadlineClock) {
        Objects.requireNonNull(properties, "properties");
        this.deadlineClock = Objects.requireNonNull(deadlineClock, "deadlineClock");

        String addresses = properties.getProperty(PostgresqlPhysicalJdbcConnector.PINNED_ADDRESSES_PROPERTY);
        if (addresses == null || addresses.isBlank()) {
            throw new IllegalStateException("no pinned vetted addresses were supplied");
        }
        this.pinnedAddresses = PostgresqlPhysicalJdbcConnector.decodePinnedAddresses(addresses);

        String portValue = properties.getProperty(PostgresqlPhysicalJdbcConnector.PINNED_PORT_PROPERTY);
        this.port = Integer.parseInt(Objects.requireNonNull(portValue, "pinned port"));

        String deadlineValue = properties.getProperty(PostgresqlPhysicalJdbcConnector.DEADLINE_PROPERTY);
        this.deadline = Instant.parse(Objects.requireNonNull(deadlineValue, "deadline"));

        String connectTimeoutSeconds =
                properties.getProperty(PostgresqlConnectorProperty.CONNECT_TIMEOUT.key(), "10");
        this.perAttemptTimeoutMillis = Integer.parseInt(connectTimeoutSeconds) * 1000;
    }

    /**
     * Tries every pinned address in the order supplied by {@link PostgresqlPhysicalJdbcConnector}
     * (itself deterministic - sorted by family then address bytes) within the single absolute
     * {@link #deadline}. A retry never introduces a new DNS answer: it is bounded to exactly the
     * same {@link #pinnedAddresses} set computed once, before the first attempt.
     */
    @Override
    public Socket createSocket() throws IOException {
        IOException lastFailure = null;
        for (InetAddress candidate : pinnedAddresses) {
            long remainingMillis = Duration.between(deadlineClock.instant(), deadline).toMillis();
            if (remainingMillis <= 0) {
                throw deadlineExceeded(lastFailure);
            }
            int attemptTimeoutMillis = (int) Math.min(remainingMillis, perAttemptTimeoutMillis);
            if (attemptTimeoutMillis <= 0) {
                throw deadlineExceeded(lastFailure);
            }
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(candidate, port), attemptTimeoutMillis);
                return socket;
            } catch (IOException failure) {
                closeQuietly(socket);
                lastFailure = failure;
            }
        }
        throw lastFailure != null ? lastFailure : deadlineExceeded(null);
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // best effort only; the original connect failure is what matters
        }
    }

    private static SocketTimeoutException deadlineExceeded(IOException cause) {
        var timeout = new SocketTimeoutException(
                "rivoquery pinned-address connect deadline exceeded");
        if (cause != null) {
            timeout.initCause(cause);
        }
        return timeout;
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        throw unsupportedOverload();
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localAddress, int localPort)
            throws IOException {
        throw unsupportedOverload();
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        throw unsupportedOverload();
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        throw unsupportedOverload();
    }

    private static IOException unsupportedOverload() {
        return new IOException(
                "RivoQueryPinnedSocketFactory only supports the no-argument createSocket() contract "
                        + "pgjdbc's PGStream uses today; any hostname- or address-parameterized overload "
                        + "would bypass vetted-address pinning, so it is refused rather than silently "
                        + "falling back to ordinary hostname resolution.");
    }
}
