package org.openphc.tiberbu.cce.emitter.fhir;

import org.hl7.fhir.r4.model.ResourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the tie-break rank: EpisodeOfCare, then Encounter, then every other
 * resource, with the explicit ranks 0, 1 and 2.
 */
class ParentResourceTypeRankTest {

    @Test
    @DisplayName("EpisodeOfCare and Encounter get their own rank")
    void parentTypesGetTheirOwnRank() {
        assertThat(ParentResourceTypeRank.rankOfResourceType(ResourceType.EpisodeOfCare)).isEqualTo(ParentResourceTypeRank.EPISODE_OF_CARE);
        assertThat(ParentResourceTypeRank.rankOfResourceType(ResourceType.Encounter)).isEqualTo(ParentResourceTypeRank.ENCOUNTER);
    }

    @Test
    @DisplayName("any other resource type, or an unknown one, ranks as any other resource")
    void everyOtherTypeRanksAsOtherResource() {
        assertThat(ParentResourceTypeRank.rankOfResourceType(ResourceType.ServiceRequest)).isEqualTo(ParentResourceTypeRank.OTHER_RESOURCE);
        assertThat(ParentResourceTypeRank.rankOfResourceType(ResourceType.Composition)).isEqualTo(ParentResourceTypeRank.OTHER_RESOURCE);
        assertThat(ParentResourceTypeRank.rankOfResourceType(null)).isEqualTo(ParentResourceTypeRank.OTHER_RESOURCE);
    }

    @Test
    @DisplayName("each resource type has its explicit rank: EpisodeOfCare 0, Encounter 1, every other resource 2")
    void eachResourceTypeHasItsExplicitRank() {
        assertThat(ParentResourceTypeRank.EPISODE_OF_CARE.rank()).isZero();
        assertThat(ParentResourceTypeRank.ENCOUNTER.rank()).isEqualTo(1);
        assertThat(ParentResourceTypeRank.OTHER_RESOURCE.rank()).isEqualTo(2);
    }
}
