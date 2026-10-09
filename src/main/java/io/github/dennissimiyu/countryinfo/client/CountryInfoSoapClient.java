package io.github.dennissimiyu.countryinfo.client;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.w3c.dom.Document;

import io.github.dennissimiyu.countryinfo.config.SoapProperties;
import io.github.dennissimiyu.countryinfo.exception.ExternalServiceException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Gateway to the oorsprong {@code CountryInfoService} SOAP API.
 *
 * <p>Responsibilities are deliberately narrow: build a request, execute it over HTTP and apply the
 * resilience policy (timeouts, bounded retries with exponential backoff, circuit breaker, caching).
 * Envelope construction lives in {@link SoapEnvelopes} and response mapping in
 * {@link CountryInfoResponseMapper}.
 *
 * <p>Failure translation contract:
 * <ul>
 *   <li>unknown country &rarr; {@code CountryNotFoundException} (HTTP 404)</li>
 *   <li>4xx from upstream &rarr; {@link ExternalServiceException}, not retried (request is at fault)</li>
 *   <li>timeouts, 5xx, malformed payloads &rarr; retried, then {@link ExternalServiceException}</li>
 * </ul>
 */
@Component
public class CountryInfoSoapClient {

    private static final Logger log = LoggerFactory.getLogger(CountryInfoSoapClient.class);

    private static final String OP_COUNTRY_ISO_CODE = "CountryISOCode";
    private static final String OP_FULL_COUNTRY_INFO = "FullCountryInfo";
    private static final MediaType TEXT_XML_UTF8 = new MediaType("text", "xml", StandardCharsets.UTF_8);

    private static final String METRIC_REQUESTS = "soap.client.requests";
    private static final String METRIC_REJECTED = "soap.client.rejected";
    private static final String TAG_OPERATION = "operation";
    private static final String TAG_OUTCOME = "outcome";

    private final RestClient restClient;
    private final SoapProperties properties;
    private final SimpleCircuitBreaker circuitBreaker;
    private final MeterRegistry meterRegistry;

    public CountryInfoSoapClient(RestClient soapRestClient, SoapProperties properties, MeterRegistry meterRegistry) {
        this.restClient = soapRestClient;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.circuitBreaker = new SimpleCircuitBreaker(
                properties.circuitFailureThreshold(), properties.circuitOpenDuration(), Clock.systemUTC());
    }

    /**
     * Resolves a country name to its ISO 3166-1 alpha-2 code.
     *
     * @throws io.github.dennissimiyu.countryinfo.exception.CountryNotFoundException if unknown upstream
     */
    @Cacheable(cacheNames = "isoCodes", key = "#countryName")
    public String getIsoCode(String countryName) {
        Assert.hasText(countryName, "countryName must not be blank");
        String isoCode = execute(OP_COUNTRY_ISO_CODE,
                SoapEnvelopes.countryIsoCode(countryName),
                document -> CountryInfoResponseMapper.toIsoCode(document, countryName));
        log.info("Resolved ISO code country='{}' iso='{}'", countryName, isoCode);
        return isoCode;
    }

    /**
     * Fetches the full country record for an ISO 3166-1 alpha-2 code.
     *
     * @throws io.github.dennissimiyu.countryinfo.exception.CountryNotFoundException if unknown upstream
     */
    @Cacheable(cacheNames = "fullCountryInfo", key = "#isoCode")
    public FullCountryInfo getFullCountryInfo(String isoCode) {
        Assert.hasText(isoCode, "isoCode must not be blank");
        FullCountryInfo info = execute(OP_FULL_COUNTRY_INFO,
                SoapEnvelopes.fullCountryInfo(isoCode),
                document -> CountryInfoResponseMapper.toFullCountryInfo(document, isoCode));
        log.info("Fetched country info iso='{}' name='{}' languages={}",
                info.isoCode(), info.name(), info.languages().size());
        return info;
    }

    /**
     * Executes a SOAP operation guarded by the circuit breaker and retried with exponential backoff.
     * Mapping runs inside the attempt so that a malformed body is treated as a retryable failure.
     */
    private <T> T execute(String operation, String envelope, Function<Document, T> mapper) {
        guardCircuit(operation);

        Duration backoff = properties.initialBackoff();
        RuntimeException lastFailure = null;

        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            try {
                T result = attempt(operation, envelope, mapper);
                circuitBreaker.onSuccess();
                return result;
            } catch (HttpClientErrorException e) {
                circuitBreaker.onSuccess();
                log.error("SOAP call rejected operation={} status={} body={}",
                        operation, e.getStatusCode(), e.getResponseBodyAsString());
                throw new ExternalServiceException(
                        "Country information service rejected the request: " + e.getStatusCode(), e);
            } catch (RestClientException | MalformedSoapResponseException e) {
                lastFailure = e;
                log.warn("SOAP call failed operation={} attempt={}/{} error={}",
                        operation, attempt, properties.maxAttempts(), e.toString());
                if (attempt < properties.maxAttempts()) {
                    sleep(backoff);
                    backoff = backoff.multipliedBy(2);
                }
            }
        }

        circuitBreaker.onFailure();
        log.error("SOAP call exhausted {} attempts operation={} circuitState={}",
                properties.maxAttempts(), operation, circuitBreaker.getState());
        throw new ExternalServiceException(
                "Country information service is unavailable, please retry later", lastFailure);
    }

    private <T> T attempt(String operation, String envelope, Function<Document, T> mapper) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            log.debug("SOAP request operation={} payload={}", operation, envelope);
            String response = restClient.post()
                    .contentType(TEXT_XML_UTF8)
                    .header("SOAPAction", "\"\"")
                    .body(envelope)
                    .retrieve()
                    .body(String.class);
            log.debug("SOAP response operation={} payload={}", operation, response);

            T result = mapper.apply(CountryInfoResponseMapper.parse(response));
            outcome = "success";
            return result;
        } finally {
            sample.stop(meterRegistry.timer(METRIC_REQUESTS, TAG_OPERATION, operation, TAG_OUTCOME, outcome));
        }
    }

    private void guardCircuit(String operation) {
        if (circuitBreaker.allowRequest()) {
            return;
        }
        meterRegistry.counter(METRIC_REJECTED, TAG_OPERATION, operation).increment();
        log.warn("Circuit breaker OPEN, rejecting SOAP call operation={}", operation);
        throw new ExternalServiceException(
                "Country information service is temporarily unavailable, please retry later");
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExternalServiceException("Interrupted while retrying SOAP call", e);
        }
    }
}
