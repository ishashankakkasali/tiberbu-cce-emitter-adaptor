package org.openphc.tiberbu.cce.emitter.redaction;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for clinical-data redaction, bound to {@code cce.emitter.redaction}.
 *
 * <p>CCE is a care <em>coordination</em> engine: it needs to know <em>that</em> a clinical step
 * happened, when, for which patient and at which facility — not <em>what the clinical finding
 * was</em>.
 *
 * <h2>How rules combine</h2>
 *
 * <p>The rule with resource type {@value #ALL_RESOURCE_TYPES} is applied to <b>every</b> resource.
 * A resource's own rule, if it has one, is applied <b>in addition</b>. So each type's rule lists
 * only what is special about that type, and the content that is sensitive everywhere is written
 * once:
 *
 * <pre>
 *   Condition  →  the "*" list  +  code
 *   Observation → the "*" list  +  component, extension   (code deliberately absent — see below)
 *   SomeNewType → the "*" list                               (no rule of its own)
 * </pre>
 *
 * <p>Rules are per-resource-type because the same FHIR element carries very different sensitivity
 * depending on the resource. {@code code} is the clearest case:
 *
 * <ul>
 *   <li>{@code Condition.code} — the diagnosis ("Type 2 diabetes mellitus") → must be removed</li>
 *   <li>{@code AllergyIntolerance.code} — the allergy ("allergy on aminophyline") → must be removed</li>
 *   <li>{@code ServiceRequest.code} — the test ordered ("a1-Acid Glycoprotein") → must be removed</li>
 *   <li>{@code Observation.code} — the observation <em>type</em> (LOINC 8716-3 "Vital signs") →
 *       must be <b>kept</b>: protocol triggers match on it, so removing it would stop compliance
 *       tracking working</li>
 * </ul>
 *
 * <h2>Field syntax</h2>
 *
 * <p>An entry is either a root field name or a dot-delimited path. A segment ending in {@code []}
 * steps through an array and applies to every element:
 *
 * <pre>
 *   code                                 → Condition.code
 *   subject.display                      → Observation.subject.display
 *   hospitalization.dischargeDisposition → Encounter.hospitalization.dischargeDisposition
 *   reaction[].manifestation             → AllergyIntolerance.reaction[*].manifestation
 * </pre>
 *
 * <p>The {@code []} marker is required where the data is an array; without it the path matches
 * nothing. That is not silent — see {@link ClinicalDataRedactor} for how it is reported.
 *
 * <p>Prefer removing a whole structure over pathing into it. {@code component} on Observation drops
 * every sub-observation in one step; a path is only needed when part of the structure must survive.
 *
 * @param enabled master switch; {@code false} passes payloads through untouched
 * @param rules   one entry per resource type, plus the {@value #ALL_RESOURCE_TYPES} entry applied
 *                to everything
 */
@ConfigurationProperties(prefix = "cce.emitter.redaction")
public record ClinicalDataRedactionProperties(
        Boolean enabled,
        List<ResourceRule> rules
) {

    /**
     * The rule applied to every resource, on top of that resource's own rule.
     *
     * <p>There is deliberately no second wildcard token for "types without a rule of their own":
     * such a type is simply one with nothing to add, and this rule already covers it. One token,
     * one meaning.
     */
    public static final String ALL_RESOURCE_TYPES = "*";

    /**
     * What to remove for one FHIR resource type.
     *
     * @param resourceType FHIR resource type (e.g. {@code Condition}), or {@value #ALL_RESOURCE_TYPES}
     * @param fields       root field names and/or dot-delimited paths to remove
     */
    public record ResourceRule(String resourceType, List<String> fields) {
        public ResourceRule {
            if (fields == null) fields = List.of();
        }
        // No convenience constructor: an extra constructor on a record stops Spring Boot
        // identifying the canonical one, and the whole redaction tree then fails to bind.
    }

    /**
     * Content that is sensitive on every resource type that carries it, plus the patient's name.
     * Verified against every reader in the platform (matcher, collector, compliance, insights,
     * ClickHouse materialised columns): none of these are consumed, so removing them changes no
     * behaviour.
     */
    private static final List<String> ALL_TYPES_FIELDS = List.of(
            // value[x] — the measurement or coded finding
            "valueQuantity", "valueCodeableConcept", "valueString", "valueBoolean",
            "valueInteger", "valueRange", "valueRatio", "valueSampledData",
            "valueTime", "valueDateTime", "valuePeriod", "valueAttachment",
            // Free-text and interpretive clinical content
            "note", "text", "interpretation", "dataAbsentReason",
            "bodySite", "method", "specimen", "referenceRange",
            // Embedded resources (Provenance, contained Observations carrying values)
            "contained",
            // Condition-style qualifiers
            "severity", "stage", "evidence",
            // Ordering / prescribing detail and clinical justification
            "dosageInstruction", "reasonCode", "reasonReference",
            "orderDetail", "patientInstruction",
            // Any remaining free-text a clinician can type into. Verified 2026-09-18 that no
            // service reads these from inbound events (the two `description` hits in insights /
            // intelligence are writes that BUILD output, not reads).
            "comment", "description", "statusReason", "instruction", "summary",
            "conclusion", "conclusionCode", "outcome",
            // The patient's name. Not clinical, but direct identifying data in the same payload,
            // and nothing downstream reads it — the patient is keyed on subject.reference /
            // patient.reference (the UPID), which is preserved. Both spellings are needed because
            // AllergyIntolerance and several other types use `patient` rather than `subject`.
            "subject.display", "patient.display"
    );

    /**
     * One rule per resource type tibERbu sends, listing only what that type adds to
     * {@link #ALL_TYPES_FIELDS}. Volumes on dev at time of writing ({@code inbound_event_log},
     * source tiberbu): Encounter 15.8k, Observation 12.1k, Condition 7.5k, Procedure 6.9k,
     * MedicationDispense 4.5k, EpisodeOfCare 4.0k, Consent 2.3k, MedicationRequest 1.1k,
     * MedicationAdministration 486, AllergyIntolerance 348, ServiceRequest 300, Composition 93,
     * DiagnosticReport 3.
     *
     * <p>Kept on every type: the elements the Kenya protocol triggers on (Encounter class/type,
     * EpisodeOfCare type/status, Observation category/code, ServiceRequest category/status,
     * Composition type, every status), the clinical times the forwarding order reads, and the
     * Organization references the facility ID is read from (serviceProvider, managingOrganization,
     * organization, custodian, requester, performer).
     */
    private static final List<ResourceRule> DEFAULT_RULES = List.of(
            new ResourceRule(ALL_RESOURCE_TYPES, ALL_TYPES_FIELDS),

            // code = the observation TYPE (LOINC) and category = vital-signs/laboratory — protocol
            // triggers match on them, kept. component holds sub-observations with their own value[x];
            // extension holds em-no-findings-on-exam / em-no-investigation-performed, the exam result.
            new ResourceRule("Observation", List.of("component", "extension")),

            // diagnosis = the encounter's diagnosis. class/type drive protocol matching and
            // serviceProvider is the facility, so they are kept — hence dischargeDisposition needs a
            // nested path. extension is dropped wholesale: tibERbu's em-incident-caller extension holds
            // the emergency caller's details, and the facility comes from serviceProvider, never an
            // extension.
            new ResourceRule("Encounter",
                    List.of("diagnosis", "hospitalization.dischargeDisposition", "extension")),

            // diagnosis = the conditions the episode is for. type (general-opd) and status are what
            // the visit steps trigger on; managingOrganization is the facility — all kept.
            new ResourceRule("EpisodeOfCare", List.of("diagnosis")),

            // code = the test/procedure requested. category (lab/procedure/imaging), status, requester
            // and performer (the facility) are kept. extension holds the emergency evacuation details,
            // including the incident scene the patient was picked up from.
            new ResourceRule("ServiceRequest", List.of("code", "extension")),

            // medicationCodeableConcept = the drug; dispenseRequest carries quantity and refills.
            // category (outpatient), status, intent and authoredOn are kept.
            new ResourceRule("MedicationRequest",
                    List.of("medicationCodeableConcept", "medicationReference", "dispenseRequest")),

            // code = the diagnosis itself. clinicalStatus/verificationStatus are kept.
            new ResourceRule("Condition", List.of("code")),

            // quantity and daysSupply = how much of the drug was dispensed. whenHandedOver is kept
            // (clinical time).
            new ResourceRule("MedicationDispense",
                    List.of("medicationCodeableConcept", "medicationReference", "quantity", "daysSupply")),

            // Consent carries no clinical finding — category/scope/status are what the Consent steps
            // match on. Listed with nothing to add so it is documented as reviewed.
            new ResourceRule("Consent", List.of()),

            // code = the procedure performed. status, performedDateTime and location are kept.
            new ResourceRule("Procedure", List.of("code", "complication")),

            // dosage = how much was administered; supportingInformation may point at clinical data.
            new ResourceRule("MedicationAdministration",
                    List.of("medicationCodeableConcept", "medicationReference",
                            "dosage", "supportingInformation")),

            // code = the allergen; reaction holds manifestation detail.
            new ResourceRule("AllergyIntolerance", List.of("code", "reaction")),

            // section = the summary itself: each section's narrative text and its clinical entries.
            // type (LOINC 60591-5 patient summary), status, date, custodian (the facility) and title
            // (the document kind and appointment number) are kept.
            new ResourceRule("Composition", List.of("section")),

            // code = the test reported; result points at the result Observations (redacted on their
            // own); presentedForm would carry the rendered report.
            new ResourceRule("DiagnosticReport", List.of("code", "presentedForm")),

            // Not received from tibERbu yet. Pre-declared so a new feed cannot leak the study or the
            // vaccine before anyone notices the resource type is unhandled.
            new ResourceRule("ImagingStudy", List.of("procedureCode", "series", "modality")),
            new ResourceRule("Immunization", List.of("vaccineCode"))
    );

    public ClinicalDataRedactionProperties {
        if (enabled == null) enabled = Boolean.TRUE;
        if (rules == null || rules.isEmpty()) rules = DEFAULT_RULES;
    }

    /** The fields applied to every resource, or empty if no {@value #ALL_RESOURCE_TYPES} rule exists. */
    public List<String> getAllResourceTypesFields() {
        return rules.stream()
                .filter(r -> ALL_RESOURCE_TYPES.equals(r.resourceType()))
                .findFirst()
                .map(ResourceRule::fields)
                .orElse(List.of());
    }

    /** The fields specific to {@code resourceType}, excluding {@link #getAllResourceTypesFields()}. */
    public List<String> fieldsFor(String resourceType) {
        List<String> found = new ArrayList<>();
        rules.stream()
                .filter(r -> !ALL_RESOURCE_TYPES.equals(r.resourceType()))
                .filter(r -> r.resourceType().equals(resourceType))
                .findFirst()
                .ifPresent(r -> found.addAll(r.fields()));
        return List.copyOf(found);
    }
}
