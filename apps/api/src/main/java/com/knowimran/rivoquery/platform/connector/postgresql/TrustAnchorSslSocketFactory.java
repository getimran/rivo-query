package com.knowimran.rivoquery.platform.connector.postgresql;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Objects;
import java.util.Properties;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * Test-only {@code sslfactory} implementation that performs full PKIX chain validation against a
 * single caller-supplied CA certificate instead of the JVM's default trust store.
 *
 * <p><b>Never used in production.</b> {@link PostgresqlPhysicalJdbcConnector} only sets the pgjdbc
 * {@code sslfactory} property to this class when constructed with a non-empty {@code
 * testTrustAnchor} (see its Javadoc). Production wiring instead sets {@code sslfactory} to
 * pgjdbc's own {@code org.postgresql.ssl.DefaultJavaSSLFactory}, which performs full PKIX chain
 * validation against the JVM's default trust store (cacerts) - <b>not</b> pgjdbc's {@code
 * sslfactory}-unset default. Leaving {@code sslfactory} unset resolves to pgjdbc's {@code
 * LibPQFactory}, which requires an {@code sslrootcert} file (defaulting to {@code
 * ${user.home}/.postgresql/root.crt}) that does not exist in any real deployment and would make
 * every production connection fail closed with "Could not open SSL root certificate file" -
 * confirmed by reading {@code org.postgresql.ssl.LibPQFactory} in the pinned 42.7.11 sources. This
 * class exists solely so Testcontainers-issued, self-signed certificates can be trusted in tests
 * without installing anything into the JVM-wide default trust store.
 *
 * <p>This class performs real chain and hostname verification, not a bypass: hostname
 * verification itself is driven by pgjdbc's {@code PGjdbcHostnameVerifier}, which runs after this
 * factory hands back a connected {@link javax.net.ssl.SSLSocket} and always checks the original
 * JDBC URL host - a certificate whose subject alternative name is for a different host still fails
 * verification even when signed by the trusted CA configured here. The same is true of the
 * production {@code DefaultJavaSSLFactory} path: hostname verification is a separate step that
 * runs after either factory produces a socket, confirmed by reading {@code
 * org.postgresql.ssl.MakeSSL#convert} in the pinned 42.7.11 sources.
 *
 * <p>There is currently no operator-configurable custom/private CA trust mechanism for production
 * (self-hosted target databases whose certificate does not chain to a JVM-trusted public root are
 * not supported today); see {@link PostgresqlPhysicalJdbcConnector}'s class Javadoc for that
 * documented residual gap.
 */
public final class TrustAnchorSslSocketFactory extends SSLSocketFactory {

    private final SSLSocketFactory delegate;

    /** Invoked by pgjdbc via reflection, mirroring {@link RivoQueryPinnedSocketFactory}. */
    public TrustAnchorSslSocketFactory(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        String encoded = properties.getProperty(PostgresqlPhysicalJdbcConnector.TRUST_ANCHOR_PROPERTY);
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException("no test trust anchor certificate was supplied");
        }
        X509Certificate certificate = PostgresqlPhysicalJdbcConnector.decodeCertificate(encoded);
        try {
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            trustStore.setCertificateEntry("rivoquery-test-ca", certificate);
            TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagerFactory.getTrustManagers(), new SecureRandom());
            this.delegate = context.getSocketFactory();
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("failed to initialize the test TLS trust anchor", e);
        }
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return delegate.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return delegate.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return delegate.createSocket(s, host, port, autoClose);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return delegate.createSocket(host, port);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return delegate.createSocket(host, port, localHost, localPort);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return delegate.createSocket(host, port);
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        return delegate.createSocket(address, port, localAddress, localPort);
    }
}
