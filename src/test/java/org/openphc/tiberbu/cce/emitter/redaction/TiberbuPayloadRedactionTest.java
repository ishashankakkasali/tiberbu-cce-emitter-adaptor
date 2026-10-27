package org.openphc.tiberbu.cce.emitter.redaction;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openphc.tiberbu.cce.emitter.fhir.ClinicalEventTimeExtractor;
import org.openphc.tiberbu.cce.emitter.fhir.FacilityDetails;
import org.openphc.tiberbu.cce.emitter.fhir.FacilityIdExtractor;
import org.openphc.tiberbu.cce.emitter.fhir.FhirResourceParser;
import org.openphc.tiberbu.cce.emitter.fhir.FhirResourceTypeDetector;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redacts tibERbu-shaped resources (synthetic, modelled on captured tibERbu bundles) with the
 * default rules and checks, per resource type, that:
 * <ul>
 *   <li>the clinical content is gone;</li>
 *   <li>every field the Kenya protocol triggers on survives;</li>
 *   <li>the clinical time the forwarding order reads is unchanged;</li>
 *   <li>the facility ID still resolves to the same Organization;</li>
 *   <li>the result is still valid FHIR R4 — the Collector rejects anything else as INVALID_FHIR.</li>
 * </ul>
 */
class TiberbuPayloadRedactionTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String FACILITY = "KE-SHRF-F75DBB8A-E36C-44DE-95F4";

    private final ClinicalDataRedactor redactor =
            new ClinicalDataRedactor(new ClinicalDataRedactionProperties(true, null), new SimpleMeterRegistry());
    private final FhirResourceParser fhirResourceParser = new FhirResourceParser(FhirContext.forR4());
    private final FacilityIdExtractor facilityIdExtractor = new FacilityIdExtractor();
    private final ClinicalEventTimeExtractor clinicalEventTimeExtractor =
            new ClinicalEventTimeExtractor(new SimpleMeterRegistry());
    private final FhirResourceTypeDetector resourceTypeDetector = new FhirResourceTypeDetector();

    /** What the redaction leaves of one resource, with the before/after facility and clinical time. */
    private record Redacted(JsonNode resource, FacilityDetails facilityBefore, FacilityDetails facilityAfter,
                            OffsetDateTime timeBefore, OffsetDateTime timeAfter) { }

    private Redacted redact(String resourceJson) throws Exception {
        JsonNode original = OBJECT_MAPPER.readTree(resourceJson);
        FacilityDetails facilityBefore = facilityIdExtractor.extract(fhirResourceParser.parse(resourceJson));
        OffsetDateTime timeBefore = clinicalEventTimeExtractor.extract(resourceTypeDetector.detect(original), original);

        JsonNode redacted = redactor.redact(original.deepCopy());
        String redactedJson = OBJECT_MAPPER.writeValueAsString(redacted);
        IBaseResource reparsed = fhirResourceParser.parse(redactedJson); // throws if no longer valid FHIR R4
        return new Redacted(redacted, facilityBefore, facilityIdExtractor.extract(reparsed),
                timeBefore, clinicalEventTimeExtractor.extract(resourceTypeDetector.detect(redacted), redacted));
    }

    private static void assertFacilityAndTimeUnchanged(Redacted redacted) {
        assertThat(redacted.facilityAfter()).isEqualTo(redacted.facilityBefore());
        assertThat(redacted.timeAfter()).isEqualTo(redacted.timeBefore());
    }

    @Test
    @DisplayName("Observation (vitals): value, interpretation, range, method and extension go; code, category and time stay")
    void observation() throws Exception {
        Redacted redacted = redact("""
                {"resourceType": "Observation", "id": "obs-1", "status": "final",
                 "category": [{"coding": [{"system": "http://terminology.hl7.org/CodeSystem/observation-category", "code": "vital-signs"}]}],
                 "code": {"coding": [{"system": "http://loinc.org", "code": "8310-5", "display": "Body temperature"}]},
                 "subject": {"reference": "Patient/KE-SHRP-TEST-0001", "display": "TEST PATIENT"},
                 "effectiveDateTime": "2026-10-23T13:38:00+03:00",
                 "performer": [{"reference": "Practitioner/KE-SHRPR-TEST-01"}],
                 "valueQuantity": {"value": 38.4, "unit": "Cel"},
                 "interpretation": [{"coding": [{"code": "H"}]}],
                 "referenceRange": [{"low": {"value": 36.1}}],
                 "method": {"text": "Axillary"},
                 "extension": [{"url": "https://nshr-uat.sha.go.ke/fhir/StructureDefinition/em-no-findings-on-exam",
                                "valueBoolean": true}]}
                """);
        JsonNode observation = redacted.resource();

        assertThat(observation.has("extension")).as("em-no-findings-on-exam is the exam result").isFalse();
        assertThat(observation.has("valueQuantity")).isFalse();
        assertThat(observation.has("interpretation")).isFalse();
        assertThat(observation.has("referenceRange")).isFalse();
        assertThat(observation.has("method")).isFalse();
        assertThat(observation.path("subject").has("display")).as("patient name").isFalse();
        assertThat(observation.path("subject").path("reference").asText()).isEqualTo("Patient/KE-SHRP-TEST-0001");
        assertThat(observation.at("/category/0/coding/0/code").asText()).isEqualTo("vital-signs");
        assertThat(observation.at("/code/coding/0/code").asText()).isEqualTo("8310-5");
        assertFacilityAndTimeUnchanged(redacted);
    }

    @Test
    @DisplayName("Condition: the diagnosis code and text go; statuses and onset stay")
    void condition() throws Exception {
        Redacted redacted = redact("""
                {"resourceType": "Condition", "id": "cond-1",
                 "clinicalStatus": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/condition-clinical", "code": "active"}]},
                 "verificationStatus": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/condition-ver-status", "code": "confirmed"}]},
                 "code": {"coding": [{"system": "http://hl7.org/fhir/sid/icd-11", "code": "5A11", "display": "Type 2 diabetes mellitus"}]},
                 "subject": {"reference": "Patient/KE-SHRP-TEST-0001"},
                 "onsetDateTime": "2026-10-23T13:50:00+03:00",
                 "recordedDate": "2026-10-23T13:50:34+03:00",
                 "text": {"status": "generated", "div": "<div xmlns=\\"http://www.w3.org/1999/xhtml\\">Type 2 diabetes</div>"}}
                """);

        assertThat(redacted.resource().has("code")).isFalse();
        assertThat(redacted.resource().has("text")).isFalse();
        assertThat(redacted.resource().toString()).doesNotContain("diabetes");
        assertThat(redacted.resource().at("/clinicalStatus/coding/0/code").asText()).isEqualTo("active");
        assertFacilityAndTimeUnchanged(redacted);
    }

    @Test
    @DisplayName("Encounter: extension (emergency caller) and reasonCode go; class, type and serviceProvider stay")
    void encounter() throws Exception {
        Redacted redacted = redact("""
                {"resourceType": "Encounter", "id": "enc-1", "status": "finished",
                 "class": {"system": "http://terminology.hl7.org/CodeSystem/v3-ActCode", "code": "AMB"},
                 "type": [{"coding": [{"system": "http://snomed.info/sct", "code": "11429006", "display": "Consultation"}]}],
                 "subject": {"reference": "Patient/KE-SHRP-TEST-0001"},
                 "period": {"start": "2026-10-23T13:45:00+03:00"},
                 "reasonCode": [{"text": "Severe headache"}],
                 "serviceProvider": {"reference": "Organization/KE-SHRF-F75DBB8A-E36C-44DE-95F4", "display": "MBAGATHI"},
                 "extension": [{"url": "https://nshr-uat.sha.go.ke/fhir/StructureDefinition/em-incident-caller",
                                "valueString": "Caller TEST NAME, 0700000000"}]}
                """);

        assertThat(redacted.resource().has("extension")).isFalse();
        assertThat(redacted.resource().has("reasonCode")).isFalse();
        assertThat(redacted.resource().at("/class/code").asText()).isEqualTo("AMB");
        assertThat(redacted.resource().at("/type/0/coding/0/code").asText()).isEqualTo("11429006");
        assertThat(redacted.facilityAfter().facilityId()).isEqualTo(FACILITY);
        assertFacilityAndTimeUnchanged(redacted);
    }

    @Test
    @DisplayName("EpisodeOfCare: diagnosis goes; type (general-opd), status and managingOrganization stay")
    void episodeOfCare() throws Exception {
        Redacted redacted = redact("""
                {"resourceType": "EpisodeOfCare", "id": "eoc-1", "status": "active",
                 "type": [{"coding": [{"system": "https://nshr-uat.sha.go.ke/fhir/CodeSystem/ke-episode-of-care-type-cs", "code": "general-opd"}]}],
                 "diagnosis": [{"condition": {"reference": "Condition/cond-1"}}],
                 "patient": {"reference": "Patient/KE-SHRP-TEST-0001", "display": "TEST PATIENT"},
                 "managingOrganization": {"reference": "Organization/KE-SHRF-F75DBB8A-E36C-44DE-95F4"},
                 "period": {"start": "2026-10-23T13:33:00+03:00"}}
                """);

        assertThat(redacted.resource().has("diagnosis")).isFalse();
        assertThat(redacted.resource().path("patient").has("display")).as("patient name").isFalse();
        assertThat(redacted.resource().at("/type/0/coding/0/code").asText()).isEqualTo("general-opd");
        assertThat(redacted.resource().path("status").asText()).isEqualTo("active");
        assertThat(redacted.facilityAfter().facilityId()).isEqualTo(FACILITY);
        assertFacilityAndTimeUnchanged(redacted);
    }

    @Test
    @DisplayName("ServiceRequest: the test ordered, the note and the evacuation extension go; category, status, requester and performer stay")
    void serviceRequest() throws Exception {
        Redacted redacted = redact("""
                {"resourceType": "ServiceRequest", "id": "sr-1", "status": "active", "intent": "order",
                 "category": [{"coding": [{"system": "http://snomed.info/sct", "code": "108252007"}]}],
                 "code": {"coding": [{"code": "58410-2", "display": "Complete blood count"}]},
                 "subject": {"reference": "Patient/KE-SHRP-TEST-0001"},
                 "authoredOn": "2026-10-23T13:47:00+03:00",
                 "requester": {"reference": "Practitioner/KE-SHRPR-TEST-01"},
                 "performer": [{"reference": "Organization/KE-SHRF-F75DBB8A-E36C-44DE-95F4"}],
                 "note": [{"text": "Fasting sample"}],
                 "extension": [{"url": "https://nshr-uat.sha.go.ke/fhir/StructureDefinition/evacuation-origin",
                                "valueReference": {"reference": "Location/loc-scene-test", "display": "Incident scene — TEST"}},
                               {"url": "https://nshr-uat.sha.go.ke/fhir/StructureDefinition/em-transport-modality",
                                "valueCode": "road"}]}
                """);

        assertThat(redacted.resource().has("code")).isFalse();
        assertThat(redacted.resource().has("note")).isFalse();
        assertThat(redacted.resource().has("extension")).as("the incident scene").isFalse();
        assertThat(redacted.resource().at("/category/0/coding/0/code").asText()).isEqualTo("108252007");
        assertThat(redacted.facilityAfter().facilityId()).isEqualTo(FACILITY);
        assertFacilityAndTimeUnchanged(redacted);
    }

    @Test
    @DisplayName("MedicationRequest: the drug, dosage and dispense request go; category and status stay")
    void medicationRequest() throws Exception {
        Redacted redacted = redact("""
                {"resourceType": "MedicationRequest", "id": "mr-1", "status": "active", "intent": "order",
                 "category": [{"coding": [{"system": "http://terminology.hl7.org/CodeSystem/medicationrequest-category", "code": "outpatient"}]}],
                 "medicationCodeableConcept": {"text": "Metformin 500mg"},
                 "subject": {"reference": "Patient/KE-SHRP-TEST-0001"},
                 "authoredOn": "2026-10-23T14:00:00+03:00",
                 "dosageInstruction": [{"text": "1 tablet twice daily"}],
                 "dispenseRequest": {"quantity": {"value": 60}}}
                """);

        assertThat(redacted.resource().has("medicationCodeableConcept")).isFalse();
        assertThat(redacted.resource().has("dosageInstruction")).isFalse();
        assertThat(redacted.resource().has("dispenseRequest")).isFalse();
        assertThat(redacted.resource().at("/category/0/coding/0/code").asText()).isEqualTo("outpatient");
        assertFacilityAndTimeUnchanged(redacted);
    }

    @Test
    @DisplayName("MedicationDispense: the drug, quantity and days of supply go; status and whenHandedOver stay")
    void medicationDispense() throws Exception {
        Redacted redacted = redact("""
                {"resourceType": "MedicationDispense", "id": "md-1", "status": "completed",
                 "medicationCodeableConcept": {"text": "Metformin 500mg"},
                 "subject": {"reference": "Patient/KE-SHRP-TEST-0001"},
                 "quantity": {"value": 60},
                 "daysSupply": {"value": 2, "unit": "Month", "system": "http://unitsofmeasure.org", "code": "mo"},
                 "whenHandedOver": "2026-10-23T14:01:00+03:00"}
                """);

        assertThat(redacted.resource().has("medicationCodeableConcept")).isFalse();
        assertThat(redacted.resource().has("quantity")).isFalse();
        assertThat(redacted.resource().has("daysSupply")).isFalse();
        assertThat(redacted.resource().path("status").asText()).isEqualTo("completed");
        assertFacilityAndTimeUnchanged(redacted);
    }

    @Test
    @DisplayName("Procedure: the procedure performed goes; status and performedDateTime stay")
    void procedure() throws Exception {
        Redacted redacted = redact("""
                {"resourceType": "Procedure", "id": "proc-1", "status": "completed",
                 "code": {"coding": [{"code": "IMAGING-PROCEDURE", "display": "Chest X-ray"}]},
                 "subject": {"reference": "Patient/KE-SHRP-TEST-0001"},
                 "performedDateTime": "2026-10-23T13:55:00+03:00",
                 "location": {"reference": "Location/FID-47-114751-8"}}
                """);

        assertThat(redacted.resource().has("code")).isFalse();
        assertThat(redacted.resource().path("status").asText()).isEqualTo("completed");
        assertFacilityAndTimeUnchanged(redacted);
    }

    @Test
    @DisplayName("Composition: the summary sections go; type, status, date and custodian stay")
    void composition() throws Exception {
        Redacted redacted = redact("""
                {"resourceType": "Composition", "id": "comp-1", "status": "final",
                 "type": {"coding": [{"system": "http://loinc.org", "code": "60591-5", "display": "Patient summary Document"}]},
                 "subject": {"reference": "Patient/KE-SHRP-TEST-0001", "display": "TEST PATIENT"},
                 "date": "2026-10-23T16:00:00+03:00",
                 "author": [{"reference": "Practitioner/KE-SHRPR-TEST-01"}],
                 "title": "Visit Summary - HLC-APP-261023-000000-0000",
                 "custodian": {"reference": "Organization/KE-SHRF-F75DBB8A-E36C-44DE-95F4"},
                 "section": [{"title": "Diagnosis",
                              "text": {"status": "generated", "div": "<div xmlns=\\"http://www.w3.org/1999/xhtml\\">Type 2 diabetes</div>"},
                              "entry": [{"reference": "Condition/cond-1"}]}]}
                """);

        assertThat(redacted.resource().has("section")).isFalse();
        assertThat(redacted.resource().toString()).doesNotContain("diabetes");
        assertThat(redacted.resource().at("/type/coding/0/code").asText()).isEqualTo("60591-5");
        assertThat(redacted.resource().path("title").asText()).startsWith("Visit Summary");
        assertThat(redacted.facilityAfter().facilityId()).isEqualTo(FACILITY);
        assertFacilityAndTimeUnchanged(redacted);
    }

    @Test
    @DisplayName("Consent: nothing type-specific is removed; status, category and organization stay")
    void consent() throws Exception {
        Redacted redacted = redact("""
                {"resourceType": "Consent", "id": "VCR-TEST-0001", "status": "active",
                 "scope": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/consentscope", "code": "patient-privacy"}]},
                 "category": [{"coding": [{"system": "http://loinc.org", "code": "59284-0"}]}],
                 "patient": {"reference": "Patient/KE-SHRP-TEST-0001", "display": "TEST PATIENT"},
                 "dateTime": "2026-10-23T13:33:00+03:00",
                 "organization": [{"reference": "Organization/KE-SHRF-F75DBB8A-E36C-44DE-95F4"}]}
                """);

        assertThat(redacted.resource().path("patient").has("display")).as("patient name").isFalse();
        assertThat(redacted.resource().path("status").asText()).isEqualTo("active");
        assertThat(redacted.resource().at("/category/0/coding/0/code").asText()).isEqualTo("59284-0");
        assertThat(redacted.facilityAfter().facilityId()).isEqualTo(FACILITY);
        assertFacilityAndTimeUnchanged(redacted);
    }
}
