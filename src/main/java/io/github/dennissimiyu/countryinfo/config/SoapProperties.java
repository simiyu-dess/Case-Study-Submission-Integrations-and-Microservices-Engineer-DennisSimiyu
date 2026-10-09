package io.github.dennissimiyu.countryinfo.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised configuration for the CountryInfo SOAP integration (timeouts, retries, circuit breaker).
 */
@ConfigurationProperties(prefix = "countryinfo.soap")
public record SoapProperties(
        String endpoint,
        Duration connectTimeout,
        Duration readTimeout,
        int maxAttempts,
        Duration initialBackoff,
        int circuitFailureThreshold,
        Duration circuitOpenDuration) {

    public static final String DEFAULT_ENDPOINT =
            "http://webservices.oorsprong.org/websamples.countryinfo/CountryInfoService.wso";

    public SoapProperties {
        if (endpoint == null || endpoint.isBlank()) endpoint = DEFAULT_ENDPOINT;
        if (connectTimeout == null) connectTimeout = Duration.ofSeconds(3);
        if (readTimeout == null) readTimeout = Duration.ofSeconds(5);
        if (maxAttempts <= 0) maxAttempts = 3;
        if (initialBackoff == null) initialBackoff = Duration.ofMillis(200);
        if (circuitFailureThreshold <= 0) circuitFailureThreshold = 5;
        if (circuitOpenDuration == null) circuitOpenDuration = Duration.ofSeconds(30);
    }
}
