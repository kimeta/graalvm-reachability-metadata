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
import org.apache.xml.security.encryption.XMLCipher;
import org.apache.xml.security.signature.ObjectContainer;
import org.apache.xml.security.signature.XMLSignature;
import org.apache.xml.security.transforms.Transforms;
import org.apache.xml.security.utils.Constants;
import org.apache.xml.security.utils.EncryptionConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayOutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XmlsecTest {

    @BeforeAll
    static void setup() {
        // Init is idempotent; it loads algorithm mappings and resources.
        Init.init();
        // Call it again to ensure idempotency doesn't throw.
        Init.init();
    }

    @Test
    void signAndVerifyEnvelopedSignature() throws Exception {
        Document doc = newDocument();
        Element root = doc.getDocumentElement();

        // Generate RSA keypair for signing
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        // Create an enveloped signature over the entire document
        XMLSignature signature = new XMLSignature(doc, "", XMLSignature.ALGO_ID_SIGNATURE_RSA_SHA256);
        root.appendChild(signature.getElement());

        Transforms transforms = new Transforms(doc);
        transforms.addTransform(Transforms.TRANSFORM_ENVELOPED_SIGNATURE);
        transforms.addTransform(Transforms.TRANSFORM_C14N_EXCL_OMIT_COMMENTS);

        signature.addDocument("", transforms, MessageDigestAlgorithm.ALGO_ID_DIGEST_SHA256);
        signature.addKeyInfo(kp.getPublic());
        signature.sign(kp.getPrivate());

        // Verify
        boolean valid = signature.checkSignatureValue(kp.getPublic());
        assertThat(valid).isTrue();

        // Ensure Signature element exists
        NodeList sigNodes = doc.getElementsByTagNameNS(Constants.SignatureSpecNS, "Signature");
        assertThat(sigNodes.getLength()).isGreaterThan(0);
    }

    @Test
    void signAndVerifySameDocumentObjectReference() throws Exception {
        Document doc = newDocument();
        Element root = doc.getDocumentElement();

        // Generate RSA keypair for signing
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        // Create signature element
        XMLSignature signature = new XMLSignature(doc, "", XMLSignature.ALGO_ID_SIGNATURE_RSA_SHA256);
        root.appendChild(signature.getElement());

        // Create an Object with Id and reference it via same-document URI
        ObjectContainer object = new ObjectContainer(doc);
        object.setId("object1");

        Element payload = doc.createElement("payload");
        payload.setTextContent("Hello Same-Document Reference");
        object.appendChild(payload);

        signature.appendObject(object);

        Transforms transforms = new Transforms(doc);
        transforms.addTransform(Transforms.TRANSFORM_C14N_EXCL_OMIT_COMMENTS);

        signature.addDocument("#object1", transforms, MessageDigestAlgorithm.ALGO_ID_DIGEST_SHA256);
        signature.addKeyInfo(kp.getPublic());
        signature.sign(kp.getPrivate());

        // Verify
        boolean valid = signature.checkSignatureValue(kp.getPublic());
        assertThat(valid).isTrue();
    }

    @Test
    void canonicalizerInstanceAndCanonicalize() throws Exception {
        Document doc = newDocument();

        // Acquire Canonicalizer via factory (internally uses reflection)
        Canonicalizer canonicalizer = Canonicalizer.getInstance(Canonicalizer.ALGO_ID_C14N_EXCL_OMIT_COMMENTS);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        canonicalizer.canonicalizeSubtree(doc, baos);
        byte[] canon = baos.toByteArray();

        assertThat(canon).isNotNull();
        assertThat(canon.length).isGreaterThan(0);
    }

    @Test
    void xmlEncryptionAesRoundTrip() throws Exception {
        Document doc = newDocument();

        // Generate AES key
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(128);
        SecretKey secretKey = kg.generateKey();

        // Encrypt the <data> element
        Element data = (Element) doc.getElementsByTagName("data").item(0);
        XMLCipher encCipher = XMLCipher.getInstance(XMLCipher.AES_128);
        encCipher.init(XMLCipher.ENCRYPT_MODE, secretKey);
        encCipher.doFinal(doc, data, true);

        // Ensure EncryptedData exists
        NodeList encrypted = doc.getElementsByTagNameNS(EncryptionConstants.EncryptionSpecNS, EncryptionConstants._TAG_ENCRYPTEDDATA);
        assertThat(encrypted.getLength()).isEqualTo(1);

        // Decrypt
        XMLCipher decCipher = XMLCipher.getInstance();
        decCipher.init(XMLCipher.DECRYPT_MODE, secretKey);
        decCipher.doFinal(doc, (Element) encrypted.item(0));

        // Assert round-trip
        Element decryptedData = (Element) doc.getElementsByTagName("data").item(0);
        assertThat(decryptedData).isNotNull();
        assertThat(decryptedData.getTextContent()).isEqualTo("Hello World");
    }

    @Test
    void xmlEncryptionDecryptWithWrongKeyFails() throws Exception {
        Document doc = newDocument();

        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(128);
        SecretKey rightKey = kg.generateKey();
        SecretKey wrongKey = kg.generateKey();

        // Encrypt the <data> element with rightKey
        Element data = (Element) doc.getElementsByTagName("data").item(0);
        XMLCipher encCipher = XMLCipher.getInstance(XMLCipher.AES_128);
        encCipher.init(XMLCipher.ENCRYPT_MODE, rightKey);
        encCipher.doFinal(doc, data, true);

        // Attempt to decrypt with wrong key and capture the exception
        NodeList encrypted = doc.getElementsByTagNameNS(EncryptionConstants.EncryptionSpecNS, EncryptionConstants._TAG_ENCRYPTEDDATA);
        Element encryptedData = (Element) encrypted.item(0);

        assertThatThrownBy(() -> {
            XMLCipher decCipher = XMLCipher.getInstance();
            decCipher.init(XMLCipher.DECRYPT_MODE, wrongKey);
            decCipher.doFinal(doc, encryptedData);
        }).isInstanceOf(org.apache.xml.security.encryption.XMLEncryptionException.class)
          .hasCauseInstanceOf(javax.crypto.BadPaddingException.class);
    }

    // Utilities

    private static Document newDocument() throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        Document doc = dbf.newDocumentBuilder().newDocument();
        Element root = doc.createElement("root");
        Element data = doc.createElement("data");
        data.setTextContent("Hello World");
        root.appendChild(data);
        doc.appendChild(root);
        return doc;
    }
}
