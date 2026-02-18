/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package xerces.xercesImpl;

import org.apache.xerces.jaxp.DocumentBuilderFactoryImpl;
import org.apache.xerces.jaxp.SAXParserFactoryImpl;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.TypeInfo;
import org.xml.sax.Attributes;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.ext.LexicalHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.SAXParser;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XercesImplTest {

    @Test
    void saxParsesNamespacesAndText() throws Exception {
        String xml = ""
            + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<ns:root xmlns:ns=\"urn:test\" attr=\"value\">\n"
            + "  <ns:child id=\"1\">Hello <ns:inner/> World &amp; Unicode: Žlutý kůň — 𝛑</ns:child>\n"
            + "</ns:root>";

        SAXParserFactoryImpl factory = new SAXParserFactoryImpl();
        factory.setNamespaceAware(true);
        SAXParser parser = factory.newSAXParser();

        CollectingHandler handler = new CollectingHandler();
        parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), handler);

        // Element sequence
        assertThat(handler.startElementQNames)
            .containsExactly("ns:root", "ns:child", "ns:inner");
        assertThat(handler.endElementQNames)
            .containsExactly("ns:inner", "ns:child", "ns:root");

        // Namespace information surfaced via SAX2
        assertThat(handler.startElementUris)
            .containsExactly("urn:test", "urn:test", "urn:test");
        assertThat(handler.startElementLocalNames)
            .containsExactly("root", "child", "inner");

        // Attributes captured for both elements
        assertThat(handler.attributesByElement.get(0))
            .containsEntry("attr", "value");
        assertThat(handler.attributesByElement.get(1))
            .containsEntry("id", "1");

        // Combined character data should include expected text and decoded entities/Unicode
        String allChars = condenseWhitespace(String.join("", handler.characters));
        assertThat(allChars).contains("Hello");
        assertThat(allChars).contains("World & Unicode: Žlutý kůň — 𝛑");

        assertThat(handler.startedDocument).isTrue();
        assertThat(handler.endedDocument).isTrue();
    }

    @Test
    void domBuildsAndPreservesStructure() throws Exception {
        String xml = ""
            + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<ns:root xmlns:ns=\"urn:test\" attr=\"value\">\n"
            + "  <ns:child id=\"1\">Hello <ns:inner/> World &amp; Unicode: Žlutý kůň — 𝛑</ns:child>\n"
            + "</ns:root>";

        DocumentBuilderFactoryImpl dbf = new DocumentBuilderFactoryImpl();
        dbf.setNamespaceAware(true);
        DocumentBuilder builder = dbf.newDocumentBuilder();
        Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        Element root = doc.getDocumentElement();
        assertThat(root.getNamespaceURI()).isEqualTo("urn:test");
        assertThat(root.getLocalName()).isEqualTo("root");
        assertThat(root.getAttribute("attr")).isEqualTo("value");

        Element child = (Element) root.getElementsByTagNameNS("urn:test", "child").item(0);
        assertThat(child).isNotNull();
        assertThat(child.getAttribute("id")).isEqualTo("1");

        // textContent merges character data across element boundaries and decodes entities
        String text = condenseWhitespace(child.getTextContent());
        assertThat(text).contains("Hello");
        assertThat(text).contains("World & Unicode: Žlutý kůň — 𝛑");
    }

    @Test
    void dtdValidationSuccessAndFailure() throws Exception {
        String validXml = ""
            + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<!DOCTYPE note [\n"
            + "  <!ELEMENT note (to,from,heading,body)>\n"
            + "  <!ELEMENT to (#PCDATA)>\n"
            + "  <!ELEMENT from (#PCDATA)>\n"
            + "  <!ELEMENT heading (#PCDATA)>\n"
            + "  <!ELEMENT body (#PCDATA)>\n"
            + "]>\n"
            + "<note>\n"
            + "  <to>Tove</to>\n"
            + "  <from>Jani</from>\n"
            + "  <heading>Reminder</heading>\n"
            + "  <body>Don't forget me this weekend!</body>\n"
            + "</note>";

        String invalidXml = ""
            + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<!DOCTYPE note [\n"
            + "  <!ELEMENT note (to,from,heading,body)>\n"
            + "  <!ELEMENT to (#PCDATA)>\n"
            + "  <!ELEMENT from (#PCDATA)>\n"
            + "  <!ELEMENT heading (#PCDATA)>\n"
            + "  <!ELEMENT body (#PCDATA)>\n"
            + "]>\n"
            + "<note>\n"
            + "  <to>Tove</to>\n"
            + "  <from>Jani</from>\n"
            + "  <heading>Reminder</heading>\n"
            + "  <!-- missing <body> -->\n"
            + "</note>";

        DocumentBuilderFactoryImpl dbf = new DocumentBuilderFactoryImpl();
        dbf.setValidating(true);
        dbf.setNamespaceAware(false);

        // Provide an ErrorHandler that escalates validation issues as exceptions
        ErrorHandler throwingHandler = new ErrorHandler() {
            @Override
            public void warning(SAXParseException exception) throws SAXException {
                throw exception;
            }

            @Override
            public void error(SAXParseException exception) throws SAXException {
                throw exception;
            }

            @Override
            public void fatalError(SAXParseException exception) throws SAXException {
                throw exception;
            }
        };

        DocumentBuilder builder = dbf.newDocumentBuilder();
        builder.setErrorHandler(throwingHandler);

        // Valid document should parse without exception
        builder.parse(new ByteArrayInputStream(validXml.getBytes(StandardCharsets.UTF_8)));

        // Invalid document should trigger a validation error
        assertThatThrownBy(() ->
            builder.parse(new ByteArrayInputStream(invalidXml.getBytes(StandardCharsets.UTF_8)))
        )
            .isInstanceOf(SAXParseException.class)
            .hasMessageContaining("content of element type \"note\"")
            .satisfies(ex -> {
                SAXParseException spe = (SAXParseException) ex;
                assertThat(spe.getLineNumber()).isGreaterThan(0);
                assertThat(spe.getColumnNumber()).isGreaterThan(0);
            });
    }

    @Test
    void xmlSchemaValidationValidAndInvalid() throws Exception {
        String xsd = ""
            + "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"\n"
            + "           targetNamespace=\"urn:ex\"\n"
            + "           xmlns=\"urn:ex\"\n"
            + "           elementFormDefault=\"qualified\">\n"
            + "  <xs:element name=\"person\" type=\"Person\"/>\n"
            + "  <xs:complexType name=\"Person\">\n"
            + "    <xs:sequence>\n"
            + "      <xs:element name=\"name\" type=\"xs:string\"/>\n"
            + "      <xs:element name=\"age\" type=\"xs:int\" minOccurs=\"0\"/>\n"
            + "    </xs:sequence>\n"
            + "    <xs:attribute name=\"id\" type=\"xs:ID\" use=\"required\"/>\n"
            + "  </xs:complexType>\n"
            + "</xs:schema>";

        String valid = ""
            + "<ex:person xmlns:ex=\"urn:ex\" id=\"p1\">\n"
            + "  <ex:name>Alice</ex:name>\n"
            + "  <ex:age>30</ex:age>\n"
            + "</ex:person>";

        String invalidMissingAttr = ""
            + "<ex:person xmlns:ex=\"urn:ex\">\n"
            + "  <ex:name>Bob</ex:name>\n"
            + "</ex:person>";

        // Use Xerces' SchemaFactory via JAXP (implementation supplied by xercesImpl)
        String previous = System.getProperty("javax.xml.validation.SchemaFactory:" + XMLConstants.W3C_XML_SCHEMA_NS_URI);
        System.setProperty("javax.xml.validation.SchemaFactory:" + XMLConstants.W3C_XML_SCHEMA_NS_URI,
            "org.apache.xerces.jaxp.validation.XMLSchemaFactory");
        try {
            SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            Schema schema = sf.newSchema(new StreamSource(new StringReader(xsd)));
            Validator validator = schema.newValidator();

            // Valid instance should pass
            validator.validate(new StreamSource(new StringReader(valid)));

            // Invalid instance should fail due to missing required attribute @id
            assertThatThrownBy(() -> validator.validate(new StreamSource(new StringReader(invalidMissingAttr))))
                .isInstanceOf(SAXException.class)
                .hasMessageContaining("Attribute 'id' must appear on element");
        } finally {
            if (previous == null) {
                System.clearProperty("javax.xml.validation.SchemaFactory:" + XMLConstants.W3C_XML_SCHEMA_NS_URI);
            } else {
                System.setProperty("javax.xml.validation.SchemaFactory:" + XMLConstants.W3C_XML_SCHEMA_NS_URI, previous);
            }
        }
    }

    @Test
    void entityResolutionWithCustomResolver() throws Exception {
        String xml = ""
            + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<!DOCTYPE root [\n"
            + "  <!ENTITY ext SYSTEM \"urn:test:entity\">\n"
            + "]>\n"
            + "<root>&ext;</root>";

        SAXParserFactoryImpl factory = new SAXParserFactoryImpl();
        factory.setNamespaceAware(true);
        SAXParser parser = factory.newSAXParser();

        CollectingHandler handler = new CollectingHandler();
        // Map our external entity to inline content via the handler's EntityResolver
        handler.mapExternalEntity("urn:test:entity", "Expanded via resolver");

        parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), handler);

        String text = condenseWhitespace(String.join("", handler.characters));
        assertThat(text).isEqualTo("Expanded via resolver");
    }

    @Test
    void domSchemaParsingProvidesTypeInfo() throws Exception {
        String xsd = ""
            + "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"\n"
            + "           targetNamespace=\"urn:type\"\n"
            + "           xmlns=\"urn:type\"\n"
            + "           elementFormDefault=\"qualified\">\n"
            + "  <xs:element name=\"person\" type=\"Person\"/>\n"
            + "  <xs:complexType name=\"Person\">\n"
            + "    <xs:sequence>\n"
            + "      <xs:element name=\"name\" type=\"xs:string\"/>\n"
            + "      <xs:element name=\"age\" type=\"xs:int\"/>\n"
            + "    </xs:sequence>\n"
            + "    <xs:attribute name=\"id\" type=\"xs:ID\" use=\"required\"/>\n"
            + "  </xs:complexType>\n"
            + "</xs:schema>";

        String instance = ""
            + "<ex:person xmlns:ex=\"urn:type\" id=\"p1\">\n"
            + "  <ex:name>Alice</ex:name>\n"
            + "  <ex:age>30</ex:age>\n"
            + "</ex:person>";

        // Ensure Xerces' XMLSchemaFactory is used to build the Schema
        String previous = System.getProperty("javax.xml.validation.SchemaFactory:" + XMLConstants.W3C_XML_SCHEMA_NS_URI);
        System.setProperty("javax.xml.validation.SchemaFactory:" + XMLConstants.W3C_XML_SCHEMA_NS_URI,
            "org.apache.xerces.jaxp.validation.XMLSchemaFactory");
        try {
            SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            Schema schema = sf.newSchema(new StreamSource(new StringReader(xsd)));

            DocumentBuilderFactoryImpl dbf = new DocumentBuilderFactoryImpl();
            dbf.setNamespaceAware(true);
            dbf.setSchema(schema);

            DocumentBuilder builder = dbf.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(instance.getBytes(StandardCharsets.UTF_8)));

            Element root = doc.getDocumentElement();
            TypeInfo rootTi = root.getSchemaTypeInfo();
            assertThat(rootTi).isNotNull();
            assertThat(rootTi.getTypeNamespace()).isEqualTo("urn:type");
            assertThat(rootTi.getTypeName()).isEqualTo("Person");

            // Attribute type info (xs:ID)
            assertThat(root.getAttribute("id")).isEqualTo("p1");
            TypeInfo idTi = root.getAttributeNode("id").getSchemaTypeInfo();
            assertThat(idTi).isNotNull();
            assertThat(idTi.getTypeNamespace()).isEqualTo(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            assertThat(idTi.getTypeName()).isEqualTo("ID");

            // Child elements' simple types
            Element nameEl = (Element) root.getElementsByTagNameNS("urn:type", "name").item(0);
            TypeInfo nameTi = nameEl.getSchemaTypeInfo();
            assertThat(nameTi).isNotNull();
            assertThat(nameTi.getTypeNamespace()).isEqualTo(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            assertThat(nameTi.getTypeName()).isEqualTo("string");

            Element ageEl = (Element) root.getElementsByTagNameNS("urn:type", "age").item(0);
            TypeInfo ageTi = ageEl.getSchemaTypeInfo();
            assertThat(ageTi).isNotNull();
            assertThat(ageTi.getTypeNamespace()).isEqualTo(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            assertThat(ageTi.getTypeName()).isEqualTo("int");
        } finally {
            if (previous == null) {
                System.clearProperty("javax.xml.validation.SchemaFactory:" + XMLConstants.W3C_XML_SCHEMA_NS_URI);
            } else {
                System.setProperty("javax.xml.validation.SchemaFactory:" + XMLConstants.W3C_XML_SCHEMA_NS_URI, previous);
            }
        }
    }

    @Test
    void saxLexicalHandlerEmitsCommentsAndCdata() throws Exception {
        String xml = ""
            + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<!DOCTYPE root [\n"
            + "  <!ELEMENT root (#PCDATA)>\n"
            + "]>\n"
            + "<!-- top comment -->\n"
            + "<root>Text <![CDATA[<not markup> & more]]> <!-- inner comment --> end</root>";

        SAXParserFactoryImpl factory = new SAXParserFactoryImpl();
        factory.setNamespaceAware(true);
        SAXParser parser = factory.newSAXParser();

        LexicalCollectingHandler handler = new LexicalCollectingHandler();
        parser.getXMLReader().setProperty("http://xml.org/sax/properties/lexical-handler", handler);
        parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), handler);

        // DTD boundary reported
        assertThat(handler.dtdName).isEqualTo("root");

        // Comments seen in both locations
        String commentsJoined = condenseWhitespace(String.join(" | ", handler.comments));
        assertThat(commentsJoined).contains("top comment");
        assertThat(commentsJoined).contains("inner comment");

        // CDATA callbacks and content captured
        assertThat(handler.sawStartCdata).isTrue();
        assertThat(handler.sawEndCdata).isTrue();
        assertThat(handler.cdataTexts).containsExactly("<not markup> & more");
    }

    // Helpers

    private static final class CollectingHandler extends DefaultHandler {
        boolean startedDocument;
        boolean endedDocument;
        final List<String> startElementQNames = new ArrayList<>();
        final List<String> startElementLocalNames = new ArrayList<>();
        final List<String> startElementUris = new ArrayList<>();
        final List<String> endElementQNames = new ArrayList<>();
        final List<String> characters = new ArrayList<>();
        final List<java.util.Map<String, String>> attributesByElement = new ArrayList<>();
        final java.util.Map<String, String> externalEntities = new java.util.LinkedHashMap<>();

        @Override
        public void startDocument() {
            startedDocument = true;
        }

        @Override
        public void endDocument() {
            endedDocument = true;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            startElementQNames.add(qName);
            startElementLocalNames.add(localName);
            startElementUris.add(uri);

            java.util.Map<String, String> attrs = new java.util.LinkedHashMap<>();
            for (int i = 0; i < attributes.getLength(); i++) {
                attrs.put(attributes.getQName(i), attributes.getValue(i));
            }
            attributesByElement.add(attrs);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            endElementQNames.add(qName);
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (length == 0) {
                return;
            }
            String s = new String(ch, start, length);
            if (!s.isEmpty()) {
                characters.add(s);
            }
        }

        // Allow tests to supply external entity content.
        void mapExternalEntity(String systemId, String content) {
            externalEntities.put(systemId, content);
        }

        @Override
        public InputSource resolveEntity(String publicId, String systemId) {
            String content = externalEntities.get(systemId);
            if (content != null) {
                return new InputSource(new StringReader(content));
            }
            return null; // fall back to default resolution
        }
    }

    private static final class LexicalCollectingHandler extends DefaultHandler implements LexicalHandler {
        final List<String> comments = new ArrayList<>();
        final List<String> cdataTexts = new ArrayList<>();
        String dtdName;
        boolean sawStartCdata;
        boolean sawEndCdata;
        private boolean inCdata;
        private StringBuilder currentCdata;

        @Override
        public void startDTD(String name, String publicId, String systemId) {
            this.dtdName = name;
        }

        @Override
        public void endDTD() {
            // no-op
        }

        @Override
        public void startCDATA() {
            sawStartCdata = true;
            inCdata = true;
            currentCdata = new StringBuilder();
        }

        @Override
        public void endCDATA() {
            sawEndCdata = true;
            inCdata = false;
            if (currentCdata != null) {
                cdataTexts.add(currentCdata.toString());
                currentCdata = null;
            }
        }

        @Override
        public void comment(char[] ch, int start, int length) {
            comments.add(new String(ch, start, length));
        }

        @Override
        public void startEntity(String name) {
            // not used
        }

        @Override
        public void endEntity(String name) {
            // not used
        }

        @Override
        public void startDTD(String name, String publicId, String systemId, String baseURI) {
            // This 4-arg method does not exist in LexicalHandler; keep class binary compatible.
        }

        @Override
        public void endDTD(String name) {
            // This method does not exist in LexicalHandler; keep class binary compatible.
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) {
            // not used
        }

        @Override
        public void endPrefixMapping(String prefix) {
            // not used
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (inCdata && length > 0) {
                if (currentCdata == null) {
                    currentCdata = new StringBuilder();
                }
                currentCdata.append(ch, start, length);
            }
            super.characters(ch, start, length);
        }
    }

    private static String condenseWhitespace(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }
}
