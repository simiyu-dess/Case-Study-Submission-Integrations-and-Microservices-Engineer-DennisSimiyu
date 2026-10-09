package io.github.dennissimiyu.countryinfo.controller;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.dennissimiyu.countryinfo.dto.CountryInfoResponse;
import io.github.dennissimiyu.countryinfo.dto.CountryNameRequest;
import io.github.dennissimiyu.countryinfo.dto.CountryUpdateRequest;
import io.github.dennissimiyu.countryinfo.service.CountryInfoService;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/countries")
public class CountryInfoController {

    private final CountryInfoService service;

    public CountryInfoController(CountryInfoService service) {
        this.service = service;
    }

    /** Body: { "name": "Tanzania" } -> fetches from SOAP service and stores the result. */
    @PostMapping
    public ResponseEntity<CountryInfoResponse> create(@Valid @RequestBody CountryNameRequest request) {
        CountryInfoResponse saved = service.fetchAndSave(request.name());
        return ResponseEntity.created(URI.create("/api/v1/countries/" + saved.id())).body(saved);
    }

    @GetMapping
    public List<CountryInfoResponse> findAll() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public CountryInfoResponse findById(@PathVariable Long id) {
        return service.findById(id);
    }

    @PutMapping("/{id}")
    public CountryInfoResponse update(@PathVariable Long id, @Valid @RequestBody CountryUpdateRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
