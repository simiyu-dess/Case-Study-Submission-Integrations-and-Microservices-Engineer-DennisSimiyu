package io.github.dennissimiyu.countryinfo.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LanguageDto(@NotBlank @Size(max = 10) String isoCode, @NotBlank @Size(max = 100) String name) {
}
