package io.github.dennissimiyu.countryinfo.repository;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import io.github.dennissimiyu.countryinfo.model.CountryInfo;

public interface CountryInfoRepository extends JpaRepository<CountryInfo, Long> {

    @EntityGraph(attributePaths = "languages")
    Optional<CountryInfo> findByIsoCode(String isoCode);

    @EntityGraph(attributePaths = "languages")
    List<CountryInfo> findAllByOrderByNameAsc();

    @EntityGraph(attributePaths = "languages")
    Optional<CountryInfo> findWithLanguagesById(Long id);
}
