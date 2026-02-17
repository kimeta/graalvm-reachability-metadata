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
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.SAXParser;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.Validator;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XercesImplTest {

    @Test
    void domParsing_withNamespaces_buildsCorrectTree() throws Exception {
        String xml = "<root xmlns=\"urn:ex\" xmlns:ns=\"urn:other\">"
            + "<child id=\"1\">text</child>"
            + "<ns:item ns:attr=\"v\"/>"
            + "</root>";

        DocumentBuilderFactoryImpl dbf = new DocumentBuilderFactoryImpl();
        dbf.setNamespaceAware(true);
        dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);

        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(input(xml));

        Element root = doc.getDocumentElement();
        assertThat(root.getLocalName()).isEqualTo("root");
        assertThat(root.getNamespaceURI()).isEqualTo("urn:ex");

        NodeList children = root.getElementsByTagNameNS("urn:ex", "child");
        assertThat(children.getLength()).isEqualTo(1);
        Element child = (Element) children.item(0);
        assertThat(child.getAttribute("id")).isEqualTo("1");
        assertThat(child.getTextContent()).isEqualTo("text");

        NodeList items = root.getElementsByTagNameNS("urn:other", "item");
        assertThat(items.getLength()).isEqualTo(1);
        Element item = (Element) items.item(0);
        assertThat(item.getAttributeNS("urn:other", "attr")).isEqualTo("v");
    }

    @Test
    void domParsing_malformedXml_throwsParseException() throws Exception {
        String malformed = "<root><unclosed></root>";

        DocumentBuilderFactoryImpl dbf = new DocumentBuilderFactoryImpl();
        dbf.setNamespaceAware(true);

        DocumentBuilder db = dbf.newDocumentBuilder();
        assertThatThrownBy(() -> db.parse(input(malformed)))
            .isInstanceOf(SAXException.class);
    }

    @Test
    void xmlSchema_validation_passesAndFailsAsExpected() throws Exception {
        String xsd =
            "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" "
                + "targetNamespace=\"urn:p\" xmlns=\"urn:p\" elementFormDefault=\"qualified\">"
                + "  <xs:element name=\"person\">"
                + "    <xs:complexType>"
                + "      <xs:sequence>"
                + "        <xs:element name=\"name\" type=\"xs:string\"/>"
                + "        <xs:element name=\"age\" type=\"xs:int\"/>"
                + "      </xs:sequence>"
                + "      <xs:attribute name=\"id\" type=\"xs:ID\" use=\"required\"/>"
                + "    </xs:complexType>"
                + "  </xs:element>"
                + "</xs:schema>";

        String valid =
            "<person xmlns=\"urn:p\" id=\"p1\">"
                + "<name>Alice</name>"
                + "<age>42</age>"
                + "</person>";

        String invalidMissingAttr =
            "<person xmlns=\"urn:p\">"
                + "<name>Alice</name>"
                + "<age>42</age>"
                + "</person>";

        String invalidType =
            "<person xmlns=\"urn:p\" id=\"p2\">"
                + "<name>Bob</name>"
                + "<age>notANumber</age>"
                + "</person>";

        XMLSchemaFactory schemaFactory = new XMLSchemaFactory();
        Schema schema = schemaFactory.newSchema(new StreamSource(new StringReader(xsd)));

        Validator validator = schema.newValidator();

        // Valid instance should pass
        validator.validate(new StreamSource(new StringReader(valid)));

        // Missing required attribute should fail
        assertThatThrownBy(() -> validator.validate(new StreamSource(new StringReader(invalidMissingAttr))))
            .isInstanceOf(SAXException.class);

        // Wrong type should fail
        assertThatThrownBy(() -> validator.validate(new StreamSource(new StringReader(invalidType))))
            .isInstanceOf(SAXException.class);
    }

    @Test
    void saxParsing_emitsExpectedEventsWithNamespaces() throws Exception {
        String xml = "<r xmlns=\"urn:ex\"><a>hi</a><b attr=\"v\"/></r>";

        SAXParserFactoryImpl spf = new SAXParserFactoryImpl();
        spf.setNamespaceAware(true);

        SAXParser parser = spf.newSAXParser();

        List<String> events = new ArrayList<>();
        DefaultHandler handler = new DefaultHandler() {
            @Override
            public void startElement(String uri, String localName, String qName, org.xml.sax.Attributes attributes) {
                events.add("start:" + "{" + uri + "}" + localName);
                if (attributes != null && attributes.getLength() > 0) {
                    for (int i = 0; i < attributes.getLength(); i++) {
                        events.add("attr:" + "{" + attributes.getURI(i) + "}" + attributes.getLocalName(i) + "=" + attributes.getValue(i));
                    }
                }
            }

            @Override
            public void characters(char[] ch, int start, int length) {
                String s = new String(ch, start, length);
                if (!s.trim().isEmpty()) {
                    events.add("text:" + s.trim());
                }
            }

            @Override
            public void endElement(String uri, String localName, String qName) {
                events.add("end:" + "{" + uri + "}" + localName);
            }
        };

        parser.parse(input(xml), handler);

        assertThat(events).containsExactly(
            "start:{urn:ex}r",
            "start:{urn:ex}a",
            "text:hi",
            "end:{urn:ex}a",
            "start:{urn:ex}b",
            // Unprefixed attribute is in no namespace
            "attr:{}attr=v",
            "end:{urn:ex}b",
            "end:{urn:ex}r"
        );
    }

    @Test
    void dtdValidation_withInternalSubset_passesAndFails() throws Exception {
        String dtdOk =
            "<!DOCTYPE items ["
                + "<!ELEMENT items (item+)>"
                + "<!ELEMENT item (#PCDATA)>"
                + "]>"
                + "<items><item>one</item><item>two</item></items>";

        String dtdBad =
            "<!DOCTYPE items ["
                + "<!ELEMENT items (item+)>"
                + "<!ELEMENT item (#PCDATA)>"
                + "]>"
                + "<items><wrong>oops</wrong></items>";

        DocumentBuilderFactoryImpl dbf = new DocumentBuilderFactoryImpl();
        dbf.setValidating(true);
        dbf.setNamespaceAware(false);

        DocumentBuilder db = dbf.newDocumentBuilder();
        // Convert validation errors into exceptions so invalid XML fails the parse
        db.setErrorHandler(new ErrorHandler() {
            @Override
            public void warning(SAXParseException exception) {
                // ignore warnings
            }

            @Override
            public void error(SAXParseException exception) throws SAXException {
                throw exception;
            }

            @Override
            public void fatalError(SAXParseException exception) throws SAXException {
                throw exception;
            }
        });

        // Valid according to DTD
        db.parse(input(dtdOk));

        // Invalid according to DTD should raise an error
        assertThatThrownBy(() -> db.parse(input(dtdBad)))
            .isInstanceOf(SAXException.class);
    }

    @Test
    void entityResolver_suppliesExternalDtd_defaultAttributeIsApplied() throws Exception {
        String xml = "<!DOCTYPE note SYSTEM \"note.dtd\"><note/>";

        DocumentBuilderFactoryImpl dbf = new DocumentBuilderFactoryImpl();
        dbf.setValidating(true);

        DocumentBuilder db = dbf.newDocumentBuilder();
        db.setEntityResolver((publicId, systemId) -> {
            String dtd = "<!ELEMENT note EMPTY>"
                + "<!ATTLIST note category CDATA \"general\">";
            InputSource src = new InputSource(new StringReader(dtd));
            src.setPublicId(publicId);
            src.setSystemId(systemId);
            return src;
        });

        Document doc = db.parse(input(xml));
        Element root = doc.getDocumentElement();
        assertThat(root.getTagName()).isEqualTo("note");
        assertThat(root.getAttribute("category")).isEqualTo("general");
    }

    @Test
    void disallowDoctypeDecl_feature_blocksDoctype() throws Exception {
        String withDoctype =
            "<!DOCTYPE x [<!ELEMENT x EMPTY>]>"
                + "<x/>";

        DocumentBuilderFactoryImpl dbf = new DocumentBuilderFactoryImpl();
        dbf.setNamespaceAware(true);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

        DocumentBuilder db = dbf.newDocumentBuilder();
        assertThatThrownBy(() -> db.parse(input(withDoctype)))
            .isInstanceOf(SAXException.class);
    }

    private static InputSource input(String xml) {
        return new InputSource(new StringReader(xml));
    }
}
