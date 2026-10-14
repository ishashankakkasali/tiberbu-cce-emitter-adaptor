package org.openphc.tiberbu.cce.emitter.cloudevents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Generates the CloudEvents {@code id} for one bundle entry from the entry's
 * FHIR resource content, so an identical resource sent again gets the same id
 * and the Collector drops it as a duplicate ({@code cloudevents_id} +
 * {@code source}).
 *
 * <p><b>The key.</b> The whole resource, written as
 * canonical JSON (object keys sorted at every level, no whitespace), hashed
 * with SHA-256; the hex digest is the name of a UUID v5 (SHA-1, RFC 4122
 * § 4.3, DNS namespace). Same content always produces the same id; any change
 * to any field (status, verificationStatus, type, period, text, …)
 * produces a new one. {@code resourceType} and {@code id} are part of the
 * content, so two different resources never share an id.
 *
 * <p><b>Why content, not the request.</b> tibERbu re-sends unchanged
 * resources many times (double-sends, a nightly re-send, and a re-send loop
 * every 10 minutes for some patients), each time in a new bundle with a new
 * envelope {@code meta.traceId}. A key built from the request ({@code traceId},
 * {@code bundleId}, or a header — tibERbu sends none) gives every copy a new
 * id, so none is caught.
 */
@Component
public class EventIdGenerator {

    private static final Logger log = LoggerFactory.getLogger(EventIdGenerator.class);

    /**
     * UUID v5 namespace — the well-known DNS namespace, RFC 4122 Appendix C.
     * Any fixed namespace works for our purposes (we only need
     * self-consistency, not interoperability with DNS-based UUIDs elsewhere);
     * this one is simply the standard, recognizable choice.
     */
    private static final UUID NAMESPACE_DNS = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

    /**
     * Writes a JSON tree with every object's keys sorted, so the same content
     * gives the same bytes whatever key order the sender used. Array order is
     * kept: in FHIR it is meaningful.
     */
    private static final ObjectMapper CANONICAL_JSON_WRITER = JsonMapper.builder()
            .enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED)
            .build();

    /**
     * Generates the CloudEvents {@code id} for one bundle entry.
     *
     * <p>Example — two identical copies of the same {@code Consent} ({@code
     * status "proposed"}), sent in different bundles: both return the same id.
     * The same {@code Consent} later sent with {@code status "active"} returns
     * a different id.
     *
     * @param resource the entry's FHIR resource, as parsed for the CloudEvent's
     *                 {@code data} field; not modified
     * @return a UUID string, determined by the resource's content alone
     */
    public String generate(JsonNode resource) {
        String contentSha256 = sha256Hex(canonicalJson(resource));
        String eventId = nameUuidFromNamespaceAndString(NAMESPACE_DNS, contentSha256).toString();
        log.debug("Generated event id '{}' from content sha256 '{}'", eventId, contentSha256);
        return eventId;
    }

    /** The resource as canonical JSON; the resource itself is not modified. */
    private static String canonicalJson(JsonNode resource) {
        try {
            return CANONICAL_JSON_WRITER.writeValueAsString(resource);
        } catch (JsonProcessingException unwritableTree) {
            // a tree that was just parsed from JSON can always be written back
            throw new IllegalStateException("Could not write FHIR resource as canonical JSON", unwritableTree);
        }
    }

    private static String sha256Hex(String canonicalJson) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossibleOnAnyJvm) {
            // SHA-256 is guaranteed present by the Java Cryptography Architecture spec.
            throw new IllegalStateException("SHA-256 algorithm not available", impossibleOnAnyJvm);
        }
    }

    /**
     * Implements UUID v5 generation per RFC 4122 § 4.3: combines a namespace
     * UUID with a name string, hashes with SHA-1, and sets the version (5) and
     * variant (RFC 4122) bits on the resulting digest.
     */
    static UUID nameUuidFromNamespaceAndString(UUID namespace, String name) {
        try {
            MessageDigest sha1Digest = MessageDigest.getInstance("SHA-1");
            sha1Digest.update(toBytes(namespace));
            sha1Digest.update(name.getBytes(StandardCharsets.UTF_8));
            byte[] hash = sha1Digest.digest();

            // Set version 5 (bits 4-7 of byte 6)
            hash[6] = (byte) ((hash[6] & 0x0F) | 0x50);
            // Set variant to RFC 4122 (bits 6-7 of byte 8)
            hash[8] = (byte) ((hash[8] & 0x3F) | 0x80);

            long mostSignificantBits = 0;
            for (int byteIndex = 0; byteIndex < 8; byteIndex++) {
                mostSignificantBits = (mostSignificantBits << 8) | (hash[byteIndex] & 0xFF);
            }
            long leastSignificantBits = 0;
            for (int byteIndex = 8; byteIndex < 16; byteIndex++) {
                leastSignificantBits = (leastSignificantBits << 8) | (hash[byteIndex] & 0xFF);
            }

            return new UUID(mostSignificantBits, leastSignificantBits);
        } catch (NoSuchAlgorithmException impossibleOnAnyJvm) {
            // SHA-1 is guaranteed present by the Java Cryptography Architecture spec.
            throw new IllegalStateException("SHA-1 algorithm not available", impossibleOnAnyJvm);
        }
    }

    private static byte[] toBytes(UUID uuid) {
        long mostSignificantBits = uuid.getMostSignificantBits();
        long leastSignificantBits = uuid.getLeastSignificantBits();
        byte[] bytes = new byte[16];
        for (int byteIndex = 0; byteIndex < 8; byteIndex++) {
            bytes[byteIndex] = (byte) (mostSignificantBits >>> (8 * (7 - byteIndex)));
        }
        for (int byteIndex = 8; byteIndex < 16; byteIndex++) {
            bytes[byteIndex] = (byte) (leastSignificantBits >>> (8 * (15 - byteIndex)));
        }
        return bytes;
    }
}
