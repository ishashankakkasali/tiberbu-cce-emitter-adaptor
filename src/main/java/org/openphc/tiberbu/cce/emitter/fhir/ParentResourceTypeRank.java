package org.openphc.tiberbu.cce.emitter.fhir;

import org.hl7.fhir.r4.model.ResourceType;

import java.util.Arrays;

/**
 * Where a FHIR resource type sits in the parent–child chain of a visit:
 * the EpisodeOfCare, then the Encounter under it, then every resource that
 * references them. {@code BundleEntryForwardingOrder} uses it as the
 * tie-break when two entries of one bundle have the same clinical time, so a
 * parent resource is forwarded before the resources that reference it.
 *
 * <p>Each constant states its {@link #rank()} explicitly: the lower rank is
 * forwarded first. To rank another parent type, add a constant with its
 * rank and move the ranks after it up; {@link #OTHER_RESOURCE} keeps the
 * highest rank.
 *
 * <p><b>Example</b> — an EpisodeOfCare, its Encounter and a ServiceRequest
 * that all carry only the date {@code 2026-10-08} rank as {@link
 * #EPISODE_OF_CARE}, {@link #ENCOUNTER}, {@link #OTHER_RESOURCE}, and are
 * forwarded in that order.
 */
public enum ParentResourceTypeRank {

    /** Rank 0: the episode every visit hangs under. */
    EPISODE_OF_CARE(ResourceType.EpisodeOfCare, 0),

    /** Rank 1: the visit, which orders, results and diagnoses point to. */
    ENCOUNTER(ResourceType.Encounter, 1),

    /** Rank 2: every other resource — they reference the parents above, never the reverse. */
    OTHER_RESOURCE(null, 2);

    /** The resource type this rank applies to; {@code null} for {@link #OTHER_RESOURCE}. */
    private final ResourceType parentResourceType;

    /** The forwarding rank; the lower rank is forwarded first. */
    private final int rank;

    ParentResourceTypeRank(ResourceType parentResourceType, int rank) {
        this.parentResourceType = parentResourceType;
        this.rank = rank;
    }

    /**
     * @return the forwarding rank of this resource type; the lower rank is forwarded first
     */
    public int rank() {
        return rank;
    }

    /**
     * Looks up the rank declared for a resource type — e.g. {@code
     * Encounter} → {@link #ENCOUNTER}. A type with no rank of its own,
     * or an unknown ({@code null}) type, gets {@link #OTHER_RESOURCE}.
     *
     * @param resourceType the entry's resource type; {@code null} when it is unknown
     * @return the rank of that resource type
     */
    public static ParentResourceTypeRank rankOfResourceType(ResourceType resourceType) {
        return Arrays.stream(values())
                .filter(parentResourceTypeRank -> parentResourceTypeRank.parentResourceType != null
                        && parentResourceTypeRank.parentResourceType == resourceType)
                .findFirst()
                .orElse(OTHER_RESOURCE);
    }
}
