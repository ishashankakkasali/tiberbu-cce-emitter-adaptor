package org.openphc.tiberbu.cce.emitter.model;

import java.time.OffsetDateTime;

/**
 * Per-entry metadata gathered while adapting one bundle entry into a CloudEvent
 * — everything {@code CloudEventEnvelopeBuilder} needs that isn't already
 * carried on the entry's own FHIR resource.
 *
 * <p>Built once per bundle entry (not once per request), since {@link
 * #facilityId} and {@link #facilityName} are entry-specific even though
 * {@link #sourceIdentifier}, {@link #correlationId}, {@link #eventTime} and
 * {@link #sourcePath} are the same for every entry in one bundle.
 *
 * <p>There is deliberately no {@code sourceEventId} field. tibERbu sends no
 * per-event header, so the CloudEvents {@code id} is instead derived from the
 * entry's own resource content — see {@code EventIdGenerator}. The output
 * CloudEvent's {@code sourceeventid} extension attribute stays {@code null}
 * and is omitted from the JSON, not backed by any field here.
 *
 * @param sourceIdentifier the configured {@code cce.emitter.source} (e.g.
 *                          {@code "tiberbu"}), stamped as the CloudEvents
 *                          {@code source} attribute
 * @param facilityId        this entry's resolved facility ID from {@code
 *                          FacilityIdExtractor}; {@code null} when the
 *                          resource has no {@code organization} reference
 * @param facilityName      this entry's resolved facility display name from
 *                          {@code FacilityIdExtractor}, read from the same
 *                          organization reference's {@code display} field;
 *                          {@code null} whenever {@link #facilityId} is, and
 *                          also when the reference carries no display
 * @param correlationId     an adaptor-generated UUID, never {@code null}
 * @param eventTime         the adaptor's own processing time in UTC — never
 *                          {@code meta.timestamp} from the inbound envelope
 * @param sourcePath        the inbound request URI path (e.g. {@code
 *                          "/inbound"}), used only for logging/metrics context
 */
public record SourceMetadata(
        String sourceIdentifier,
        String facilityId,
        String facilityName,
        String correlationId,
        OffsetDateTime eventTime,
        String sourcePath) {
}
