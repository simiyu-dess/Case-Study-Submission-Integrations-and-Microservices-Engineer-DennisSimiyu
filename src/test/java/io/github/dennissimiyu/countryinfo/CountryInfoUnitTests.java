package io.github.dennissimiyu.countryinfo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.dennissimiyu.countryinfo.client.SimpleCircuitBreaker;
import io.github.dennissimiyu.countryinfo.service.CountryInfoService;

import java.time.Clock;
import java.time.Duration;

class CountryInfoUnitTests {

    @Test
    void convertsToSentenceCase() {
        assertThat(CountryInfoService.toSentenceCase("kenya")).isEqualTo("Kenya");
        assertThat(CountryInfoService.toSentenceCase("  tANZANIA ")).isEqualTo("Tanzania");
    }

    @Test
    void circuitOpensAfterThreshold() {
        SimpleCircuitBreaker cb = new SimpleCircuitBreaker(2, Duration.ofMinutes(1), Clock.systemUTC());
        cb.onFailure();
        assertThat(cb.allowRequest()).isTrue();
        cb.onFailure();
        assertThat(cb.allowRequest()).isFalse();
        assertThat(cb.getState()).isEqualTo(SimpleCircuitBreaker.State.OPEN);
    }
}
