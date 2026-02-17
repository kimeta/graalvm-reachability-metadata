/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package xerces.xercesImpl;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.Attributes;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XercesImplTest {

    private static String originalSaxFactoryProperty;
    private static String originalDomFactoryProperty;
    private static String originalSchemaFactoryProperty;

    @BeforeAll
    static void configureXercesJaxpProviders() {
        originalSaxFactoryProperty = System.getProperty("javax.xml.parsers.SAXParserFactory");
        originalDomFactoryProperty = System.getProperty("javax.xml.parsers.DocumentBuilderFactory");
        originalSchemaFactoryProperty = System.getProperty("javax.xml.validation.SchemaFactory:" + XMLConstants.W3C_XML_SCHEMA_NS_URI);

        System.setProperty("javax.xml.parsers.SAXParserFactory", "org.apache.xerces.jaxp.SAXParserFactoryImpl");
        System.setProperty("javax.xml.parsers.DocumentBuilderFactory", "org.apache.xerces.jaxp.DocumentBuilderFactoryImpl");
        System.setProperty("javax.xml.validation.SchemaFactory:" + XMLConstants.W3C_XML_SCHEMA_NS_URI,
                "org.apache.xerces.jaxp.validation.XMLSchemaFactory");
    }

    @AfterAll
    static void restoreJaxpProviders() {
        restoreOrClear("javax.xml.parsers.SAXParserFactory", originalSaxFactoryProperty);
        restoreOrClear("javax.xml.parsers.DocumentBuilderFactory", originalDomFactoryProperty);
        restoreOrClear("javax.xml.validation.SchemaFactory:" + XMLConstants.W3C_XML_SCHEMA_NS_URI, originalSchemaFactoryProperty);
    }

    private static void restoreOrClear(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @Test
    void dom_parsesDocumentWithNamespaces() throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();

        // Ensure Xerces is the active provider
        assertThat(dbf.getClass().getName()).contains("org.apache.xerces");

        String xml = ""
                + "<root xmlns='urn:ns' xmlns:ns2='urn:ns2'>"
                + "  <child ns2:attr='v'>text</child>"
                + "</root>";

        Document doc = db.parse(new InputSource(new StringReader(xml)));
        Element root = doc.getDocumentElement();

        assertThat(root.getNamespaceURI()).isEqualTo("urn:ns");
        assertThat(root.getLocalName()).isEqualTo("root");

        Element child = (Element) root.getElementsByTagNameNS("urn:ns", "child").item(0);
        assertThat(child).isNotNull();
        assertThat(child.getTextContent().trim()).isEqualTo("text");
        assertThat(child.getAttributeNS("urn:ns2", "attr")).isEqualTo("v");
    }

    @Test
    void sax_parsesNamespacesAndAttributes() throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setValidating(false);

        SAXParser saxParser = factory.newSAXParser();
        XMLReader reader = saxParser.getXMLReader();

        // Ensure Xerces is the active provider
        assertThat(reader.getClass().getName()).contains("org.apache.xerces");

        List<String> events = new ArrayList<>();
        reader.setContentHandler(new DefaultHandler() {
            @Override
            public void startPrefixMapping(String prefix, String uri) {
                events.add("startPrefixMapping(" + (prefix == null ? "" : prefix) + " -> " + uri + ")");
            }

            @Override
            public void startElement(String uri, String localName, String qName, Attributes attributes) {
                StringBuilder b = new StringBuilder();
                b.append("startElement{uri=").append(uri)
                        .append(", local=").append(localName)
                        .append(", q=").append(qName)
                        .append(", attrs=[");
                for (int i = 0; i < attributes.getLength(); i++) {
                    String aUri = attributes.getURI(i);
                    String aLocal = attributes.getLocalName(i);
                    String value = attributes.getValue(i);
                    b.append("{").append(aUri).append("}").append(aLocal).append("=").append(value);
                    if (i < attributes.getLength() - 1) {
                        b.append(", ");
                    }
                }
                b.append("]}");
                events.add(b.toString());
            }

            @Override
            public void characters(char[] ch, int start, int length) {
                String text = new String(ch, start, length);
                if (!text.trim().isEmpty()) {
                    events.add("text(" + text.trim() + ")");
                }
            }

            @Override
            public void endElement(String uri, String localName, String qName) {
                events.add("endElement{uri=" + uri + ", local=" + localName + ", q=" + qName + "}");
            }
        });

        String xml = ""
                + "<root xmlns='urn:ns' xmlns:ns2='urn:ns2'>"
                + "  <child ns2:attr='v'>text</child>"
                + "</root>";

        reader.parse(new InputSource(new StringReader(xml)));

        // Structural and attribute namespace checks
        assertThat(events).anySatisfy(e ->
                assertThat(e).startsWith("startElement{uri=urn:ns, local=root, q=root"));
        assertThat(events).anySatisfy(e ->
                assertThat(e).startsWith("startElement{uri=urn:ns, local=child, q=child"));
        assertThat(events).anySatisfy(e ->
                assertThat(e).contains("{urn:ns2}attr=v"));
        assertThat(events).contains("text(text)");
        assertThat(events).anySatisfy(e ->
                assertThat(e).isEqualTo("endElement{uri=urn:ns, local=child, q=child}"));
        assertThat(events).anySatisfy(e ->
                assertThat(e).isEqualTo("endElement{uri=urn:ns, local=root, q=root}"));
    }

    @Test
    void xsd_validation_reportsErrorsForInvalidContent() throws Exception {
        // Schema: root -> child (xs:int)
        String xsd = ""
                + "<xs:schema xmlns:xs='http://www.w3.org/2001/XMLSchema'>"
                + "  <xs:element name='root'>"
                + "    <xs:complexType>"
                + "      <xs:sequence>"
                + "        <xs:element name='child' type='xs:int'/>"
                + "      </xs:sequence>"
                + "    </xs:complexType>"
                + "  </xs:element>"
                + "</xs:schema>";

        SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        // Ensure Xerces is the active provider
        assertThat(sf.getClass().getName()).contains("org.apache.xerces");

        Schema schema = sf.newSchema(new StreamSource(new StringReader(xsd), "memory:xsd"));

        String validXml = "<root><child>123</child></root>";
        String invalidXml = "<root><child>abc</child></root>";

        // Valid input should pass
        schema.newValidator()
              .validate(new StreamSource(new StringReader(validXml), "memory:valid.xml"));

        // Invalid input should throw a SAXParseException
        assertThatThrownBy(() ->
                schema.newValidator().validate(new StreamSource(new StringReader(invalidXml), "memory:invalid.xml"))
        ).isInstanceOf(SAXParseException.class);
    }

    @Test
    void dtd_validation_detectsContentModelViolations() throws Exception {
        // DTD enforces: root -> child
        String valid = ""
                + "<!DOCTYPE root ["
                + "  <!ELEMENT root (child)>"
                + "  <!ELEMENT child (#PCDATA)>"
                + "]>"
                + "<root><child>ok</child></root>";

        String invalid = ""
                + "<!DOCTYPE root ["
                + "  <!ELEMENT root (child)>"
                + "  <!ELEMENT child (#PCDATA)>"
                + "]>"
                + "<root/>";

        SAXParserFactory f = SAXParserFactory.newInstance();
        f.setNamespaceAware(false);
        f.setValidating(true);

        // Ensure Xerces is the active provider
        assertThat(f.getClass().getName()).contains("org.apache.xerces");

        // Valid should parse without exception
        SAXParser parser = f.newSAXParser();
        XMLReader reader = parser.getXMLReader();
        reader.setErrorHandler(new SilentErrorHandler());
        reader.parse(new InputSource(new StringReader(valid)));

        // Invalid should throw a SAXParseException due to missing required element
        SAXParser parser2 = f.newSAXParser();
        XMLReader reader2 = parser2.getXMLReader();
        reader2.setErrorHandler(new SilentErrorHandler());
        assertThatThrownBy(() -> reader2.parse(new InputSource(new StringReader(invalid))))
                .isInstanceOf(SAXParseException.class);
    }

    @Test
    void entityResolver_isInvokedForExternalSubset() throws Exception {
        String xml = "<!DOCTYPE root SYSTEM 'http://example.com/test.dtd'><root/>";

        SAXParserFactory f = SAXParserFactory.newInstance();
        f.setValidating(true);
        f.setNamespaceAware(false);

        SAXParser parser = f.newSAXParser();
        XMLReader reader = parser.getXMLReader();

        AtomicBoolean resolved = new AtomicBoolean(false);
        reader.setEntityResolver((publicId, systemId) -> {
            if ("http://example.com/test.dtd".equals(systemId)) {
                resolved.set(true);
                String dtd = "<!ELEMENT root EMPTY>";
                InputSource is = new InputSource(new StringReader(dtd));
                is.setSystemId(systemId);
                return is;
            }
            return null;
        });
        reader.setErrorHandler(new SilentErrorHandler());

        reader.parse(new InputSource(new StringReader(xml)));

        assertThat(resolved).isTrue();
    }

    private static final class SilentErrorHandler implements ErrorHandler {
        @Override
        public void warning(SAXParseException exception) {
            // ignore warnings for these tests
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            // rethrow to make failures visible in negative tests
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            // rethrow to make failures visible in negative tests
            throw exception;
        }
    }
}
