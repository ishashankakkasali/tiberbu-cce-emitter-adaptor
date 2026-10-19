package org.openphc.tiberbu.cce.emitter.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.hl7.fhir.r4.model.ResourceType;
import org.openphc.tiberbu.cce.emitter.fhir.ClinicalEventTimeExtractor;
import org.openphc.tiberbu.cce.emitter.fhir.ParentResourceTypeRank;
import org.openphc.tiberbu.cce.emitter.fhir.FhirResourceTypeDetector;
import org.openphc.tiberbu.cce.emitter.model.BundleEntryResult;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * Decides the order a bundle's entries are forwarded to the Collector in.
 *
 * <p>tibERbu lists a bundle's children before their parents (e.g. a
 * ServiceRequest, then its Encounter, then the EpisodeOfCare). Downstream,
 * events are processed in the order they reach the Collector, so entries are
 * forwarded by when they happened clinically instead:
 * <ol>
 *   <li><b>clinical time</b>, earliest first ({@link ClinicalEventTimeExtractor}
 *       — the same fields the Collector and Matcher use);</li>
 *   <li>on a tie, <b>parent first</b> ({@link ParentResourceTypeRank}):
 *       EpisodeOfCare, then Encounter, then everything else — e.g. an
 *       EpisodeOfCare and its Encounter that both carry only the visit date;</li>
 *   <li>then <b>bundle order</b>.</li>
 * </ol>
 * An entry without a clinical time goes after every entry that has one.
 * Only entries that are forwarded are sorted here; {@code InboundEventService}
 * keeps the facility-filtered and failed ones aside.
 *
 * <p><b>Example</b> — a tibERbu bundle listing, in this order, a
 * ServiceRequest (authored 13:56), its Encounter (started 13:53) and the
 * EpisodeOfCare (started 13:51) is forwarded as EpisodeOfCare, Encounter,
 * ServiceRequest. If all three carried only the date {@code 2026-10-08},
 * their clinical times would tie and the resource-type rank would give the same
 * order.
 *
 * <p>Only the forwarding order changes: {@code InboundEventService} puts the
 * outcomes back in bundle order for the response.
 */
@Component
public class BundleEntryForwardingOrder {

    /** Reads each resource's type — the same way the Collector and Matcher do. */
    private final FhirResourceTypeDetector resourceTypeDetector;

    /** Reads each resource's clinical time — the same fields the Collector and Matcher use. */
    private final ClinicalEventTimeExtractor clinicalEventTimeExtractor;

    /**
     * @param resourceTypeDetector       reads each entry's resource type; the
     *                                   copy of cce-common-util's detector
     * @param clinicalEventTimeExtractor reads each entry's clinical time; the
     *                                   copy of cce-common-util's extractor
     */
    public BundleEntryForwardingOrder(
            FhirResourceTypeDetector resourceTypeDetector, ClinicalEventTimeExtractor clinicalEventTimeExtractor) {
        this.resourceTypeDetector = resourceTypeDetector;
        this.clinicalEventTimeExtractor = clinicalEventTimeExtractor;
    }

    /**
     * Sorts one bundle's entries to forward into the order they are sent to
     * the Collector: clinical time, then parent first on a tie, then bundle
     * order (see the class javadoc). The input list is not modified.
     *
     * @param entriesToForward one bundle's ready-to-forward entries ({@link
     *                         BundleEntryResult#isReadyToForward()}), in bundle order
     * @return the same entries, in the order to forward them
     */
    public List<BundleEntryResult> sortIntoForwardingOrder(List<BundleEntryResult> entriesToForward) {
        return entriesToForward.stream()
                // resolve each entry's clinical time and resource-type rank once, not on every comparison
                .map(this::buildSortKey)
                .sorted(Comparator
                        // 1. earliest clinical time first; an entry with no clinical time goes after all timed ones
                        .comparing(SortKey::clinicalTime, Comparator.nullsLast(Comparator.naturalOrder()))
                        // 2. same clinical time: lower resource-type rank first — EpisodeOfCare (0), Encounter (1), the rest (2)
                        .thenComparingInt(sortKey -> sortKey.parentResourceTypeRank().rank())
                        // 3. still tied: keep the order the entries had in the bundle
                        .thenComparingInt(sortKey -> sortKey.bundleEntryResult().bundleEntryIndex()))
                // back to the entries themselves, now in forwarding order
                .map(SortKey::bundleEntryResult)
                .toList();
    }

    /**
     * Builds the {@link SortKey} of one entry: reads its clinical time and the
     * rank of its resource type from the resource it forwards.
     *
     * @param bundleEntryResult one ready-to-forward bundle entry
     * @return the entry with its clinical time and parent resource-type rank
     */
    private SortKey buildSortKey(BundleEntryResult bundleEntryResult) {
        // the CloudEvent's data is the entry's FHIR resource, exactly as it is forwarded
        JsonNode resource = bundleEntryResult.cloudEventToForward().data();
        // null for a type HAPI does not know: no clinical time, and ranked as any other resource
        ResourceType resourceType = resourceTypeDetector.detect(resource);
        return new SortKey(
                bundleEntryResult,
                clinicalEventTimeExtractor.extract(resourceType, resource),
                ParentResourceTypeRank.rankOfResourceType(resourceType));
    }

    /**
     * One entry paired with what it is sorted on, so both are resolved once
     * per sort rather than on every comparison.
     *
     * @param bundleEntryResult      the entry being sorted
     * @param clinicalTime           its clinical time in UTC, or {@code null} when it has none
     * @param parentResourceTypeRank the rank of its resource type, used when clinical times tie:
     *                               EpisodeOfCare, then Encounter, then every other resource
     */
    private record SortKey(
            BundleEntryResult bundleEntryResult, OffsetDateTime clinicalTime, ParentResourceTypeRank parentResourceTypeRank) {
    }
}
