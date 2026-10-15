package org.openphc.tiberbu.cce.emitter.cloudevents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the content-based id contract: an unchanged resource sent again gets
 * the same id (so the Collector drops it as a duplicate), while any change to
 * the resource, {@code meta} included, gets a new one. The sender's key order
 * never matters.
 */
class EventIdGeneratorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final EventIdGenerator eventIdGenerator = new EventIdGenerator();

    private static JsonNode resource(String json) {
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (Exception invalidTestJson) {
            throw new IllegalArgumentException(invalidTestJson);
        }
    }

    private static final String PROPOSED_CONSENT = """
            {"resourceType":"Consent","id":"VCR-20260901-57098420","status":"proposed",
             "meta":{"profile":["https://nshr-uat.sha.go.ke/fhir/StructureDefinition/ke-consent"]},
             "patient":{"reference":"Patient/KE-SHRP-170CDF0A-1363-4972-B36A"},
             "verification":[{"verified":false}]}
            """;

    @Nested
    @DisplayName("same content — same id")
    class SameContent {

        @Test
        @DisplayName("the same resource sent twice produces the same id — a re-send is caught as a duplicate")
        void sameResourceSentTwiceProducesTheSameId() {
            String firstCopy = eventIdGenerator.generate(resource(PROPOSED_CONSENT));
            String secondCopy = eventIdGenerator.generate(resource(PROPOSED_CONSENT));

            assertThat(firstCopy).isEqualTo(secondCopy);
        }

        @Test
        @DisplayName("the sender's key order, at any depth, does not change the id")
        void keyOrderDoesNotChangeTheId() {
            String oneOrder = eventIdGenerator.generate(resource("""
                    {"resourceType":"Observation","id":"fa86e0c582baf04e","status":"final",
                     "valueQuantity":{"value":38.0,"unit":"Cel"}}
                    """));
            String otherOrder = eventIdGenerator.generate(resource("""
                    {"valueQuantity":{"unit":"Cel","value":38.0},"status":"final",
                     "id":"fa86e0c582baf04e","resourceType":"Observation"}
                    """));

            assertThat(otherOrder).isEqualTo(oneOrder);
        }

        @Test
        @DisplayName("generating the id leaves the resource unchanged — it is still the CloudEvent's data")
        void generatingTheIdDoesNotModifyTheResource() {
            JsonNode consent = resource(PROPOSED_CONSENT);

            eventIdGenerator.generate(consent);

            assertThat(consent).isEqualTo(resource(PROPOSED_CONSENT));
        }
    }

    @Nested
    @DisplayName("changed content — new id")
    class ChangedContent {

        @Test
        @DisplayName("REGRESSION: the two real Consent samples (proposed, then verified) get DIFFERENT ids")
        void proposedAndVerifiedConsentGetDifferentIds() {
            // The two real tibERbu Consent samples that once collided on bundleId + resource.id:
            // same Consent.id, ~26 minutes apart, the first proposed and unverified, the second
            // active and verified. Their content differs, so their ids must too.
            String proposed = eventIdGenerator.generate(resource(PROPOSED_CONSENT));
            String verified = eventIdGenerator.generate(resource("""
                    {"resourceType":"Consent","id":"VCR-20260901-57098420","status":"active",
                     "meta":{"profile":["https://nshr-uat.sha.go.ke/fhir/StructureDefinition/ke-consent"]},
                     "patient":{"reference":"Patient/KE-SHRP-170CDF0A-1363-4972-B36A"},
                     "verification":[{"verified":true}]}
                    """));

            assertThat(verified).isNotEqualTo(proposed);
        }

        @Test
        @DisplayName("a change only in meta (a confidentiality label) produces a new id")
        void metaChangeProducesANewId() {
            String normal = eventIdGenerator.generate(resource("""
                    {"resourceType":"Condition","id":"cfa51b6b2491c19e",
                     "meta":{"security":[{"code":"N"}]}}
                    """));
            String restricted = eventIdGenerator.generate(resource("""
                    {"resourceType":"Condition","id":"cfa51b6b2491c19e",
                     "meta":{"security":[{"code":"R"}]}}
                    """));

            assertThat(restricted).isNotEqualTo(normal);
        }

        @Test
        @DisplayName("a change in one nested field (provisional -> confirmed) produces a new id")
        void nestedFieldChangeProducesANewId() {
            String provisional = eventIdGenerator.generate(resource("""
                    {"resourceType":"Condition","id":"cfa51b6b2491c19e",
                     "verificationStatus":{"coding":[{"code":"provisional"}]}}
                    """));
            String confirmed = eventIdGenerator.generate(resource("""
                    {"resourceType":"Condition","id":"cfa51b6b2491c19e",
                     "verificationStatus":{"coding":[{"code":"confirmed"}]}}
                    """));

            assertThat(confirmed).isNotEqualTo(provisional);
        }

        @Test
        @DisplayName("two resources with the same content but different ids get different ids")
        void differentResourceIdsProduceDifferentIds() {
            String firstVital = eventIdGenerator.generate(resource("""
                    {"resourceType":"Observation","id":"obs-001","status":"final"}
                    """));
            String secondVital = eventIdGenerator.generate(resource("""
                    {"resourceType":"Observation","id":"obs-002","status":"final"}
                    """));

            assertThat(secondVital).isNotEqualTo(firstVital);
        }

        @Test
        @DisplayName("array order is part of the content — reordered codings produce a new id")
        void arrayOrderIsPartOfTheContent() {
            String typeFirst = eventIdGenerator.generate(resource("""
                    {"resourceType":"Encounter","id":"e1","type":[{"coding":[{"code":"AMB"},{"code":"11429006"}]}]}
                    """));
            String typeSwapped = eventIdGenerator.generate(resource("""
                    {"resourceType":"Encounter","id":"e1","type":[{"coding":[{"code":"11429006"},{"code":"AMB"}]}]}
                    """));

            assertThat(typeSwapped).isNotEqualTo(typeFirst);
        }
    }

    @Test
    @DisplayName("the generated id is a well-formed UUID v5")
    void generatedIdIsAWellFormedUuidV5() {
        UUID eventId = UUID.fromString(eventIdGenerator.generate(resource(PROPOSED_CONSENT)));

        assertThat(eventId.version()).isEqualTo(5);
    }
}
