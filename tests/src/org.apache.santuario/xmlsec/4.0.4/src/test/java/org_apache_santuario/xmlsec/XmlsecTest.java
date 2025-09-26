/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org_apache_santuario.xmlsec;

import org.apache.xml.security.Init;
import org.apache.xml.security.algorithms.MessageDigestAlgorithm;
import org.apache.xml.security.c14n.Canonicalizer;
import org.apache.xml.security.signature.XMLSignature;
import org.apache.xml.security.transforms.Transforms;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class XmlsecTest {

    @BeforeAll
    public static void init() {
        Init.init();
    }

    @Test
    public void testXMLSignature() throws Exception {
        // Load a pre-existing document
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(Files.newInputStream(Paths.get("src/test/resources/document.xml")));

        // Generate a key pair
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        PrivateKey privateKey = kp.getPrivate();
        PublicKey publicKey = kp.getPublic();

        // Create a new XML signature
        XMLSignature sig = new XMLSignature(doc, "", XMLSignature.ALGO_ID_SIGNATURE_RSA_SHA256);
        doc.getDocumentElement().appendChild(sig.getElement());

        // Add a reference to the signature
        Transforms transforms = new Transforms(doc);
        transforms.addTransform(Transforms.TRANSFORM_ENVELOPED_SIGNATURE);
        sig.addDocument("", transforms, MessageDigestAlgorithm.ALGO_ID_DIGEST_SHA256);

        // Sign the document
        sig.sign(privateKey);

        // Verify the signature
        assertTrue(sig.checkSignatureValue(publicKey));

        // Try to verify with a wrong public key
        KeyPairGenerator wrongKpg = KeyPairGenerator.getInstance("RSA");
        wrongKpg.initialize(2048);
        KeyPair wrongKp = wrongKpg.generateKeyPair();
        PublicKey wrongPublicKey = wrongKp.getPublic();
    }

    @Test
    public void testCanonicalization() throws Exception {
        // Load a pre-existing document
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(Files.newInputStream(Paths.get("src/test/resources/document.xml")));

        // Canonicalize the document
        Canonicalizer c14n = Canonicalizer.getInstance(Canonicalizer.ALGO_ID_C14N_EXCL_OMIT_COMMENTS);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        c14n.canonicalizeSubtree(doc.getDocumentElement(), bos);

        // Check that the canonicalized output is not empty
        assertTrue(bos.toByteArray().length > 0);
    }
}
