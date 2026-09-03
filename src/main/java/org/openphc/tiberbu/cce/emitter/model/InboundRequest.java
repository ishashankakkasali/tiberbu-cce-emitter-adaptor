package org.openphc.tiberbu.cce.emitter.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Normalized view of an inbound {@code POST /inbound} call.
 *
 * <p>Holds the raw request body untouched — parsing happens downstream, so a
 * body that is not JSON at all still reaches the pipeline and is answered with
 * {@code 200 ignored} rather than a transport-level error.
 *
 * <p>Header names are stored lowercased so lookups are case-insensitive, which
 * HTTP requires and which no caller should have to think about.
 */
public final class InboundRequest {

    /** Facility FOSA ID supplied by the source system. Optional. */
    public static final String HEADER_FACILITY_ID = "x-facility-id";

    /** The source system's own event identifier, used to derive a stable CloudEvents id. Optional. */
    public static final String HEADER_SOURCE_EVENT_ID = "x-source-event-id";

    /** Trace correlation ID. Optional — a UUID is generated when it is absent. */
    public static final String HEADER_CORRELATION_ID = "x-correlation-id";

    private final String rawBody;
    private final Map<String, String> headersByLowercaseName;
    private final String requestPath;

    private InboundRequest(String rawBody, Map<String, String> headersByLowercaseName, String requestPath) {
        this.rawBody = rawBody;
        this.headersByLowercaseName = headersByLowercaseName;
        this.requestPath = requestPath;
    }

    /**
     * @param rawBody       the request body exactly as received, may be {@code null} or empty
     * @param requestHeaders headers in any casing, may be {@code null}
     * @param requestPath   the request URI path, used as a metric tag
     * @return a normalized request with lowercased, unmodifiable headers
     */
    public static InboundRequest from(String rawBody, Map<String, String> requestHeaders, String requestPath) {
        Map<String, String> lowercasedHeaders = new LinkedHashMap<>();
        if (requestHeaders != null) {
            requestHeaders.forEach((headerName, headerValue) -> {
                if (headerName != null) {
                    lowercasedHeaders.put(headerName.toLowerCase(java.util.Locale.ROOT), headerValue);
                }
            });
        }
        return new InboundRequest(rawBody, Collections.unmodifiableMap(lowercasedHeaders), requestPath);
    }

    /**
     * @param headerName header name in any casing
     * @return the header value, or empty when absent or blank
     */
    public Optional<String> getHeader(String headerName) {
        if (headerName == null) {
            return Optional.empty();
        }
        String headerValue = headersByLowercaseName.get(headerName.toLowerCase(java.util.Locale.ROOT));
        return (headerValue == null || headerValue.isBlank()) ? Optional.empty() : Optional.of(headerValue);
    }

    /** @return the {@code X-Facility-Id} header, if the source system sent one */
    public Optional<String> getFacilityIdHeader() {
        return getHeader(HEADER_FACILITY_ID);
    }

    /** @return the {@code X-Source-Event-Id} header, if the source system sent one */
    public Optional<String> getSourceEventIdHeader() {
        return getHeader(HEADER_SOURCE_EVENT_ID);
    }

    /** @return the {@code X-Correlation-Id} header, if the source system sent one */
    public Optional<String> getCorrelationIdHeader() {
        return getHeader(HEADER_CORRELATION_ID);
    }

    /**
     * Cheap pre-parse guard. A body with no {@code "resourceType"} anywhere in it
     * cannot contain a FHIR Bundle, so the pipeline can answer {@code 200 ignored}
     * without paying for a JSON or HAPI parse.
     *
     * @return {@code true} when the body could plausibly hold a FHIR resource
     */
    public boolean containsFhirResource() {
        return rawBody != null && rawBody.contains("\"resourceType\"");
    }

    /** @return the request body exactly as received */
    public String getRawBody() {
        return rawBody;
    }

    /** @return an unmodifiable map of headers, keyed by lowercased name */
    public Map<String, String> getHeadersByLowercaseName() {
        return headersByLowercaseName;
    }

    /** @return the request URI path */
    public String getRequestPath() {
        return requestPath;
    }
}
