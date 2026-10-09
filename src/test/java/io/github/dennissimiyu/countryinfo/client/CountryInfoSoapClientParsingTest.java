package io.github.dennissimiyu.countryinfo.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import io.github.dennissimiyu.countryinfo.exception.CountryNotFoundException;
import io.github.dennissimiyu.countryinfo.exception.ExternalServiceException;

/** Verifies parsing against responses shaped per the CountryInfoService WSDL (elementFormDefault=qualified). */
class CountryInfoSoapClientParsingTest {

    private static final String ENV_START = "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>";
    private static final String ENV_END = "</soap:Body></soap:Envelope>";

    @Test
    void parsesCountryIsoCode() {
        String xml = ENV_START + "<m:CountryISOCodeResponse xmlns:m=\"http://www.oorsprong.org/websamples.countryinfo\">"
                + "<m:CountryISOCodeResult>KE</m:CountryISOCodeResult></m:CountryISOCodeResponse>" + ENV_END;
        assertThat(CountryInfoResponseMapper.toIsoCode(CountryInfoResponseMapper.parse(xml), "Kenya")).isEqualTo("KE");
    }

    @Test
    void unknownCountryNameIsNotFound() {
        String xml = ENV_START + "<m:CountryISOCodeResponse xmlns:m=\"http://www.oorsprong.org/websamples.countryinfo\">"
                + "<m:CountryISOCodeResult>No country found by that name</m:CountryISOCodeResult></m:CountryISOCodeResponse>" + ENV_END;
        assertThatThrownBy(() -> CountryInfoResponseMapper.toIsoCode(CountryInfoResponseMapper.parse(xml), "Atlantis"))
                .isInstanceOf(CountryNotFoundException.class);
    }

    @Test
    void parsesFullCountryInfoWithLanguages() {
        String xml = ENV_START + "<m:FullCountryInfoResponse xmlns:m=\"http://www.oorsprong.org/websamples.countryinfo\">"
                + "<m:FullCountryInfoResult>"
                + "<m:sISOCode>TZ</m:sISOCode><m:sName>Tanzania</m:sName><m:sCapitalCity>Dodoma</m:sCapitalCity>"
                + "<m:sPhoneCode>255</m:sPhoneCode><m:sContinentCode>AF</m:sContinentCode>"
                + "<m:sCurrencyISOCode>TZS</m:sCurrencyISOCode><m:sCountryFlag>http://flags/TZ.jpg</m:sCountryFlag>"
                + "<m:Languages><m:tLanguage><m:sISOCode>sw</m:sISOCode><m:sName>Swahili</m:sName></m:tLanguage>"
                + "<m:tLanguage><m:sISOCode>en</m:sISOCode><m:sName>English</m:sName></m:tLanguage></m:Languages>"
                + "</m:FullCountryInfoResult></m:FullCountryInfoResponse>" + ENV_END;

        FullCountryInfo info = CountryInfoResponseMapper.toFullCountryInfo(CountryInfoResponseMapper.parse(xml), "TZ");

        assertThat(info.isoCode()).isEqualTo("TZ");
        assertThat(info.name()).isEqualTo("Tanzania");
        assertThat(info.capitalCity()).isEqualTo("Dodoma");
        assertThat(info.currencyIsoCode()).isEqualTo("TZS");
        assertThat(info.languages()).containsExactly(
                new FullCountryInfo.Lang("sw", "Swahili"), new FullCountryInfo.Lang("en", "English"));
    }

    @Test
    void soapFaultBecomesExternalServiceException() {
        String xml = ENV_START + "<soap:Fault><faultcode>soap:Server</faultcode><faultstring>boom</faultstring></soap:Fault>" + ENV_END;
        assertThatThrownBy(() -> CountryInfoResponseMapper.parse(xml)).isInstanceOf(ExternalServiceException.class);
    }
}
