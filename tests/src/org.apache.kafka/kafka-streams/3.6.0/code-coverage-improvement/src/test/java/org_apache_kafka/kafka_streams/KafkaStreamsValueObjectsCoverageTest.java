/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org_apache_kafka.kafka_streams;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyQueryMetadata;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Repartitioned;
import org.apache.kafka.streams.kstream.SessionWindows;
import org.apache.kafka.streams.kstream.SlidingWindows;
import org.apache.kafka.streams.kstream.UnlimitedWindows;
import org.apache.kafka.streams.processor.StreamPartitioner;
import org.apache.kafka.streams.processor.TaskMetadata;
import org.apache.kafka.streams.processor.To;
import org.apache.kafka.streams.state.HostInfo;
import org.apache.kafka.streams.state.QueryableStoreType;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.apache.kafka.streams.state.VersionedRecord;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class KafkaStreamsValueObjectsCoverageTest {

    @Test
    void storeQueryParametersPreserveSettingsAcrossImmutableUpdates() {
        QueryableStoreType<ReadOnlyKeyValueStore<String, Long>> type = QueryableStoreTypes.keyValueStore();
        StoreQueryParameters<ReadOnlyKeyValueStore<String, Long>> initial =
                StoreQueryParameters.fromNameAndType("counts", type);
        StoreQueryParameters<ReadOnlyKeyValueStore<String, Long>> configured =
                initial.withPartition(3).enableStaleStores();
        StoreQueryParameters<ReadOnlyKeyValueStore<String, Long>> equivalent =
                StoreQueryParameters.fromNameAndType("counts", type).withPartition(3).enableStaleStores();

        assertThat(initial.storeName()).isEqualTo("counts");
        assertThat(initial.queryableStoreType()).isSameAs(type);
        assertThat(initial.partition()).isNull();
        assertThat(initial.staleStoresEnabled()).isFalse();
        assertThat(configured.partition()).isEqualTo(3);
        assertThat(configured.staleStoresEnabled()).isTrue();
        assertThat(configured).isEqualTo(equivalent).hasSameHashCodeAs(equivalent);
        assertThat(configured).isNotEqualTo(initial).isNotEqualTo("counts");
        assertThat(configured.toString()).contains("partition=3", "staleStores=true", "storeName=counts");
    }

    @SuppressWarnings("deprecation")
    @Test
    void keyQueryMetadataExposesActiveAndStandbyLocations() {
        HostInfo active = new HostInfo("active.example", 9092);
        HostInfo standby = new HostInfo("standby.example", 9093);
        KeyQueryMetadata metadata = new KeyQueryMetadata(active, Set.of(standby), 4);
        KeyQueryMetadata equivalent = new KeyQueryMetadata(active, Set.of(standby), 4);

        assertThat(metadata.activeHost()).isEqualTo(active);
        assertThat(metadata.getActiveHost()).isEqualTo(active);
        assertThat(metadata.standbyHosts()).containsExactly(standby);
        assertThat(metadata.getStandbyHosts()).containsExactly(standby);
        assertThat(metadata.partition()).isEqualTo(4);
        assertThat(metadata.getPartition()).isEqualTo(4);
        assertThat(metadata).isEqualTo(equivalent).hasSameHashCodeAs(equivalent);
        assertThat(metadata).isNotEqualTo(KeyQueryMetadata.NOT_AVAILABLE).isNotEqualTo(null);
        assertThat(metadata.toString()).contains("active.example", "standby.example", "partition=4");
    }

    @SuppressWarnings("deprecation")
    @Test
    void sessionWindowsValidateAndRetainGapAndGrace() {
        SessionWindows noGrace = SessionWindows.ofInactivityGapWithNoGrace(Duration.ofSeconds(5));
        SessionWindows withGrace = SessionWindows.ofInactivityGapAndGrace(
                Duration.ofSeconds(5), Duration.ofSeconds(2));
        SessionWindows legacy = SessionWindows.with(Duration.ofSeconds(5)).grace(Duration.ofSeconds(2));

        assertThat(noGrace.inactivityGap()).isEqualTo(5_000L);
        assertThat(noGrace.gracePeriodMs()).isZero();
        assertThat(withGrace.inactivityGap()).isEqualTo(5_000L);
        assertThat(withGrace.gracePeriodMs()).isEqualTo(2_000L);
        assertThat(withGrace).isEqualTo(legacy).hasSameHashCodeAs(legacy);
        assertThat(withGrace).isNotEqualTo(noGrace).isNotEqualTo(null);
        assertThat(withGrace.toString()).contains("gapMs=5000", "graceMs=2000");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SessionWindows.ofInactivityGapWithNoGrace(Duration.ZERO));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SessionWindows.ofInactivityGapAndGrace(
                        Duration.ofSeconds(1), Duration.ofMillis(-1)));
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> noGrace.grace(Duration.ofSeconds(1)));
    }

    @SuppressWarnings("deprecation")
    @Test
    void slidingWindowsValidateAndRetainDifferenceAndGrace() {
        SlidingWindows noGrace = SlidingWindows.ofTimeDifferenceWithNoGrace(Duration.ofSeconds(5));
        SlidingWindows withGrace = SlidingWindows.ofTimeDifferenceAndGrace(
                Duration.ofSeconds(5), Duration.ofSeconds(2));
        SlidingWindows legacy = SlidingWindows.withTimeDifferenceAndGrace(
                Duration.ofSeconds(5), Duration.ofSeconds(2));

        assertThat(noGrace.timeDifferenceMs()).isEqualTo(5_000L);
        assertThat(noGrace.gracePeriodMs()).isZero();
        assertThat(withGrace.timeDifferenceMs()).isEqualTo(5_000L);
        assertThat(withGrace.gracePeriodMs()).isEqualTo(2_000L);
        assertThat(withGrace).isEqualTo(legacy).hasSameHashCodeAs(legacy);
        assertThat(withGrace).isNotEqualTo(noGrace).isNotEqualTo(null);
        assertThat(withGrace.toString()).contains("sizeMs=5000", "graceMs=2000");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SlidingWindows.ofTimeDifferenceWithNoGrace(Duration.ofMillis(-1)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SlidingWindows.ofTimeDifferenceAndGrace(
                        Duration.ZERO, Duration.ofMillis(-1)));
    }

    @Test
    void unlimitedWindowsCreateOnlyTheirLandmarkWindow() {
        UnlimitedWindows initial = UnlimitedWindows.of();
        UnlimitedWindows shifted = initial.startOn(Instant.ofEpochMilli(100));
        UnlimitedWindows equivalent = UnlimitedWindows.of().startOn(Instant.ofEpochMilli(100));

        assertThat(initial.windowsFor(0)).containsOnlyKeys(0L);
        assertThat(shifted.windowsFor(99)).isEmpty();
        assertThat(shifted.windowsFor(100)).containsOnlyKeys(100L);
        assertThat(shifted.size()).isEqualTo(Long.MAX_VALUE);
        assertThat(shifted.gracePeriodMs()).isZero();
        assertThat(shifted).isEqualTo(equivalent).hasSameHashCodeAs(equivalent);
        assertThat(shifted).isNotEqualTo(initial).isNotEqualTo(null);
        assertThat(shifted.toString()).contains("startMs=100");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> initial.startOn(Instant.ofEpochMilli(-1)));
    }

    @Test
    void repartitionConfigurationProducesNamedTopologyNodes() {
        Serde<String> stringSerde = Serdes.String();
        Serde<Long> longSerde = Serdes.Long();
        StreamPartitioner<String, Long> partitioner =
                (topic, key, value, partitions) -> 0;
        Repartitioned<String, Long> repartitioned = Repartitioned
                .with(stringSerde, longSerde)
                .withName("orders")
                .withNumberOfPartitions(2)
                .withKeySerde(stringSerde)
                .withValueSerde(longSerde)
                .withStreamPartitioner(partitioner);

        assertThat(Repartitioned.<String, Long>as("named")).isNotNull();
        assertThat(Repartitioned.<String, Long>numberOfPartitions(2)).isNotNull();
        assertThat(Repartitioned.<String, Long>streamPartitioner(partitioner)).isNotNull();

        StreamsBuilder builder = new StreamsBuilder();
        builder.<String, Long>stream("input").repartition(repartitioned).to("output");
        Topology topology = builder.build();

        assertThat(topology.describe().toString())
                .contains("orders-repartition-source", "orders-repartition-sink", "output");
    }

    @Test
    void legacyForwardingOptionsRepresentDestinationAndTimestamp() {
        To child = To.child("aggregate").withTimestamp(42L);
        To equivalent = To.child("aggregate").withTimestamp(42L);

        assertThat(child).isEqualTo(equivalent).isNotEqualTo(To.all()).isNotEqualTo(null);
        assertThat(child.toString()).contains("aggregate", "timestamp=42");
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(child::hashCode);
    }

    @Test
    void versionedRecordRequiresAValueAndUsesValueSemantics() {
        VersionedRecord<String> record = new VersionedRecord<>("value", 123L);
        VersionedRecord<String> equivalent = new VersionedRecord<>("value", 123L);

        assertThat(record.value()).isEqualTo("value");
        assertThat(record.timestamp()).isEqualTo(123L);
        assertThat(record).isEqualTo(equivalent).hasSameHashCodeAs(equivalent);
        assertThat(record).isNotEqualTo(new VersionedRecord<>("other", 123L)).isNotEqualTo(null);
        assertThat(record.toString()).isEqualTo("<value,123>");
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new VersionedRecord<>(null, 123L));
    }

    @SuppressWarnings("deprecation")
    @Test
    void taskMetadataExposesAnImmutableTaskSnapshot() {
        TopicPartition partition = new TopicPartition("input", 2);
        TaskMetadata metadata = new TaskMetadata(
                "1_2", Set.of(partition), Map.of(partition, 10L), Map.of(partition, 15L), Optional.of(99L));
        TaskMetadata equivalent = new TaskMetadata(
                "1_2", Set.of(partition), Map.of(partition, 11L), Map.of(partition, 16L), Optional.empty());

        assertThat(metadata.taskId()).isEqualTo("1_2");
        assertThat(metadata.topicPartitions()).containsExactly(partition);
        assertThat(metadata.committedOffsets()).containsEntry(partition, 10L);
        assertThat(metadata.endOffsets()).containsEntry(partition, 15L);
        assertThat(metadata.timeCurrentIdlingStarted()).contains(99L);
        assertThat(metadata).isEqualTo(equivalent).hasSameHashCodeAs(equivalent);
        assertThat(metadata).isNotEqualTo(null);
        assertThat(metadata.toString()).contains("taskId=1_2", "input-2", "Optional[99]");
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> metadata.topicPartitions().clear());
    }
}
