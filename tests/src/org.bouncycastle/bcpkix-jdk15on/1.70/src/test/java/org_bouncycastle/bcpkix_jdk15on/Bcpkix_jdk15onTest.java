/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org_bouncycastle.bcpkix_jdk15on;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class Bcpkix_jdk15onTest {
    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    @Test
    void testSelfSignedCertificate() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BC");
        kpg.initialize(2048);
        KeyPair keyPair = kpg.generateKeyPair();

        X500Name issuer = new X500Name("CN=Test");
        BigInteger serial = BigInteger.valueOf(1);
        Date notBefore = new Date(System.currentTimeMillis() - 1000L * 60 * 60 * 24);
        Date notAfter = new Date(System.currentTimeMillis() + 1000L * 60 * 60 * 24 * 365);
        X500Name subject = issuer;

        JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(issuer, serial, notBefore, notAfter, subject, keyPair.getPublic());
        JcaContentSignerBuilder signerBuilder = new JcaContentSignerBuilder("SHA256withRSA").setProvider("BC");
        ContentSigner contentSigner = signerBuilder.build(keyPair.getPrivate());

        X509CertificateHolder certHolder = certBuilder.build(contentSigner);
        JcaX509CertificateConverter converter = new JcaX509CertificateConverter().setProvider("BC");
        X509Certificate cert = converter.getCertificate(certHolder);

        assertNotNull(cert);
    }
}
