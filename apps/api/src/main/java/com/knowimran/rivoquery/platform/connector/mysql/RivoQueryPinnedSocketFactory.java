package com.knowimran.rivoquery.platform.connector.mysql;

import com.knowimran.rivoquery.platform.connector.MysqlConnectorProperty;
import com.mysql.cj.conf.PropertyKey;
import com.mysql.cj.conf.PropertySet;
import com.mysql.cj.log.Log;
import com.mysql.cj.protocol.ExportControlled;
import com.mysql.cj.protocol.ServerSession;
import com.mysql.cj.protocol.SocketConnection;
import com.mysql.cj.protocol.SocketFactory;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The RivoQuery-controlled, non-user-configurable MySQL Connector/J socket hook (ADR-0013).
 *
 * <p><b>Why Connector/J's own {@code SocketFactory} contract makes this simpler than pgjdbc's.</b>
 * Unlike pgjdbc's {@code javax.net.SocketFactory} (a zero-argument {@code createSocket()} whose
 * caller resolves the hostname itself only if the returned socket is not already connected),
 * Connector/J defines its own {@code com.mysql.cj.protocol.SocketFactory} interface whose {@link
 * #connect(String, int, com.mysql.cj.conf.PropertySet, int)} method receives the JDBC URL host as
 * an explicit {@code String} parameter and is fully responsible for creating <em>and</em> connecting
 * the socket itself (confirmed by decompiling {@code com.mysql.cj.protocol.a.NativeSocketConnection
 * #connect} and {@code com.mysql.cj.protocol.StandardSocketFactory#connect} for the pinned 9.7.0
 * driver: {@code NativeSocketConnection} stores the given hostname in its own {@code host} field
 * *before* delegating to {@code socketFactory.connect(...)}, and the default {@code
 * StandardSocketFactory} implementation is the one that calls {@code InetAddress.getAllByName(host)}
 * - nothing in the driver resolves the hostname on this class's behalf). This factory therefore
 * never receives an already-resolved address from the driver and never needs to "trick" the driver
 * into skipping its own resolution step - it simply never calls a hostname-resolving API at all,
 * dialing only {@link #pinnedAddresses} (decoded from an internal, non-client-reachable connection
 * property - see {@link MysqlPhysicalJdbcConnector}) via {@link InetAddress#getByAddress(byte[])},
 * which the JDK documents as performing no forward or reverse name-service lookup.
 *
 * <p><b>TLS hostname verification is untouched.</b> {@code NativeSocketConnection} stores the
 * original {@code hostName} argument in its own {@code host} field regardless of what address this
 * factory's {@link #connect} actually dials - {@link SocketConnection#getHost()} therefore always
 * returns the original JDBC URL host, never this socket's physical remote address. {@link
 * #performTlsHandshake} delegates unchanged to {@code com.mysql.cj.protocol.ExportControlled
 * #performTlsHandshake} (the same static method {@code StandardSocketFactory} itself calls), which
 * performs the TLS handshake and, when {@code sslMode=VERIFY_IDENTITY}, full chain and hostname/IP
 * subject-alternative-name verification keyed entirely off {@code socketConnection.getHost()} - this
 * class does not reimplement or bypass that verification in any way.
 *
 * <p><b>Instantiation contract.</b> Connector/J instantiates a {@code SocketFactory} via {@code
 * AbstractSocketConnection#createSocketFactory}, which calls {@code
 * Class#getConstructor((Class<?>[]) null)} - the public no-argument constructor only (confirmed by
 * decompiling {@code com.mysql.cj.util.Util#getInstance}/{@code #handleNewInstance} for 9.7.0). All
 * per-connection state (pinned addresses, port, absolute deadline) is therefore read from the
 * {@code PropertySet} argument of {@link #connect}, not from a constructor parameter.
 */
public final class RivoQueryPinnedSocketFactory implements SocketFactory {

    private final Clock deadlineClock;
    private Socket rawSocket;

    /** Invoked by Connector/J via reflection (public no-argument constructor only). */
    public RivoQueryPinnedSocketFactory() {
        this(Clock.systemUTC());
    }

    /** Test-only: injects a controllable clock to deterministically exercise deadline exhaustion. */
    RivoQueryPinnedSocketFactory(Clock deadlineClock) {
        this.deadlineClock = Objects.requireNonNull(deadlineClock, "deadlineClock");
    }

    /**
     * Tries every pinned address supplied by {@link MysqlPhysicalJdbcConnector} (itself
     * deterministic - sorted by family then address bytes) within the single absolute deadline
     * also supplied through {@code pset}. A retry never introduces a new DNS answer: it is bounded
     * to exactly the same pinned address set decoded once, before the first attempt, and no
     * hostname-resolving API is ever called - {@code hostname} is accepted only because the {@link
     * SocketFactory} interface requires it, and is otherwise ignored for dialing purposes (the
     * original hostname still reaches TLS verification independently, via {@code
     * NativeSocketConnection}'s own bookkeeping - see this class's Javadoc).
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T extends Closeable> T connect(String hostname, int portNumber, PropertySet pset, int loginTimeout)
            throws IOException {
        Objects.requireNonNull(pset, "pset");
        List<InetAddress> pinnedAddresses = MysqlPhysicalJdbcConnector.decodePinnedAddresses(
                requireProperty(pset, MysqlPhysicalJdbcConnector.PINNED_ADDRESSES_PROPERTY));
        int pinnedPort = Integer.parseInt(
                requireProperty(pset, MysqlPhysicalJdbcConnector.PINNED_PORT_PROPERTY));
        Instant deadline = Instant.parse(
                requireProperty(pset, MysqlPhysicalJdbcConnector.DEADLINE_PROPERTY));
        int perAttemptTimeoutMillis = Integer.parseInt(
                pset.getStringProperty(MysqlConnectorProperty.CONNECT_TIMEOUT.key()).getStringValue());

        IOException lastFailure = null;
        for (InetAddress candidate : pinnedAddresses) {
            long remainingMillis = Duration.between(deadlineClock.instant(), deadline).toMillis();
            if (remainingMillis <= 0) {
                throw deadlineExceeded(lastFailure);
            }
            int attemptTimeoutMillis = (int) Math.min(remainingMillis, Math.max(1, perAttemptTimeoutMillis));
            Socket socket = new Socket();
            try {
                configureSocket(socket, pset);
                socket.connect(new InetSocketAddress(candidate, pinnedPort), attemptTimeoutMillis);
                this.rawSocket = socket;
                return (T) socket;
            } catch (IOException failure) {
                closeQuietly(socket);
                lastFailure = failure;
            }
        }
        throw lastFailure != null ? lastFailure : deadlineExceeded(null);
    }

    /**
     * Delegates unchanged to Connector/J's own {@code ExportControlled#performTlsHandshake} - the
     * exact static method {@code StandardSocketFactory} itself calls - so TLS chain/hostname
     * verification behavior is byte-for-byte identical to the driver's default socket factory; the
     * only difference is which raw socket was dialed to reach this point.
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T extends Closeable> T performTlsHandshake(SocketConnection socketConnection, ServerSession serverSession)
            throws IOException {
        return performTlsHandshake(socketConnection, serverSession, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Closeable> T performTlsHandshake(
            SocketConnection socketConnection, ServerSession serverSession, Log log) throws IOException {
        Socket sslSocket = ExportControlled.performTlsHandshake(
                this.rawSocket, socketConnection, serverSession == null ? null : serverSession.getServerVersion(), log);
        return (T) sslSocket;
    }

    /**
     * S2 fix: replicates {@code com.mysql.cj.protocol.StandardSocketFactory#configureSocket}
     * (decompiled for 9.7.0) exactly, applied to this pinned socket before connecting instead of
     * after - the default {@code StandardSocketFactory} calls it immediately before its own {@code
     * Socket.connect}. {@code NativeSocketConnection#connect} never applies these options itself (it
     * only sets {@code socketTimeout} as {@code SO_TIMEOUT} <em>after</em> this factory returns), so
     * without this, {@code tcpNoDelay}/{@code tcpKeepAlive} - the latter set to {@code true} by {@link
     * com.knowimran.rivoquery.platform.connector.MysqlConnectorDialect} and advertised via {@link
     * com.knowimran.rivoquery.connection.connector.ConnectorOption#TCP_KEEP_ALIVE} - would be silently
     * inert on every connection this connector opens.
     */
    private static void configureSocket(Socket socket, PropertySet pset) throws SocketException {
        socket.setTcpNoDelay(booleanProperty(pset, PropertyKey.tcpNoDelay));
        socket.setKeepAlive(booleanProperty(pset, PropertyKey.tcpKeepAlive));
        int receiveBufferSize = integerProperty(pset, PropertyKey.tcpRcvBuf);
        if (receiveBufferSize > 0) {
            socket.setReceiveBufferSize(receiveBufferSize);
        }
        int sendBufferSize = integerProperty(pset, PropertyKey.tcpSndBuf);
        if (sendBufferSize > 0) {
            socket.setSendBufferSize(sendBufferSize);
        }
        int trafficClass = integerProperty(pset, PropertyKey.tcpTrafficClass);
        if (trafficClass > 0) {
            socket.setTrafficClass(trafficClass);
        }
    }

    private static boolean booleanProperty(PropertySet pset, PropertyKey key) {
        return pset.getBooleanProperty(key).getValue();
    }

    private static int integerProperty(PropertySet pset, PropertyKey key) {
        return pset.getIntegerProperty(key).getValue();
    }

    private static String requireProperty(PropertySet pset, String key) {
        var property = pset.getStringProperty(key);
        String value = property == null ? null : property.getStringValue();
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("no " + key + " was supplied");
        }
        return value;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // best effort only; the original connect failure is what matters
        }
    }

    private static SocketTimeoutException deadlineExceeded(IOException cause) {
        var timeout = new SocketTimeoutException("rivoquery pinned-address connect deadline exceeded");
        if (cause != null) {
            timeout.initCause(cause);
        }
        return timeout;
    }
}
