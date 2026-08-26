package com.knowimran.rivoquery.platform.connector.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.cert.X509Certificate;
import javax.security.auth.x500.X500Principal;
import org.junit.jupiter.api.Test;

class TestCertificateAuthorityTest {

    @Test
    void issuesALeafCertificateSignedByItsOwnCaWithTheRequestedSubjectAlternativeName() throws Exception {
        var ca = new TestCertificateAuthority();

        var issued = ca.issueServerCertificate("pinned-db.rivoquery.invalid");

        issued.certificate().verify(ca.caCertificate().getPublicKey());
        assertThat(issued.certificate().getSubjectX500Principal())
                .isEqualTo(new X500Principal("CN=pinned-db.rivoquery.invalid"));
        assertThat(subjectAlternativeNames(issued.certificate())).contains("pinned-db.rivoquery.invalid");
        assertThat(issued.certificatePem()).startsWith("-----BEGIN CERTIFICATE-----");
        assertThat(issued.privateKeyPem()).startsWith("-----BEGIN PRIVATE KEY-----");
    }

    @Test
    void twoLeavesFromTheSameAuthorityShareTheSameTrustAnchor() throws Exception {
        var ca = new TestCertificateAuthority();

        var first = ca.issueServerCertificate("first.rivoquery.invalid");
        var second = ca.issueServerCertificate("second.rivoquery.invalid");

        first.certificate().verify(ca.caCertificate().getPublicKey());
        second.certificate().verify(ca.caCertificate().getPublicKey());
    }

    private static java.util.List<String> subjectAlternativeNames(X509Certificate certificate) throws Exception {
        var names = new java.util.ArrayList<String>();
        if (certificate.getSubjectAlternativeNames() == null) {
            return names;
        }
        for (var entry : certificate.getSubjectAlternativeNames()) {
            names.add(String.valueOf(entry.get(1)));
        }
        return names;
    }
}
