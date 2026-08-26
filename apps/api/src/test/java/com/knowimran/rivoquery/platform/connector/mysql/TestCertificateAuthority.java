package com.knowimran.rivoquery.platform.connector.mysql;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A throwaway, JDK-{@code keytool}-driven certificate authority for TLS tests: issues a
 * self-signed root CA once, then leaf server certificates (signed by that CA) for whatever
 * hostname a test needs, entirely via {@code keytool} subprocess calls plus in-process {@link
 * KeyStore} reads - no external OpenSSL/Bouncy Castle dependency.
 *
 * <p>Deliberately duplicated from {@code platform.connector.postgresql.TestCertificateAuthority}
 * (package-private there, and P2-120's own instructions forbid touching anything under {@code
 * platform/connector/postgresql/**}) rather than shared - this is pure test infrastructure, not a
 * production abstraction, so duplication is the correct trade-off here.
 */
final class TestCertificateAuthority {

    private static final char[] STORE_PASSWORD = "rivoquery-test-only".toCharArray();

    private final Path workDir;
    private final Path caKeystore;
    private final Path caCertificatePem;
    private final X509Certificate caCertificate;
    private final AtomicInteger sequence = new AtomicInteger();

    TestCertificateAuthority() {
        try {
            this.workDir = Files.createTempDirectory("rivoquery-mysql-tls-ca");
            this.caKeystore = workDir.resolve("ca.p12");
            runKeytool(
                    "-genkeypair",
                    "-alias", "ca",
                    "-keyalg", "RSA",
                    "-keysize", "2048",
                    "-sigalg", "SHA256withRSA",
                    "-validity", "3",
                    "-keystore", caKeystore.toString(),
                    "-storetype", "PKCS12",
                    "-storepass", new String(STORE_PASSWORD),
                    "-dname", "CN=RivoQuery MySQL Test Root CA",
                    "-ext", "bc:c=ca:true",
                    "-ext", "ku:c=keyCertSign,cRLSign");
            this.caCertificatePem = workDir.resolve("ca.pem");
            runKeytool(
                    "-exportcert",
                    "-alias", "ca",
                    "-keystore", caKeystore.toString(),
                    "-storepass", new String(STORE_PASSWORD),
                    "-rfc",
                    "-file", caCertificatePem.toString());
            this.caCertificate = readCertificate(caKeystore, "ca");
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("failed to bootstrap the test certificate authority", e);
        }
    }

    X509Certificate caCertificate() {
        return caCertificate;
    }

    String caCertificatePem() {
        try {
            return pem("CERTIFICATE", caCertificate.getEncoded());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("failed to encode the test CA certificate", e);
        }
    }

    /** Issues a fresh leaf certificate, signed by this CA, whose only SAN is the DNS name {@code hostname}. */
    IssuedServerCertificate issueServerCertificate(String hostname) {
        return issueServerCertificate(hostname, "san=dns:" + hostname);
    }

    /**
     * Issues a fresh leaf certificate, signed by this CA, whose only SAN is the IP address {@code
     * ipLiteral} (an {@code iPAddress} SAN, per RFC 6125 - not a DNS SAN) - for ADR-0013's "when the
     * configured host is itself an IP literal, verification requires a matching IP subject
     * alternative name" requirement.
     */
    IssuedServerCertificate issueIpLiteralServerCertificate(String ipLiteral) {
        return issueServerCertificate(ipLiteral, "san=ip:" + ipLiteral);
    }

    private IssuedServerCertificate issueServerCertificate(String commonName, String sanExtension) {
        try {
            int id = sequence.incrementAndGet();
            Path keystore = workDir.resolve("server-" + id + ".p12");
            Path csr = workDir.resolve("server-" + id + ".csr");
            Path signed = workDir.resolve("server-" + id + ".pem");

            runKeytool(
                    "-genkeypair",
                    "-alias", "server",
                    "-keyalg", "RSA",
                    "-keysize", "2048",
                    "-sigalg", "SHA256withRSA",
                    "-validity", "3",
                    "-keystore", keystore.toString(),
                    "-storetype", "PKCS12",
                    "-storepass", new String(STORE_PASSWORD),
                    "-dname", "CN=" + commonName,
                    "-ext", sanExtension);
            runKeytool(
                    "-certreq",
                    "-alias", "server",
                    "-keystore", keystore.toString(),
                    "-storepass", new String(STORE_PASSWORD),
                    "-file", csr.toString(),
                    "-ext", sanExtension);
            runKeytool(
                    "-gencert",
                    "-alias", "ca",
                    "-keystore", caKeystore.toString(),
                    "-storepass", new String(STORE_PASSWORD),
                    "-infile", csr.toString(),
                    "-outfile", signed.toString(),
                    "-rfc",
                    "-validity", "3",
                    "-ext", sanExtension);
            runKeytool(
                    "-importcert",
                    "-alias", "ca",
                    "-keystore", keystore.toString(),
                    "-storepass", new String(STORE_PASSWORD),
                    "-file", caCertificatePem.toString(),
                    "-noprompt");
            runKeytool(
                    "-importcert",
                    "-alias", "server",
                    "-keystore", keystore.toString(),
                    "-storepass", new String(STORE_PASSWORD),
                    "-file", signed.toString());

            X509Certificate certificate = readCertificate(keystore, "server");
            PrivateKey privateKey = readPrivateKey(keystore, "server");
            return new IssuedServerCertificate(certificate, privateKey);
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("failed to issue a test server certificate for " + commonName, e);
        }
    }

    record IssuedServerCertificate(X509Certificate certificate, PrivateKey privateKey) {
        String certificatePem() {
            try {
                return pem("CERTIFICATE", certificate.getEncoded());
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("failed to encode the test server certificate", e);
            }
        }

        String privateKeyPem() {
            return pem("PRIVATE KEY", privateKey.getEncoded());
        }
    }

    private static String pem(String label, byte[] der) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + base64 + "\n-----END " + label + "-----\n";
    }

    private static void runKeytool(String... args) throws IOException {
        String keytoolPath = System.getProperty("java.home") + File.separator + "bin" + File.separator + "keytool";
        List<String> command = new ArrayList<>(args.length + 1);
        command.add(keytoolPath);
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try (var in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("keytool invocation was interrupted", e);
        }
        if (exitCode != 0) {
            throw new IOException("keytool failed (exit " + exitCode + "): " + output);
        }
    }

    private static X509Certificate readCertificate(Path keystore, String alias)
            throws GeneralSecurityException, IOException {
        return (X509Certificate) load(keystore).getCertificate(alias);
    }

    private static PrivateKey readPrivateKey(Path keystore, String alias) throws GeneralSecurityException, IOException {
        return (PrivateKey) load(keystore).getKey(alias, STORE_PASSWORD);
    }

    private static KeyStore load(Path keystore) throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(keystore)) {
            keyStore.load(in, STORE_PASSWORD);
        }
        return keyStore;
    }
}
