package io.github.dennissimiyu.countryinfo.client;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import io.github.dennissimiyu.countryinfo.exception.CountryNotFoundException;
import io.github.dennissimiyu.countryinfo.exception.ExternalServiceException;

/**
 * Parses and maps CountryInfoService SOAP responses into domain-friendly values.
 *
 * <p>Isolated from the transport concerns of {@link CountryInfoSoapClient} so that the (tricky)
 * XML handling can be unit tested against recorded payloads without any network access.
 */
final class CountryInfoResponseMapper {

    private static final String NS = SoapEnvelopes.NAMESPACE;
    private static final String DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl";

    /**
     * Sentinels the upstream service returns instead of a SOAP fault when a lookup misses.
     * Matched case-insensitively on a {@code contains} basis because the wording varies per operation.
     */
    private static final List<String> NOT_FOUND_SENTINELS = List.of(
            "no country found",
            "country not found");

    private CountryInfoResponseMapper() {
    }

    /**
     * Parses a SOAP envelope, applying hardened XML settings (no DOCTYPE, secure processing)
     * to protect against XXE and entity expansion attacks.
     *
     * @throws ExternalServiceException if the envelope carries a SOAP fault
     * @throws MalformedSoapResponseException if the payload is not well-formed XML
     */
    static Document parse(String xml) {
        Document document = toDocument(xml);
        failOnSoapFault(document);
        return document;
    }

    /** Extracts {@code CountryISOCodeResult}, treating upstream "not found" sentinels as a 404. */
    static String toIsoCode(Document document, String countryName) {
        return textOf(document.getDocumentElement(), "CountryISOCodeResult")
                .filter(iso -> !isNotFoundSentinel(iso))
                .orElseThrow(() -> new CountryNotFoundException(
                        "No country found with name '" + countryName + "'"));
    }

    /** Maps {@code FullCountryInfoResult} (including the nested language list) onto {@link FullCountryInfo}. */
    static FullCountryInfo toFullCountryInfo(Document document, String isoCode) {
        Element result = firstElement(document.getDocumentElement(), "FullCountryInfoResult")
                .orElseThrow(() -> notFound(isoCode));
        String name = textOf(result, "sName")
                .filter(value -> !isNotFoundSentinel(value))
                .orElseThrow(() -> notFound(isoCode));

        return new FullCountryInfo(
                textOf(result, "sISOCode").orElse(isoCode),
                name,
                textOf(result, "sCapitalCity").orElse(null),
                textOf(result, "sPhoneCode").orElse(null),
                textOf(result, "sContinentCode").orElse(null),
                textOf(result, "sCurrencyISOCode").orElse(null),
                textOf(result, "sCountryFlag").orElse(null),
                toLanguages(result));
    }

    private static List<FullCountryInfo.Lang> toLanguages(Element result) {
        NodeList nodes = result.getElementsByTagNameNS(NS, "tLanguage");
        List<FullCountryInfo.Lang> languages = new ArrayList<>(nodes.getLength());
        for (int i = 0; i < nodes.getLength(); i++) {
            Element language = (Element) nodes.item(i);
            languages.add(new FullCountryInfo.Lang(
                    textOf(language, "sISOCode").orElse(null),
                    textOf(language, "sName").orElse(null)));
        }
        return List.copyOf(languages);
    }

    private static Document toDocument(String xml) {
        try {
            return documentBuilderFactory()
                    .newDocumentBuilder()
                    .parse(new InputSource(new StringReader(xml == null ? "" : xml)));
        } catch (Exception e) {
            throw new MalformedSoapResponseException("Invalid SOAP response: " + e.getMessage(), e);
        }
    }

    private static DocumentBuilderFactory documentBuilderFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        factory.setFeature(DISALLOW_DOCTYPE, true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        return factory;
    }

    private static void failOnSoapFault(Document document) {
        NodeList faults = document.getElementsByTagNameNS("*", "Fault");
        if (faults.getLength() > 0) {
            throw new ExternalServiceException("SOAP fault: " + faults.item(0).getTextContent().trim());
        }
    }

    /**
     * Reads the text of a direct child element. Restricting the lookup to direct children matters
     * because {@code sISOCode}/{@code sName} also appear inside the nested {@code Languages} list.
     */
    private static Optional<String> textOf(Element parent, String localName) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE && matches(child, localName)) {
                return Optional.of(child.getTextContent().trim()).filter(value -> !value.isBlank());
            }
        }
        // The operation result element is a child of soap:Body, so fall back to a namespaced lookup.
        return firstElement(parent, localName)
                .map(element -> element.getTextContent().trim())
                .filter(value -> !value.isBlank());
    }

    private static Optional<Element> firstElement(Element parent, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS(NS, localName);
        return nodes.getLength() == 0 ? Optional.empty() : Optional.of((Element) nodes.item(0));
    }

    private static boolean matches(Node node, String localName) {
        return NS.equals(node.getNamespaceURI()) && localName.equals(node.getLocalName());
    }

    private static boolean isNotFoundSentinel(String value) {
        String normalised = value.toLowerCase(Locale.ROOT);
        return NOT_FOUND_SENTINELS.stream().anyMatch(normalised::contains);
    }

    private static CountryNotFoundException notFound(String isoCode) {
        return new CountryNotFoundException("No country info found for ISO code '" + isoCode + "'");
    }
}
