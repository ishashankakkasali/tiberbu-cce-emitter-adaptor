package org.openphc.tiberbu.cce.emitter.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InboundRequestTest {

    @Nested
    @DisplayName("header lookup")
    class HeaderLookup {

        @Test
        @DisplayName("is case-insensitive in both directions")
        void findsHeadersRegardlessOfCasing() {
            InboundRequest inboundRequest = InboundRequest.from(
                    "{}", Map.of("X-Facility-Id", "FAC-0001"), "/inbound");

            assertThat(inboundRequest.getHeader("X-Facility-Id")).contains("FAC-0001");
            assertThat(inboundRequest.getHeader("x-facility-id")).contains("FAC-0001");
            assertThat(inboundRequest.getHeader("X-FACILITY-ID")).contains("FAC-0001");
        }

        @Test
        @DisplayName("returns empty for absent, blank and null header names")
        void returnsEmptyWhenHeaderIsUnusable() {
            InboundRequest inboundRequest = InboundRequest.from(
                    "{}", Map.of("X-Correlation-Id", "   "), "/inbound");

            assertThat(inboundRequest.getHeader("X-Source-Event-Id")).isEmpty();
            assertThat(inboundRequest.getHeader("X-Correlation-Id")).isEmpty();
            assertThat(inboundRequest.getHeader(null)).isEmpty();
        }

        @Test
        @DisplayName("exposes the three optional contract headers by name")
        void exposesTheContractHeaders() {
            Map<String, String> requestHeaders = Map.of(
                    "x-facility-id", "FAC-0001",
                    "X-Source-Event-Id", "VCR-20260901-57098420",
                    "X-CORRELATION-ID", "7f3c9b12-4d5e-4a6b-8c7d-9e0f1a2b3c4d");

            InboundRequest inboundRequest = InboundRequest.from("{}", requestHeaders, "/inbound");

            assertThat(inboundRequest.getFacilityIdHeader()).contains("FAC-0001");
            assertThat(inboundRequest.getSourceEventIdHeader()).contains("VCR-20260901-57098420");
            assertThat(inboundRequest.getCorrelationIdHeader())
                    .contains("7f3c9b12-4d5e-4a6b-8c7d-9e0f1a2b3c4d");
        }

        @Test
        @DisplayName("all three contract headers are optional")
        void contractHeadersAreOptional() {
            InboundRequest inboundRequest = InboundRequest.from("{}", Map.of(), "/inbound");

            assertThat(inboundRequest.getFacilityIdHeader()).isEmpty();
            assertThat(inboundRequest.getSourceEventIdHeader()).isEmpty();
            assertThat(inboundRequest.getCorrelationIdHeader()).isEmpty();
        }

        @Test
        @DisplayName("the header map is unmodifiable and detached from the source map")
        void headerMapIsDefensivelyCopied() {
            Map<String, String> mutableHeaders = new HashMap<>();
            mutableHeaders.put("X-Facility-Id", "FAC-0001");

            InboundRequest inboundRequest = InboundRequest.from("{}", mutableHeaders, "/inbound");
            mutableHeaders.put("X-Facility-Id", "TAMPERED");

            assertThat(inboundRequest.getFacilityIdHeader()).contains("FAC-0001");
            assertThatThrownBy(() -> inboundRequest.getHeadersByLowercaseName().put("x", "y"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("containsFhirResource pre-parse guard")
    class FhirResourceGuard {

        @Test
        void acceptsAnEnvelopeCarryingABundle() {
            String bundleEnvelope = """
                    {"meta":{},"resource":{"resourceType":"Bundle","entry":[]}}""";
            assertThat(InboundRequest.from(bundleEnvelope, Map.of(), "/inbound")
                    .containsFhirResource()).isTrue();
        }

        @Test
        void rejectsBodiesThatCannotHoldAFhirResource() {
            assertThat(InboundRequest.from(null, Map.of(), "/inbound").containsFhirResource()).isFalse();
            assertThat(InboundRequest.from("", Map.of(), "/inbound").containsFhirResource()).isFalse();
            assertThat(InboundRequest.from("not json at all", Map.of(), "/inbound")
                    .containsFhirResource()).isFalse();
            assertThat(InboundRequest.from("{\"meta\":{}}", Map.of(), "/inbound")
                    .containsFhirResource()).isFalse();
        }
    }

    @Test
    @DisplayName("a null header map is tolerated")
    void toleratesNullHeaderMap() {
        InboundRequest inboundRequest = InboundRequest.from("{}", null, "/inbound");

        assertThat(inboundRequest.getHeadersByLowercaseName()).isEmpty();
        assertThat(inboundRequest.getFacilityIdHeader()).isEmpty();
        assertThat(inboundRequest.getRequestPath()).isEqualTo("/inbound");
        assertThat(inboundRequest.getRawBody()).isEqualTo("{}");
    }
}
