package io.github.dennissimiyu.countryinfo.dto;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Full replacement payload for PUT /api/v1/countries/{id}. */
public record CountryUpdateRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 100) String capitalCity,
        @Size(max = 10) String phoneCode,
        @Size(max = 10) String continentCode,
        @Size(max = 10) String currencyIsoCode,
        @Size(max = 255) String countryFlag,
        List<@Valid LanguageDto> languages) {
}
