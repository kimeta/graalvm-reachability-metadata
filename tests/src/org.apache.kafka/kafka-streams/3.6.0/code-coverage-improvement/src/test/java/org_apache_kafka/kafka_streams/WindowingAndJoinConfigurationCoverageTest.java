/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org_apache_kafka.kafka_streams;

import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.kstream.JoinWindows;
import org.apache.kafka.streams.kstream.Joined;
import org.apache.kafka.streams.kstream.StreamJoined;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.kstream.internals.TimeWindow;
import org.apache.kafka.streams.state.Stores;
import org.apache.kafka.streams.state.WindowBytesStoreSupplier;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class WindowingAndJoinConfigurationCoverageTest {

    @SuppressWarnings("deprecation")
    @Test
    void timeWindowsAssignRecordsToTumblingAndHoppingWindows() {
        TimeWindows tumbling = TimeWindows.ofSizeAndGrace(Duration.ofSeconds(10), Duration.ofSeconds(2));
        TimeWindows hopping = tumbling.advanceBy(Duration.ofSeconds(4));
        TimeWindows equivalent = TimeWindows.ofSizeAndGrace(Duration.ofSeconds(10), Duration.ofSeconds(2))
                .advanceBy(Duration.ofSeconds(4));

        assertThat(tumbling.windowsFor(12_000L)).containsOnlyKeys(10_000L);
        assertThat(hopping.windowsFor(12_000L)).containsOnlyKeys(4_000L, 8_000L, 12_000L);
        assertThat(hopping.windowsFor(-1L)).isEmpty();
        assertThat(hopping.size()).isEqualTo(10_000L);
        assertThat(hopping.gracePeriodMs()).isEqualTo(2_000L);
        assertThat(hopping).isEqualTo(equivalent).hasSameHashCodeAs(equivalent);
        assertThat(hopping).isNotEqualTo(tumbling).isNotEqualTo(null).isNotEqualTo("window");
        assertThat(hopping.toString()).contains("sizeMs=10000", "advanceMs=4000", "graceMs=2000");

        TimeWindows noGrace = TimeWindows.ofSizeWithNoGrace(Duration.ofSeconds(1));
        assertThat(noGrace.gracePeriodMs()).isZero();
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> noGrace.grace(Duration.ofMillis(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> hopping.advanceBy(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> hopping.advanceBy(Duration.ofSeconds(11)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> TimeWindows.ofSizeAndGrace(Duration.ZERO, Duration.ZERO));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> TimeWindows.ofSizeAndGrace(Duration.ofSeconds(1), Duration.ofMillis(-1)));

        TimeWindows legacy = TimeWindows.of(Duration.ofSeconds(10)).grace(Duration.ofSeconds(3));
        assertThat(legacy.gracePeriodMs()).isEqualTo(3_000L);
    }

    @SuppressWarnings("deprecation")
    @Test
    void joinWindowsRetainAsymmetricBoundsAndGrace() {
        JoinWindows symmetric = JoinWindows.ofTimeDifferenceAndGrace(
                Duration.ofSeconds(5), Duration.ofSeconds(2));
        JoinWindows asymmetric = symmetric.before(Duration.ofSeconds(3)).after(Duration.ofSeconds(7));
        JoinWindows equivalent = JoinWindows.ofTimeDifferenceAndGrace(
                Duration.ofSeconds(5), Duration.ofSeconds(2))
                .before(Duration.ofSeconds(3)).after(Duration.ofSeconds(7));

        assertThat(asymmetric.beforeMs).isEqualTo(3_000L);
        assertThat(asymmetric.afterMs).isEqualTo(7_000L);
        assertThat(asymmetric.size()).isEqualTo(10_000L);
        assertThat(asymmetric.gracePeriodMs()).isEqualTo(2_000L);
        assertThat(asymmetric).isEqualTo(equivalent).hasSameHashCodeAs(equivalent);
        assertThat(asymmetric).isNotEqualTo(symmetric).isNotEqualTo(null).isNotEqualTo("window");
        assertThat(asymmetric.toString()).contains("beforeMs=3000", "afterMs=7000", "graceMs=2000");
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> asymmetric.windowsFor(1L));

        JoinWindows noGrace = JoinWindows.ofTimeDifferenceWithNoGrace(Duration.ofSeconds(1));
        assertThat(noGrace.gracePeriodMs()).isZero();
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> noGrace.grace(Duration.ofMillis(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> noGrace.before(Duration.ofSeconds(-2)));

        JoinWindows legacy = JoinWindows.of(Duration.ofSeconds(1)).grace(Duration.ofSeconds(3));
        assertThat(legacy.gracePeriodMs()).isEqualTo(3_000L);
        InspectableJoinWindows copy = new InspectableJoinWindows(legacy);
        assertThat(copy.beforeMs).isEqualTo(legacy.beforeMs);
        assertThat(copy.afterMs).isEqualTo(legacy.afterMs);
        assertThat(copy.gracePeriodMs()).isEqualTo(legacy.gracePeriodMs());
    }

    @Test
    void timeWindowsAndWindowedKeysUseHalfOpenIntervalSemantics() {
        TimeWindow first = new TimeWindow(10L, 20L);
        TimeWindow overlapping = new TimeWindow(19L, 30L);
        TimeWindow adjacent = new TimeWindow(20L, 30L);
        Windowed<String> windowed = new Windowed<>("order-7", first);
        Windowed<String> equivalent = new Windowed<>("order-7", new TimeWindow(10L, 20L));

        assertThat(first.overlap(overlapping)).isTrue();
        assertThat(first.overlap(adjacent)).isFalse();
        assertThat(first.startTime()).isEqualTo(Instant.ofEpochMilli(10L));
        assertThat(first.endTime()).isEqualTo(Instant.ofEpochMilli(20L));
        assertThat(windowed.key()).isEqualTo("order-7");
        assertThat(windowed.window()).isEqualTo(first);
        assertThat(windowed).isEqualTo(equivalent).hasSameHashCodeAs(equivalent);
        assertThat(windowed).isNotEqualTo(new Windowed<>("order-8", first)).isNotEqualTo("order-7");
        assertThat(windowed.toString()).isEqualTo("[order-7@10/20]");
        assertThatIllegalArgumentException().isThrownBy(() -> new TimeWindow(10L, 10L));
    }

    @Test
    void joinedConfigurationUpdatesAreImmutableAndComposable() {
        Serde<String> keySerde = Serdes.String();
        Serde<Long> valueSerde = Serdes.Long();
        Serde<Double> otherSerde = Serdes.Double();
        Joined<String, Long, Double> initial = Joined.with(keySerde, valueSerde, otherSerde);
        Joined<String, Long, Double> configured = initial
                .withName("lookup")
                .withGracePeriod(Duration.ofSeconds(2));
        InspectableJoined<String, Long, Double> inspected = new InspectableJoined<>(configured);

        assertThat(initial.gracePeriod()).isNull();
        assertThat(configured.keySerde()).isSameAs(keySerde);
        assertThat(configured.valueSerde()).isSameAs(valueSerde);
        assertThat(configured.otherValueSerde()).isSameAs(otherSerde);
        assertThat(configured.gracePeriod()).isEqualTo(Duration.ofSeconds(2));
        assertThat(inspected.name()).isEqualTo("lookup");

        assertThat(new InspectableJoined<>(Joined.with(keySerde, valueSerde, otherSerde, "four")).name())
                .isEqualTo("four");
        assertThat(new InspectableJoined<>(Joined.with(
                keySerde, valueSerde, otherSerde, "five", Duration.ofSeconds(5))).name())
                .isEqualTo("five");
        assertThat(Joined.<String, Long, Double>keySerde(keySerde).keySerde()).isSameAs(keySerde);
        assertThat(Joined.<String, Long, Double>valueSerde(valueSerde).valueSerde()).isSameAs(valueSerde);
        assertThat(Joined.<String, Long, Double>otherValueSerde(otherSerde).otherValueSerde()).isSameAs(otherSerde);
        assertThat(new InspectableJoined<>(Joined.<String, Long, Double>as("named")).name()).isEqualTo("named");
        assertThat(initial.withKeySerde(Serdes.String()).keySerde()).isNotNull();
        assertThat(initial.withValueSerde(Serdes.Long()).valueSerde()).isNotNull();
        assertThat(initial.withOtherValueSerde(Serdes.Double()).otherValueSerde()).isNotNull();
    }

    @Test
    void streamJoinedConfigurationCarriesStoresSerdesAndLoggingPolicy() {
        Serde<String> keySerde = Serdes.String();
        Serde<Long> valueSerde = Serdes.Long();
        Serde<Double> otherSerde = Serdes.Double();
        WindowBytesStoreSupplier leftStore = Stores.inMemoryWindowStore(
                "left", Duration.ofMinutes(2), Duration.ofSeconds(10), false);
        WindowBytesStoreSupplier rightStore = Stores.inMemoryWindowStore(
                "right", Duration.ofMinutes(2), Duration.ofSeconds(10), false);

        StreamJoined<String, Long, Double> configured = StreamJoined
                .with(keySerde, valueSerde, otherSerde)
                .withName("processor")
                .withStoreName("joined")
                .withThisStoreSupplier(leftStore)
                .withOtherStoreSupplier(rightStore)
                .withLoggingEnabled(Map.of("cleanup.policy", "compact"));
        InspectableStreamJoined<String, Long, Double> inspected = new InspectableStreamJoined<>(configured);

        assertThat(inspected.keySerde()).isSameAs(keySerde);
        assertThat(inspected.valueSerde()).isSameAs(valueSerde);
        assertThat(inspected.otherValueSerde()).isSameAs(otherSerde);
        assertThat(inspected.leftStore()).isSameAs(leftStore);
        assertThat(inspected.rightStore()).isSameAs(rightStore);
        assertThat(inspected.name()).isEqualTo("processor");
        assertThat(inspected.storeName()).isEqualTo("joined");
        assertThat(inspected.loggingEnabled()).isTrue();
        assertThat(inspected.topicConfig()).containsEntry("cleanup.policy", "compact");
        assertThat(configured.toString()).contains("processor", "joined", "cleanup.policy", "loggingEnabled=true");

        assertThat(new InspectableStreamJoined<>(StreamJoined.<String, Long, Double>with(leftStore, rightStore))
                .leftStore()).isSameAs(leftStore);
        assertThat(new InspectableStreamJoined<>(StreamJoined.<String, Long, Double>as("store")).storeName())
                .isEqualTo("store");
        assertThat(new InspectableStreamJoined<>(configured.withLoggingDisabled()).loggingEnabled()).isFalse();
        assertThat(new InspectableStreamJoined<>(configured.withKeySerde(Serdes.String())).keySerde()).isNotNull();
        assertThat(new InspectableStreamJoined<>(configured.withValueSerde(Serdes.Long())).valueSerde()).isNotNull();
        assertThat(new InspectableStreamJoined<>(configured.withOtherValueSerde(Serdes.Double())).otherValueSerde())
                .isNotNull();
    }

    private static final class InspectableJoinWindows extends JoinWindows {
        private InspectableJoinWindows(final JoinWindows windows) {
            super(windows);
        }
    }

    private static final class InspectableJoined<K, V, VO> extends Joined<K, V, VO> {
        private InspectableJoined(final Joined<K, V, VO> joined) {
            super(joined);
        }

        private String name() {
            return name;
        }
    }

    private static final class InspectableStreamJoined<K, V, VO> extends StreamJoined<K, V, VO> {
        private InspectableStreamJoined(final StreamJoined<K, V, VO> joined) {
            super(joined);
        }

        private Serde<K> keySerde() {
            return keySerde;
        }

        private Serde<V> valueSerde() {
            return valueSerde;
        }

        private Serde<VO> otherValueSerde() {
            return otherValueSerde;
        }

        private WindowBytesStoreSupplier leftStore() {
            return thisStoreSupplier;
        }

        private WindowBytesStoreSupplier rightStore() {
            return otherStoreSupplier;
        }

        private String name() {
            return name;
        }

        private String storeName() {
            return storeName;
        }

        private boolean loggingEnabled() {
            return loggingEnabled;
        }

        private Map<String, String> topicConfig() {
            return topicConfig;
        }
    }
}
