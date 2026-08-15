package com.payorch.infra.persistence;

import java.nio.ByteBuffer;
import java.util.UUID;

import com.github.f4b6a3.uuid.UuidCreator;

/**
 * UUIDv7 identifiers and their 16-byte wire/storage form.
 *
 * <p>Every primary key in this system is a UUIDv7 stored as {@code BINARY(16)}.
 * Both halves of that sentence are load-bearing, and both are InnoDB-specific.
 *
 * <p><strong>v7 rather than v4.</strong> In InnoDB the primary key <em>is</em>
 * the clustered index - rows are physically stored in primary-key order. A v4
 * key is uniformly random, so every insert lands in a random page: pages split,
 * fill factor drops towards 50%, and the buffer pool has to hold the whole index
 * rather than its hot right edge. A v7 key carries a millisecond timestamp in
 * its leading 48 bits, so inserts append to the rightmost page the way an
 * auto-increment does, while still being generated client-side and safe to
 * expose.
 *
 * <p><strong>{@code BINARY(16)} rather than {@code CHAR(36)}.</strong> 16 bytes
 * against 36, and in InnoDB every secondary index carries a full copy of the
 * primary key as its row pointer. The saving is therefore multiplied by the
 * number of secondary indexes on the table, not paid once - which is why this
 * choice is worth making before phase 8 starts measuring index sizes.
 *
 * <p>The cost is that a raw {@code SELECT} shows unreadable bytes. Use
 * {@code HEX(id)} in ad-hoc queries, and {@link #fromHex(String)} to get back.
 *
 * <h2>Mapping a UUID to BINARY(16) in JPA</h2>
 *
 * Annotate the field, do not write a converter:
 *
 * <pre>{@code
 * @Id
 * @JdbcTypeCode(SqlTypes.BINARY)
 * @Column(name = "id", columnDefinition = "BINARY(16)")
 * private UUID id;
 * }</pre>
 *
 * <p>An {@code AttributeConverter<UUID, byte[]>} is the first thing most people
 * reach for, and Hibernate 7 rejects it on an {@code @Id} field with
 * <em>"'AttributeConverter' not allowed for attribute 'id'"</em>. Hibernate's
 * own UUID handling produces the same big-endian 16 bytes this class does, so
 * the two forms are interchangeable on the wire - which is what lets
 * {@link #toBytes} be used for JDBC parameters and seed data while JPA entities
 * use the annotation.
 */
public final class Uuid7 {

    private static final int BYTES = 16;

    private Uuid7() {
    }

    /** A fresh time-ordered (v7) UUID. */
    public static UUID generate() {
        return UuidCreator.getTimeOrderedEpoch();
    }

    /**
     * Big-endian 16-byte form: most-significant bits first.
     *
     * <p>Byte order matters more than it looks. MySQL compares {@code BINARY}
     * values byte by byte, so big-endian is what preserves the timestamp
     * ordering that made v7 worth choosing. Writing the little-endian form
     * would store correct values in an index that no longer sorts by time, and
     * nothing would fail - the locality benefit would simply be absent.
     */
    public static byte[] toBytes(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        return ByteBuffer.allocate(BYTES)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }

    public static UUID fromBytes(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        if (bytes.length != BYTES) {
            throw new IllegalArgumentException(
                    "expected " + BYTES + " bytes for a UUID, got " + bytes.length);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    /** The form that pairs with MySQL's {@code HEX(id)} and {@code UNHEX(...)}. */
    public static String toHex(UUID uuid) {
        return java.util.HexFormat.of().formatHex(toBytes(uuid)).toUpperCase(java.util.Locale.ROOT);
    }

    public static UUID fromHex(String hex) {
        return fromBytes(java.util.HexFormat.of().parseHex(hex));
    }

    /**
     * Parses a canonical UUID string, returning {@code null} rather than
     * throwing when the input is not one.
     *
     * <p>Path variables are caller-controlled. A malformed id is a 404, not a
     * 500, and this keeps that decision at the call site instead of in an
     * exception handler.
     */
    public static UUID parseOrNull(String canonical) {
        if (canonical == null) {
            return null;
        }
        try {
            return UUID.fromString(canonical);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
