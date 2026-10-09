package io.github.dennissimiyu.countryinfo.client;

/**
 * Raised when the upstream SOAP service answers with a payload that is not well-formed XML.
 * Treated as a transient failure: such responses are typically produced by intermediaries
 * (proxies, load balancers) during an incident and are therefore worth retrying.
 */
class MalformedSoapResponseException extends RuntimeException {

    MalformedSoapResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
