/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org_apache_kafka.kafka_streams;

import org.apache.kafka.streams.state.internals.Murmur3;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class Murmur3CoverageTest {

    @Test
    void producesCanonicalMurmur3Vectors() {
        byte[] hello = "hello".getBytes(StandardCharsets.UTF_8);

        assertThat(Murmur3.hash32(hello, hello.length, 0)).isEqualTo(613_153_351);
        assertThat(Murmur3.hash128(hello, 0, hello.length, 0))
                .containsExactly(-3_758_069_500_696_749_310L, 6_565_844_092_913_065_241L);
    }

    @Test
    void arrayOverloadsRespectOffsetsLengthsAndSeeds() {
        byte[] data = new byte[34];
        for (int index = 0; index < data.length; index++) {
            data[index] = (byte) (index * 7 + 3);
        }

        for (int length = 0; length <= 32; length++) {
            byte[] slice = Arrays.copyOfRange(data, 1, length + 1);

            assertThat(Murmur3.hash32(data, 1, length, Murmur3.DEFAULT_SEED))
                    .isEqualTo(Murmur3.hash32(slice));
            assertThat(Murmur3.hash32(slice, length)).isEqualTo(Murmur3.hash32(slice));
            assertThat(Murmur3.hash32(slice, length, 17))
                    .isEqualTo(Murmur3.hash32(slice, 0, length, 17));
            assertThat(Murmur3.hash64(data, 1, length))
                    .isEqualTo(Murmur3.hash64(slice));
            assertThat(Murmur3.hash64(data, 1, length, 17))
                    .isNotEqualTo(Murmur3.hash64(data, 1, length, 18));
            assertThat(Murmur3.hash128(data, 1, length, 17))
                    .containsExactly(Murmur3.hash128(slice, 0, length, 17));
        }
    }

    @Test
    void incrementalHashMatchesOneShotHashAcrossEverySplit() {
        byte[] data = "incremental hashing must preserve partial words"
                .getBytes(StandardCharsets.UTF_8);

        for (int split = 0; split <= data.length; split++) {
            Murmur3.IncrementalHash32 incremental = new Murmur3.IncrementalHash32();
            incremental.start(29);
            incremental.add(data, 0, split);
            incremental.add(data, split, data.length - split);

            assertThat(incremental.end())
                    .as("split at byte %s", split)
                    .isEqualTo(Murmur3.hash32(data, 0, data.length, 29));
        }
    }

    @Test
    void primitiveOverloadsUseStableTypeSpecificEncodings() {
        assertThat(Murmur3.hash32(42L)).isEqualTo(Murmur3.hash32(42L, Murmur3.DEFAULT_SEED));
        assertThat(Murmur3.hash32(42L, 84L))
                .isEqualTo(Murmur3.hash32(42L, 84L, Murmur3.DEFAULT_SEED));
        assertThat(Murmur3.hash64((short) 42)).isEqualTo(5_976_052_811_867_784_168L);
        assertThat(Murmur3.hash64(42)).isEqualTo(8_831_750_612_535_747_064L);
        assertThat(Murmur3.hash64(42L)).isEqualTo(-9_051_690_767_330_425_106L);
    }
}
