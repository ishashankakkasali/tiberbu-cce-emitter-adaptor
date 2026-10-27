package org.openphc.tiberbu.cce.emitter.cloudevents;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openphc.tiberbu.cce.emitter.exception.FhirMappingException;
import org.openphc.tiberbu.cce.emitter.model.CloudEventDto;
import org.openphc.tiberbu.cce.emitter.model.SourceMetadata;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Covers every CloudEvents attribute's mapping per api-reference.md § 3.1,
 * that {@code type} is the FHIR resourceType verbatim, and the event id
 * regression: two entries of one bundle never share an id, but the same
 * entry replayed does.
 */
class CloudEventEnvelopeBuilderTest {

    /**
     * The redactor as production runs it (enabled, default rules): envelope building and adaptor
     * routing must behave the same with redaction on.
     */
    private static org.openphc.tiberbu.cce.emitter.redaction.ClinicalDataRedactor productionRedactor() {
        return new org.openphc.tiberbu.cce.emitter.redaction.ClinicalDataRedactor(
                new org.openphc.tiberbu.cce.emitter.redaction.ClinicalDataRedactionProperties(true, null),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    private static final String CONSENT_JSON = """
            {
              "resourceType": "Consent", "id": "VCR-20260901-57098420", "status": "active",
              "patient": {"reference": "Patient/KE-SHRP-170CDF0A-1363-4972-B36A"}
            }""";

    private static final OffsetDateTime FIXED_EVENT_TIME =
            OffsetDateTime.of(2026, 9, 1, 11, 55, 42, 118_000_000, ZoneOffset.UTC);

    private final CloudEventEnvelopeBuilder cloudEventEnvelopeBuilder =
            new CloudEventEnvelopeBuilder(new EventIdGenerator(), new ObjectMapper(), productionRedactor());

    private SourceMetadata sourceMetadata() {
        return sourceMetadata("7f3c9b12-4d5e-4a6b-8c7d-9e0f1a2b3c4d");
    }

    private SourceMetadata sourceMetadata(String correlationId) {
        return new SourceMetadata("tiberbu", "FAC-0001", "Kamiriithu Health Centre",
                correlationId, FIXED_EVENT_TIME, "/inbound");
    }

    @Nested
    @DisplayName("attribute mapping")
    class AttributeMapping {

        @Test
        @DisplayName("every attribute is mapped from its documented source")
        void everyAttributeIsMappedFromItsDocumentedSource() {
            CloudEventDto cloudEvent = cloudEventEnvelopeBuilder.build(
                    CONSENT_JSON, "KE-SHRP-170CDF0A-1363-4972-B36A", "Consent",
                    sourceMetadata());

            assertThat(cloudEvent.specversion()).isEqualTo("1.0");
            assertThat(cloudEvent.source()).isEqualTo("tiberbu");
            assertThat(cloudEvent.type()).isEqualTo("Consent");
            assertThat(cloudEvent.subject()).isEqualTo("KE-SHRP-170CDF0A-1363-4972-B36A");
            assertThat(cloudEvent.time()).isEqualTo("2026-09-01T11:55:42.118Z");
            assertThat(cloudEvent.datacontenttype()).isEqualTo("application/fhir+json");
            assertThat(cloudEvent.facilityid()).isEqualTo("FAC-0001");
            assertThat(cloudEvent.facilityname()).isEqualTo("Kamiriithu Health Centre");
            assertThat(cloudEvent.correlationid()).isEqualTo("7f3c9b12-4d5e-4a6b-8c7d-9e0f1a2b3c4d");
            assertThat(cloudEvent.sourceeventid()).isNull();
            assertThat(cloudEvent.id()).isNotBlank();
        }

        @Test
        @DisplayName("data holds the entry's FHIR resource, verbatim when it carries nothing to redact (this Consent)")
        void dataHoldsTheEntryResourceVerbatim() throws Exception {
            CloudEventDto cloudEvent = cloudEventEnvelopeBuilder.build(
                    CONSENT_JSON, "KE-SHRP-170CDF0A-1363-4972-B36A", "Consent",
                    sourceMetadata());

            JSONAssert.assertEquals(CONSENT_JSON, cloudEvent.data().toString(), JSONCompareMode.STRICT);
        }

        @Test
        @DisplayName("type is the FHIR resourceType verbatim, no transformation")
        void typeIsResourceTypeVerbatim() {
            CloudEventDto observationEvent = cloudEventEnvelopeBuilder.build(
                    """
                    {"resourceType": "Observation", "id": "obs-001", "status": "final"}""",
                    "KE-SHRP-170CDF0A-1363-4972-B36A", "Observation", sourceMetadata());

            assertThat(observationEvent.type()).isEqualTo("Observation");
        }

        @Test
        @DisplayName("source always comes from metadata.sourceIdentifier(), never from the payload")
        void sourceNeverComesFromThePayload() {
            CloudEventDto cloudEvent = cloudEventEnvelopeBuilder.build(
                    CONSENT_JSON, "KE-SHRP-170CDF0A-1363-4972-B36A", "Consent",
                    sourceMetadata());

            // CONSENT_JSON carries no "source" field at all — this asserts the value is
            // exactly the configured source identifier, not merely "not absent".
            assertThat(cloudEvent.source()).isEqualTo("tiberbu");
        }
    }

    @Nested
    @DisplayName("JSON serialization")
    class JsonSerialization {

        @Test
        @DisplayName("sourceeventid is entirely absent from the serialized JSON, not present as null")
        void sourceEventIdIsAbsentFromSerializedJson() throws Exception {
            CloudEventDto cloudEvent = cloudEventEnvelopeBuilder.build(
                    CONSENT_JSON, "KE-SHRP-170CDF0A-1363-4972-B36A", "Consent",
                    sourceMetadata());

            String serialized = new ObjectMapper().writeValueAsString(cloudEvent);

            assertThat(serialized).doesNotContain("sourceeventid");
        }

        @Test
        @DisplayName("facilityname is entirely absent from the serialized JSON when null, not present as null")
        void facilityNameIsAbsentFromSerializedJsonWhenNull() throws Exception {
            SourceMetadata metadataWithNoFacilityName = new SourceMetadata(
                    "tiberbu", "FAC-0001", null, "7f3c9b12-4d5e-4a6b-8c7d-9e0f1a2b3c4d",
                    FIXED_EVENT_TIME, "/inbound");
            CloudEventDto cloudEvent = cloudEventEnvelopeBuilder.build(
                    CONSENT_JSON, "KE-SHRP-170CDF0A-1363-4972-B36A", "Consent", metadataWithNoFacilityName);

            String serialized = new ObjectMapper().writeValueAsString(cloudEvent);

            assertThat(serialized).doesNotContain("facilityname");
        }

        @Test
        @DisplayName("every extension attribute name is lowercase")
        void extensionAttributeNamesAreLowercase() throws Exception {
            CloudEventDto cloudEvent = cloudEventEnvelopeBuilder.build(
                    CONSENT_JSON, "KE-SHRP-170CDF0A-1363-4972-B36A", "Consent",
                    sourceMetadata());

            String serialized = new ObjectMapper().writeValueAsString(cloudEvent);

            assertThat(serialized).contains(
                    "\"specversion\"", "\"datacontenttype\"", "\"facilityid\"", "\"facilityname\"", "\"correlationid\"");
        }
    }

    @Nested
    @DisplayName("event id regression — uniqueness")
    class EventIdRegression {

        @Test
        @DisplayName("the same resource re-sent in a new bundle (new correlation id) gets the same id")
        void sameResourceResentInANewBundleGetsSameId() {
            CloudEventDto firstDelivery = cloudEventEnvelopeBuilder.build(
                    CONSENT_JSON, "KE-SHRP-170CDF0A-1363-4972-B36A", "Consent",
                    sourceMetadata("7f3c9b12-4d5e-4a6b-8c7d-9e0f1a2b3c4d"));
            CloudEventDto resentDelivery = cloudEventEnvelopeBuilder.build(
                    CONSENT_JSON, "KE-SHRP-170CDF0A-1363-4972-B36A", "Consent",
                    sourceMetadata("0b1e2d3c-5f6a-4b7c-8d9e-a0b1c2d3e4f5"));

            assertThat(resentDelivery.id()).isEqualTo(firstDelivery.id());
        }

        @Test
        @DisplayName("two entries in ONE bundle never share a CloudEvents id")
        void twoEntriesInOneBundleNeverShareAnId() {
            String observationJson = """
                    {"resourceType": "Observation", "id": "obs-001", "status": "final"}""";
            CloudEventDto consentEvent = cloudEventEnvelopeBuilder.build(
                    CONSENT_JSON, "KE-SHRP-170CDF0A-1363-4972-B36A", "Consent",
                    sourceMetadata());
            CloudEventDto observationEvent = cloudEventEnvelopeBuilder.build(
                    observationJson, "KE-SHRP-170CDF0A-1363-4972-B36A", "Observation",
                    sourceMetadata());

            assertThat(consentEvent.id()).isNotEqualTo(observationEvent.id());
        }

        @Test
        @DisplayName("versions differing only in redacted content still get different ids — the id is taken before redaction")
        void idIsTakenBeforeRedaction() {
            String firstReading = """
                    {"resourceType": "Observation", "id": "obs-001", "status": "final",
                     "code": {"coding": [{"system": "http://loinc.org", "code": "8310-5"}]},
                     "valueQuantity": {"value": 38.4, "unit": "Cel"}}""";
            String correctedReading = firstReading.replace("38.4", "37.4");

            CloudEventDto first = cloudEventEnvelopeBuilder.build(
                    firstReading, "KE-SHRP-170CDF0A-1363-4972-B36A", "Observation", sourceMetadata());
            CloudEventDto corrected = cloudEventEnvelopeBuilder.build(
                    correctedReading, "KE-SHRP-170CDF0A-1363-4972-B36A", "Observation", sourceMetadata());

            // the forwarded data is identical once the value is redacted, but the correction must
            // not be dropped by the Collector as a duplicate
            assertThat(first.data()).isEqualTo(corrected.data());
            assertThat(corrected.id()).isNotEqualTo(first.id());
        }
    }

    @Nested
    @DisplayName("clinical-data redaction")
    class Redaction {

        @Test
        @DisplayName("the forwarded data has the clinical content removed")
        void forwardedDataIsRedacted() {
            CloudEventDto cloudEvent = cloudEventEnvelopeBuilder.build("""
                    {"resourceType": "Condition", "id": "cond-001",
                     "code": {"coding": [{"code": "5A11", "display": "Type 2 diabetes mellitus"}]},
                     "subject": {"reference": "Patient/KE-SHRP-170CDF0A-1363-4972-B36A", "display": "TEST PATIENT"}}""",
                    "KE-SHRP-170CDF0A-1363-4972-B36A", "Condition", sourceMetadata());

            assertThat(cloudEvent.data().has("code")).isFalse();
            assertThat(cloudEvent.data().path("subject").has("display")).isFalse();
            assertThat(cloudEvent.data().path("subject").path("reference").asText())
                    .isEqualTo("Patient/KE-SHRP-170CDF0A-1363-4972-B36A");
        }
    }

    @Nested
    @DisplayName("malformed FHIR JSON")
    class MalformedFhirJson {

        @Test
        @DisplayName("unparseable fhirJson throws FhirMappingException")
        void unparseableFhirJsonThrowsFhirMappingException() {
            assertThatExceptionOfType(FhirMappingException.class)
                    .isThrownBy(() -> cloudEventEnvelopeBuilder.build(
                            "not valid json", "KE-SHRP-170CDF0A-1363-4972-B36A", "Consent",
                            sourceMetadata()));
        }
    }
}
