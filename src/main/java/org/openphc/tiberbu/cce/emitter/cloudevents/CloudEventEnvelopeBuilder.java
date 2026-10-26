package org.openphc.tiberbu.cce.emitter.cloudevents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openphc.tiberbu.cce.emitter.exception.FhirMappingException;
import org.openphc.tiberbu.cce.emitter.model.CloudEventDto;
import org.openphc.tiberbu.cce.emitter.model.SourceMetadata;
import org.openphc.tiberbu.cce.emitter.redaction.ClinicalDataRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

/**
 * Builds the CloudEvents v1.0 envelope for one bundle entry.
 *
 * <p>Example — given the {@code Consent} entry from api-reference.md § 3.1
 * ({@code fhirJson} holding {@code {"resourceType":"Consent","id":"VCR-20260901-57098420",...}},
 * {@code patientId="KE-SHRP-170CDF0A-1363-4972-B36A"}, {@code resourceType="Consent"}),
 * {@link #build} returns a {@link CloudEventDto} with {@code type="Consent"}
 * (the FHIR resource type, verbatim — no transformation),
 * {@code source} from {@code sourceMetadata.sourceIdentifier()} (never {@code
 * meta.source} from the inbound envelope), {@code id} from {@link
 * EventIdGenerator}, and {@code data} holding the parsed {@code Consent}
 * resource with its clinical content redacted ({@link ClinicalDataRedactor}).
 */
@Component
public class CloudEventEnvelopeBuilder {

    private static final Logger log = LoggerFactory.getLogger(CloudEventEnvelopeBuilder.class);

    private static final String SPEC_VERSION = "1.0";

    /**
     * Confirmed against the Collector's own {@code PayloadValidator}: this is
     * one of exactly two {@code datacontenttype} values it accepts, and the
     * one that triggers full FHIR R4 structural validation there (the other,
     * {@code "application/json"}, gets only a basic non-empty-object check).
     * Any other value is rejected as {@code UNSUPPORTED_CONTENT_TYPE}.
     */
    private static final String DATA_CONTENT_TYPE = "application/fhir+json";

    private final EventIdGenerator eventIdGenerator;
    private final ObjectMapper objectMapper;
    private final ClinicalDataRedactor clinicalDataRedactor;

    public CloudEventEnvelopeBuilder(EventIdGenerator eventIdGenerator, ObjectMapper objectMapper,
                                     ClinicalDataRedactor clinicalDataRedactor) {
        this.eventIdGenerator = eventIdGenerator;
        this.objectMapper = objectMapper;
        this.clinicalDataRedactor = clinicalDataRedactor;
    }

    /**
     * Builds the CloudEvents envelope for one bundle entry.
     *
     * <p>Step 1: parse {@code fhirJson} into the {@code data} field's {@link
     * JsonNode}. Step 2: derive the event {@code id} from that same parsed
     * node's content — no second parse — with {@link EventIdGenerator}.
     * Step 3: redact the clinical content from {@code data} ({@link
     * ClinicalDataRedactor}). Step 4: assemble every other field from
     * {@code sourceMetadata} and the method's own parameters.
     *
     * <p>The id is derived <em>before</em> redaction, from the complete
     * resource: two versions of a resource that differ only in redacted
     * content (e.g. a corrected Observation value) must still get different
     * ids, or the Collector would drop the correction as a duplicate.
     *
     * @param fhirJson       the entry's FHIR resource, as JSON text (from {@code
     *                       BundleEntry#resourceJson})
     * @param patientId      the patient identifier from {@code
     *                       PatientIdExtractor}, becomes the CloudEvents {@code
     *                       subject}
     * @param resourceType   the FHIR {@code resourceType} (e.g. {@code
     *                       "Consent"}), becomes the CloudEvents {@code type}
     *                       verbatim
     * @param sourceMetadata this entry's source metadata
     * @return the fully populated envelope
     * @throws FhirMappingException when {@code fhirJson} cannot be parsed
     */
    public CloudEventDto build(String fhirJson, String patientId, String resourceType, SourceMetadata sourceMetadata) {
        JsonNode data = parseToJsonNode(fhirJson);
        // id from the complete resource first: a change only in redacted content must still change the id
        String eventId = eventIdGenerator.generate(data);
        // then strip the clinical content; everything the Collector persists is the minimised form
        data = clinicalDataRedactor.redact(data);
        String eventTime = sourceMetadata.eventTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        CloudEventDto cloudEvent = new CloudEventDto(
                SPEC_VERSION,
                eventId,
                sourceMetadata.sourceIdentifier(),
                resourceType,
                patientId,
                eventTime,
                DATA_CONTENT_TYPE,
                sourceMetadata.facilityId(),
                sourceMetadata.facilityName(),
                null,
                sourceMetadata.correlationId(),
                data);

        log.debug("Built CloudEvent: id={}, type={}, source={}, subject={}",
                eventId, resourceType, sourceMetadata.sourceIdentifier(), patientId);
        return cloudEvent;
    }

    /**
     * Parses {@code fhirJson} into the {@link JsonNode} that becomes the
     * envelope's {@code data} field.
     *
     * @throws FhirMappingException when {@code fhirJson} is not valid JSON
     */
    private JsonNode parseToJsonNode(String fhirJson) {
        try {
            return objectMapper.readTree(fhirJson);
        } catch (JsonProcessingException malformedJson) {
            throw new FhirMappingException(
                    "Failed to parse FHIR JSON for CloudEvent data field: " + malformedJson.getMessage(), malformedJson);
        }
    }
}
