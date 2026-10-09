package io.github.dennissimiyu.countryinfo.client;

import java.util.List;

/** Immutable representation of the SOAP FullCountryInfo response. */
public record FullCountryInfo(
        String isoCode,
        String name,
        String capitalCity,
        String phoneCode,
        String continentCode,
        String currencyIsoCode,
        String countryFlag,
        List<Lang> languages) {

    public record Lang(String isoCode, String name) {
    }
}
