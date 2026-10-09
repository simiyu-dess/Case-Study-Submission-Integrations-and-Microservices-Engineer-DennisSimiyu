package io.github.dennissimiyu.countryinfo.client;

import org.springframework.web.util.HtmlUtils;

/**
 * Builds the SOAP 1.1 request envelopes for the CountryInfoService operations.
 *
 * <p>The payloads are small and fully known at compile time, so hand-crafting them keeps the
 * integration dependency-free (no JAXB/WSDL code generation) while remaining easy to read.
 * All caller supplied values are XML-escaped to prevent injection into the envelope.
 */
final class SoapEnvelopes {

    /** Target namespace declared by the CountryInfoService WSDL. */
    static final String NAMESPACE = "http://www.oorsprong.org/websamples.countryinfo";

    private static final String ENVELOPE_TEMPLATE = """
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">\
            <soap:Body>%s</soap:Body>\
            </soap:Envelope>""";

    private SoapEnvelopes() {
    }

    /** {@code CountryISOCode} request: resolves a country name to its ISO 3166-1 alpha-2 code. */
    static String countryIsoCode(String countryName) {
        return envelope("""
                <web:CountryISOCode xmlns:web="%s"><web:sCountryName>%s</web:sCountryName></web:CountryISOCode>"""
                .formatted(NAMESPACE, escape(countryName)));
    }

    /** {@code FullCountryInfo} request: fetches the complete country record for an ISO code. */
    static String fullCountryInfo(String isoCode) {
        return envelope("""
                <web:FullCountryInfo xmlns:web="%s"><web:sCountryISOCode>%s</web:sCountryISOCode></web:FullCountryInfo>"""
                .formatted(NAMESPACE, escape(isoCode)));
    }

    private static String envelope(String body) {
        return ENVELOPE_TEMPLATE.formatted(body);
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }
}
