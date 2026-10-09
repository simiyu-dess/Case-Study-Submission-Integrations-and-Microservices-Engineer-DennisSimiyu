package io.github.dennissimiyu.countryinfo.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CountryNameRequest(@NotBlank(message = "must not be blank") @Size(max = 100) String name) {
}
