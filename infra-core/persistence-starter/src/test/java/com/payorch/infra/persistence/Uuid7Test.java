package com.payorch.infra.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Uuid7Test {

    @Test
    void generatesVersion7WithRfcVariant() {
        UUID id = Uuid7.generate();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void roundTripsThroughBytes() {
        UUID id = Uuid7.generate();

        assertThat(Uuid7.fromBytes(Uuid7.toBytes(id))).isEqualTo(id);
    }

    @Test
    void roundTripsThroughHex() {
        UUID id = Uuid7.generate();
        String hex = Uuid7.toHex(id);

        assertThat(hex).hasSize(32).matches("[0-9A-F]+");
        assertThat(Uuid7.fromHex(hex)).isEqualTo(id);
    }

    /**
     * The property the whole choice rests on: byte-wise comparison of the
     * stored form must agree with generation order. If this fails, the
     * clustered index no longer appends to its rightmost page and UUIDv7 has
     * bought nothing over v4 - silently, because every value is still unique
     * and every query still works.
     */
    @Test
    void byteFormSortsInGenerationOrder() throws InterruptedException {
        List<byte[]> encoded = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            encoded.add(Uuid7.toBytes(Uuid7.generate()));
            // uuid-creator keeps a monotonic counter within a millisecond, but
            // sleeping makes the assertion about the timestamp prefix rather
            // than about the counter.
            Thread.sleep(2);
        }

        for (int i = 1; i < encoded.size(); i++) {
            assertThat(java.util.Arrays.compareUnsigned(encoded.get(i - 1), encoded.get(i)))
                    .as("uuid %d must sort before uuid %d", i - 1, i)
                    .isNegative();
        }
    }

    @Test
    void rejectsWrongLengthByteArray() {
        assertThatThrownBy(() -> Uuid7.fromBytes(new byte[15]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("16 bytes");
    }

    @Test
    void nullsPassThrough() {
        assertThat(Uuid7.toBytes(null)).isNull();
        assertThat(Uuid7.fromBytes(null)).isNull();
        assertThat(Uuid7.parseOrNull(null)).isNull();
    }

    @Test
    void parseOrNullReturnsNullForGarbage() {
        assertThat(Uuid7.parseOrNull("not-a-uuid")).isNull();
        assertThat(Uuid7.parseOrNull("0192abcd-0000-7000-8000-000000000001")).isNotNull();
    }

    /**
     * Pins the encoding Hibernate's native UUID mapping also produces. JPA
     * entities use {@code @JdbcTypeCode(SqlTypes.BINARY)} while seed data and
     * JDBC parameters use {@link Uuid7#toBytes}; if the two ever disagreed, ids
     * written by one path would be invisible to the other.
     */
    @Test
    void byteFormIsBigEndianMostSignificantBitsFirst() {
        UUID id = new UUID(0x0192ABCD00007000L, 0x8000000000000001L);

        assertThat(Uuid7.toHex(id)).isEqualTo("0192ABCD000070008000000000000001");
        assertThat(Uuid7.toBytes(id)[0]).isEqualTo((byte) 0x01);
        assertThat(Uuid7.toBytes(id)[15]).isEqualTo((byte) 0x01);
    }
}
