/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package xerces.xercesImpl;

import org.apache.xerces.jaxp.DocumentBuilderFactoryImpl;
import org.apache.xerces.jaxp.SAXParserFactoryImpl;
import org.apache.xerces.jaxp.validation.XMLSchemaFactory;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.Attributes;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.ext.LexicalHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class XercesImplTest {

    @Test
    void domParsing_withNamespaces_parsesElementsAndNamespacesCorrectly() throws Exception {
        String xml = ""
            + "<book xmlns=\"http://example.com/book\" "
            + "      xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
            + "  <title>Thinking in XML</title>"
            + "  <dc:creator>Jane Doe</dc:creator>"
            + "</book>";

        DocumentBuilderFactoryImpl dbf = new DocumentBuilderFactoryImpl();
        dbf.setNamespaceAware(true);

        DocumentBuilder builder = dbf.newDocumentBuilder();
        Document doc = builder.parse(asInputSource(xml));

        Element root = doc.getDocumentElement();
        assertThat(root).isNotNull();
        assertThat(root.getLocalName()).isEqualTo("book");
        assertThat(root.getNamespaceURI()).isEqualTo("http://example.com/book");
        assertThat(root.lookupNamespaceURI("dc")).isEqualTo("http://purl.org/dc/elements/1.1/");

        Element title = (Element) root.getElementsByTagNameNS("http://example.com/book", "title").item(0);
        assertThat(title).isNotNull();
        assertThat(title.getTextContent()).isEqualTo("Thinking in XML");

        Element creator = (Element) root.getElementsByTagNameNS("http://purl.org/dc/elements/1.1/", "creator").item(0);
        assertThat(creator).isNotNull();
        assertThat(creator.getTextContent()).isEqualTo("Jane Doe");
    }

    @Test
    void domParsing_malformedXml_throwsSAXParseExceptionWithLocation() throws Exception {
        String malformed = "<root><unclosed></root>";

        DocumentBuilderFactoryImpl dbf = new DocumentBuilderFactoryImpl();
        dbf.setNamespaceAware(true);

        DocumentBuilder builder = dbf.newDocumentBuilder();

        ThrowableAssert.ThrowingCallable parse = () -> builder.parse(asInputSource(malformed));
        assertThatThrownBy(parse)
            .isInstanceOf(SAXParseException.class)
            .satisfies(ex -> {
                SAXParseException spe = (SAXParseException) ex;
                assertThat(spe.getLineNumber()).isGreaterThan(0);
                assertThat(spe.getColumnNumber()).isGreaterThan(0);
            });
    }

    @Test
    void dtdValidation_reportsErrorsAndAllowsValidDocuments() throws Exception {
        String validXml = ""
            + "<!DOCTYPE note [\n"
            + "  <!ELEMENT note (to,from,body)>\n"
            + "  <!ELEMENT to (#PCDATA)>\n"
            + "  <!ELEMENT from (#PCDATA)>\n"
            + "  <!ELEMENT body (#PCDATA)>\n"
            + "]>\n"
            + "<note>"
            + "  <to>Alice</to>"
            + "  <from>Bob</from>"
            + "  <body>Hello!</body>"
            + "</note>";

        String invalidXml = ""
            + "<!DOCTYPE note [\n"
            + "  <!ELEMENT note (to,from,body)>\n"
            + "  <!ELEMENT to (#PCDATA)>\n"
            + "  <!ELEMENT from (#PCDATA)>\n"
            + "  <!ELEMENT body (#PCDATA)>\n"
            + "]>\n"
            + "<note>"
            + "  <to>Alice</to>"
            + "  <from>Bob</from>"
            + "  <!-- Missing body element -->"
            + "</note>";

        DocumentBuilderFactoryImpl dbf = new DocumentBuilderFactoryImpl();
        dbf.setValidating(true);
        dbf.setNamespaceAware(false);

        DocumentBuilder builder = dbf.newDocumentBuilder();

        // ErrorHandler that escalates any validation error to an exception
        RecordingErrorHandler handler = new RecordingErrorHandler(true);
        builder.setErrorHandler(handler);

        // Valid document should parse without exceptions and without recorded errors
        Document doc = builder.parse(asInputSource(validXml));
        assertThat(doc.getDocumentElement().getNodeName()).isEqualTo("note");
        assertThat(handler.errors).isEmpty();

        // Invalid document should trigger a SAXParseException
        RecordingErrorHandler handler2 = new RecordingErrorHandler(true);
        builder.setErrorHandler(handler2);
        assertThatThrownBy(() -> builder.parse(asInputSource(invalidXml)))
            .isInstanceOf(SAXParseException.class);
        assertThat(handler2.errors).isNotEmpty();
    }

    @Test
    void xmlSchemaValidation_validatesUsingXercesSchemaFactory() throws Exception {
        String xsd = ""
            + "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" "
            + "           targetNamespace=\"http://example.com/person\" "
            + "           xmlns=\"http://example.com/person\" "
            + "           elementFormDefault=\"qualified\">"
            + "  <xs:element name=\"person\">"
            + "    <xs:complexType>"
            + "      <xs:sequence>"
            + "        <xs:element name=\"name\" type=\"xs:string\"/>"
            + "        <xs:element name=\"age\" type=\"xs:int\" minOccurs=\"0\"/>"
            + "      </xs:sequence>"
            + "      <xs:attribute name=\"id\" type=\"xs:ID\" use=\"required\"/>"
            + "    </xs:complexType>"
            + "  </xs:element>"
            + "</xs:schema>";

        String valid = ""
            + "<person xmlns=\"http://example.com/person\" id=\"p1\">"
            + "  <name>John</name>"
            + "  <age>30</age>"
            + "</person>";

        String invalid = ""
            + "<person xmlns=\"http://example.com/person\">"
            + "  <name>John</name>"
            + "  <age>thirty</age>" // invalid type and missing required @id
            + "</person>";

        XMLSchemaFactory schemaFactory = new XMLSchemaFactory();
        // Secure processing is supported and recommended; enable it explicitly.
        schemaFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);

        Schema schema = schemaFactory.newSchema(new StreamSource(new StringReader(xsd), "memory:xsd"));
        Validator validator = schema.newValidator();

        // Valid XML should pass
        validator.validate(new StreamSource(new StringReader(valid), "memory:valid.xml"));

        // Invalid XML should fail with detailed diagnostics
        assertThatThrownBy(() -> validator.validate(new StreamSource(new StringReader(invalid), "memory:invalid.xml")))
            .isInstanceOf(SAXParseException.class)
            .satisfies(ex -> {
                SAXParseException spe = (SAXParseException) ex;
                assertThat(spe.getLineNumber()).isGreaterThan(0);
                assertThat(spe.getPublicId()).isNull(); // we used in-memory sources
            });
    }

    @Test
    void saxParsing_emitsExpectedEventsInOrder() throws Exception {
        String xml = ""
            + "<root>"
            + "  <child id=\"1\">text</child>"
            + "  <child id=\"2\"/>"
            + "</root>";

        SAXParserFactoryImpl spf = new SAXParserFactoryImpl();
        spf.setNamespaceAware(true);

        SAXParser parser = spf.newSAXParser();

        EventRecordingHandler handler = new EventRecordingHandler();
        parser.parse(asInputSource(xml), handler);

        // Verify start/end element order and captured text
        assertThat(handler.events)
            .containsExactly(
                "startElement:root",
                "startElement:child[@id=1]",
                "text:child[text='text']",
                "endElement:child",
                "startElement:child[@id=2]",
                "endElement:child",
                "endElement:root"
            );
    }

    @Test
    void features_disallowDoctypeDecl_blocksDoctypes() throws Exception {
        String withDoctype = ""
            + "<!DOCTYPE root [<!ELEMENT root (#PCDATA)>]>"
            + "<root>content</root>";

        DocumentBuilderFactoryImpl dbf = new DocumentBuilderFactoryImpl();
        dbf.setNamespaceAware(true);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

        DocumentBuilder builder = dbf.newDocumentBuilder();

        assertThatThrownBy(() -> builder.parse(asInputSource(withDoctype)))
            .isInstanceOf(SAXParseException.class);
    }

    @Test
    void saxParsing_lexicalHandler_emitsCommentsAndCdataBoundaries() throws Exception {
        String xml = ""
            + "<root>"
            + "  <!--a comment-->"
            + "  <c><![CDATA[some <cdata> & text]]></c>"
            + "  <d>t<!--inline comment-->x</d>"
            + "</root>";

        SAXParserFactoryImpl spf = new SAXParserFactoryImpl();
        spf.setNamespaceAware(true);

        SAXParser parser = spf.newSAXParser();
        XMLReader reader = parser.getXMLReader();

        LexicalEventRecordingHandler lexical = new LexicalEventRecordingHandler();
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", lexical);
        reader.setContentHandler(new DefaultHandler()); // we only assert lexical events

        reader.parse(asInputSource(xml));

        assertThat(lexical.events)
            .containsExactly(
                "comment:a comment",
                "startCDATA",
                "endCDATA",
                "comment:inline comment"
            );
    }

    @Test
    void xmlSchema11Validation_validatesUsingXercesSchema11Factory_withAssertions() throws Exception {
        String xsd11 = ""
            + "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" "
            + "           xmlns:vc=\"http://www.w3.org/2007/XMLSchema-versioning\" "
            + "           targetNamespace=\"http://example.com/person11\" "
            + "           xmlns=\"http://example.com/person11\" "
            + "           elementFormDefault=\"qualified\" "
            + "           vc:minVersion=\"1.1\">"
            + "  <xs:element name=\"person\">"
            + "    <xs:complexType>"
            + "      <xs:sequence>"
            + "        <xs:element name=\"age\" type=\"xs:int\"/>"
            + "      </xs:sequence>"
            + "      <xs:attribute name=\"status\" type=\"xs:string\" use=\"required\"/>"
            + "      <!-- XSD 1.1 assertion ensuring consistency between age and status -->"
            + "      <xs:assert test=\"(@status = 'adult' and age >= 18) or (@status = 'minor' and age lt 18)\"/>"
            + "    </xs:complexType>"
            + "  </xs:element>"
            + "</xs:schema>";

        String valid = ""
            + "<person xmlns=\"http://example.com/person11\" status=\"adult\">"
            + "  <age>21</age>"
            + "</person>";

        String invalid = ""
            + "<person xmlns=\"http://example.com/person11\" status=\"adult\">"
            + "  <age>15</age>"
            + "</person>";

        // Try to obtain the XSD 1.1 SchemaFactory via JAXP. If not available, skip the test.
        SchemaFactory schema11Factory;
        try {
            schema11Factory = SchemaFactory.newInstance("http://www.w3.org/XML/XMLSchema/v1.1");
        } catch (IllegalArgumentException e) {
            assumeTrue(false, "XSD 1.1 SchemaFactory is not available in this environment");
            return; // keep compiler happy
        }
        if (schema11Factory == null) {
            assumeTrue(false, "XSD 1.1 SchemaFactory is not available in this environment");
            return;
        }

        schema11Factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);

        Schema schema = schema11Factory.newSchema(new StreamSource(new StringReader(xsd11), "memory:xsd11"));
        Validator validator = schema.newValidator();

        // Valid XML should pass under XSD 1.1 assertion
        validator.validate(new StreamSource(new StringReader(valid), "memory:valid11.xml"));

        // Invalid XML should fail due to assertion violation
        assertThatThrownBy(() -> validator.validate(new StreamSource(new StringReader(invalid), "memory:invalid11.xml")))
            .isInstanceOf(SAXParseException.class)
            .satisfies(ex -> {
                SAXParseException spe = (SAXParseException) ex;
                assertThat(spe.getLineNumber()).isGreaterThan(0);
                assertThat(spe.getPublicId()).isNull();
            });
    }

    private static InputSource asInputSource(String xml) {
        InputSource is = new InputSource(new StringReader(xml));
        is.setSystemId("memory:xml"); // helps provide a base systemId for diagnostics
        return is;
    }

    private static final class RecordingErrorHandler implements ErrorHandler {
        private final boolean throwOnError;
        private final List<SAXParseException> warnings = new ArrayList<>();
        private final List<SAXParseException> errors = new ArrayList<>();
        private final List<SAXParseException> fatals = new ArrayList<>();

        private RecordingErrorHandler(boolean throwOnError) {
            this.throwOnError = throwOnError;
        }

        @Override
        public void warning(SAXParseException exception) throws SAXException {
            warnings.add(exception);
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            errors.add(exception);
            if (throwOnError) {
                throw exception;
            }
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            fatals.add(exception);
            throw exception;
        }
    }

    private static final class EventRecordingHandler extends DefaultHandler {
        private final List<String> events = new ArrayList<>();
        private String currentElement;
        private StringBuilder text = new StringBuilder();
        private String currentChildId;

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            flushText(); // flush any pending text for previous element
            currentElement = localName != null && !localName.isEmpty() ? localName : qName;
            String id = attributes.getValue("id");
            currentChildId = id;
            if (id != null) {
                events.add("startElement:" + currentElement + "[@id=" + id + "]");
            } else {
                events.add("startElement:" + currentElement);
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            text.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String name = localName != null && !localName.isEmpty() ? localName : qName;
            String content = text.toString().trim();
            if (!content.isEmpty()) {
                events.add("text:" + name + "[text='" + content + "']");
            }
            events.add("endElement:" + name);
            text.setLength(0);
            currentElement = null;
            currentChildId = null;
        }

        private void flushText() {
            if (currentElement != null) {
                String content = text.toString().trim();
                if (!content.isEmpty()) {
                    events.add("text:" + currentElement + "[text='" + content + "']");
                }
                text.setLength(0);
            }
        }
    }

    private static final class LexicalEventRecordingHandler implements LexicalHandler {
        private final List<String> events = new ArrayList<>();

        @Override
        public void startDTD(String name, String publicId, String systemId) {
            // not used in this test
        }

        @Override
        public void endDTD() {
            // not used in this test
        }

        @Override
        public void startEntity(String name) {
            // not used in this test
        }

        @Override
        public void endEntity(String name) {
            // not used in this test
        }

        @Override
        public void startCDATA() {
            events.add("startCDATA");
        }

        @Override
        public void endCDATA() {
            events.add("endCDATA");
        }

        @Override
        public void comment(char[] ch, int start, int length) {
            events.add("comment:" + new String(ch, start, length));
        }
    }
}
