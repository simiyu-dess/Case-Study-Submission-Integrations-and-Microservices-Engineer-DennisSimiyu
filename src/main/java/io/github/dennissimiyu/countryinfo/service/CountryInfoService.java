package io.github.dennissimiyu.countryinfo.service;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import io.github.dennissimiyu.countryinfo.client.CountryInfoSoapClient;
import io.github.dennissimiyu.countryinfo.client.FullCountryInfo;
import io.github.dennissimiyu.countryinfo.dto.CountryInfoResponse;
import io.github.dennissimiyu.countryinfo.dto.CountryUpdateRequest;
import io.github.dennissimiyu.countryinfo.dto.LanguageDto;
import io.github.dennissimiyu.countryinfo.exception.ResourceNotFoundException;
import io.github.dennissimiyu.countryinfo.model.CountryInfo;
import io.github.dennissimiyu.countryinfo.model.Language;
import io.github.dennissimiyu.countryinfo.repository.CountryInfoRepository;

@Service
public class CountryInfoService {

    private static final Logger log = LoggerFactory.getLogger(CountryInfoService.class);

    private final CountryInfoSoapClient soapClient;
    private final CountryInfoRepository repository;
    private final TransactionTemplate tx;

    public CountryInfoService(CountryInfoSoapClient soapClient, CountryInfoRepository repository, TransactionTemplate tx) {
        this.soapClient = soapClient;
        this.repository = repository;
        this.tx = tx;
    }

    public static String toSentenceCase(String input) {
        String s = input.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public CountryInfoResponse fetchAndSave(String rawName) {
        String name = toSentenceCase(rawName);
        log.info("Fetching country info name={}", name);
        String iso = soapClient.getIsoCode(name);
        FullCountryInfo info = soapClient.getFullCountryInfo(iso);

        return tx.execute(status -> {
            CountryInfo entity = repository.findByIsoCode(info.isoCode()).orElseGet(CountryInfo::new);
            boolean created = entity.getId() == null;
            entity.setIsoCode(info.isoCode());
            entity.setName(info.name());
            entity.setCapitalCity(info.capitalCity());
            entity.setPhoneCode(info.phoneCode());
            entity.setContinentCode(info.continentCode());
            entity.setCurrencyIsoCode(info.currencyIsoCode());
            entity.setCountryFlag(info.countryFlag());
            entity.replaceLanguages(info.languages().stream().map(l -> new Language(l.isoCode(), l.name())).toList());
            CountryInfo saved = repository.save(entity);
            log.info("Country info {} id={} iso={}", created ? "created" : "refreshed", saved.getId(), saved.getIsoCode());
            return toResponse(saved);
        });
    }

    @Transactional(readOnly = true)
    public List<CountryInfoResponse> findAll() {
        return repository.findAllByOrderByNameAsc().stream().map(CountryInfoService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public CountryInfoResponse findById(Long id) {
        return toResponse(load(id));
    }

    @Transactional
    public CountryInfoResponse update(Long id, CountryUpdateRequest req) {
        CountryInfo entity = load(id);
        entity.setName(req.name());
        entity.setCapitalCity(req.capitalCity());
        entity.setPhoneCode(req.phoneCode());
        entity.setContinentCode(req.continentCode());
        entity.setCurrencyIsoCode(req.currencyIsoCode());
        entity.setCountryFlag(req.countryFlag());
        if (req.languages() != null) {
            entity.replaceLanguages(req.languages().stream().map(l -> new Language(l.isoCode(), l.name())).toList());
        }
        CountryInfo saved = repository.saveAndFlush(entity);
        log.info("Country info updated id={}", id);
        return toResponse(saved);
    }

    @Transactional
    public void delete(Long id) {
        repository.delete(load(id));
        log.info("Country info deleted id={}", id);
    }

    private CountryInfo load(Long id) {
        return repository.findWithLanguagesById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Country info with id " + id + " not found"));
    }

    static CountryInfoResponse toResponse(CountryInfo c) {
        return new CountryInfoResponse(c.getId(), c.getIsoCode(), c.getName(), c.getCapitalCity(), c.getPhoneCode(),
                c.getContinentCode(), c.getCurrencyIsoCode(), c.getCountryFlag(),
                c.getLanguages().stream().map(l -> new LanguageDto(l.getIsoCode(), l.getName())).toList(),
                c.getCreatedAt(), c.getUpdatedAt());
    }
}
