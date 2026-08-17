/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org_apache_kafka.kafka_streams;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.streams.kstream.Suppressed;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.kstream.internals.suppress.BufferConfigInternal;
import org.apache.kafka.streams.kstream.internals.suppress.EagerBufferConfigImpl;
import org.apache.kafka.streams.kstream.internals.suppress.FinalResultsSuppressionBuilder;
import org.apache.kafka.streams.kstream.internals.suppress.StrictBufferConfigImpl;
import org.apache.kafka.streams.kstream.internals.suppress.SuppressedInternal;
import org.apache.kafka.streams.processor.TaskMetadata;
import org.apache.kafka.streams.processor.ThreadMetadata;
import org.apache.kafka.streams.state.HostInfo;
import org.apache.kafka.streams.state.StreamsMetadata;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class LegacyMetadataAndSuppressionCoverageTest {

    @SuppressWarnings("deprecation")
    @Test
    void legacyThreadMetadataExposesAnImmutableRuntimeSnapshot() {
        TopicPartition partition = new TopicPartition("orders", 1);
        TaskMetadata activeTask = new TaskMetadata(
                "0_1", Set.of(partition), Map.of(partition, 4L), Map.of(partition, 9L), Optional.empty());
        ThreadMetadata metadata = new ThreadMetadata(
                "stream-thread-1",
                "RUNNING",
                "consumer-1",
                "restore-1",
                Set.of("producer-1"),
                "admin-1",
                Set.of(activeTask),
                Set.of());
        ThreadMetadata equivalent = new ThreadMetadata(
                "stream-thread-1",
                "RUNNING",
                "consumer-1",
                "restore-1",
                Set.of("producer-1"),
                "admin-1",
                Set.of(activeTask),
                Set.of());

        assertThat(metadata.threadName()).isEqualTo("stream-thread-1");
        assertThat(metadata.threadState()).isEqualTo("RUNNING");
        assertThat(metadata.consumerClientId()).isEqualTo("consumer-1");
        assertThat(metadata.restoreConsumerClientId()).isEqualTo("restore-1");
        assertThat(metadata.producerClientIds()).containsExactly("producer-1");
        assertThat(metadata.adminClientId()).isEqualTo("admin-1");
        assertThat(metadata.activeTasks()).containsExactly(activeTask);
        assertThat(metadata.standbyTasks()).isEmpty();
        assertThat(metadata).isEqualTo(equivalent).hasSameHashCodeAs(equivalent);
        assertThat(metadata).isNotEqualTo(null).isNotEqualTo("stream-thread-1");
        assertThat(metadata.toString()).contains("stream-thread-1", "RUNNING", "consumer-1", "0_1");
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> metadata.activeTasks().clear());
    }

    @SuppressWarnings("deprecation")
    @Test
    void legacyStreamsMetadataDescribesActiveAndStandbyResources() {
        HostInfo host = new HostInfo("streams.example", 8080);
        TopicPartition activePartition = new TopicPartition("orders", 0);
        TopicPartition standbyPartition = new TopicPartition("orders", 1);
        StreamsMetadata metadata = new StreamsMetadata(
                host,
                Set.of("orders-store"),
                Set.of(activePartition),
                Set.of("standby-store"),
                Set.of(standbyPartition));
        StreamsMetadata equivalent = new StreamsMetadata(
                host,
                Set.of("orders-store"),
                Set.of(activePartition),
                Set.of("standby-store"),
                Set.of(standbyPartition));

        assertThat(metadata.hostInfo()).isEqualTo(host);
        assertThat(metadata.host()).isEqualTo("streams.example");
        assertThat(metadata.port()).isEqualTo(8080);
        assertThat(metadata.stateStoreNames()).containsExactly("orders-store");
        assertThat(metadata.topicPartitions()).containsExactly(activePartition);
        assertThat(metadata.standbyStateStoreNames()).containsExactly("standby-store");
        assertThat(metadata.standbyTopicPartitions()).containsExactly(standbyPartition);
        assertThat(metadata).isEqualTo(equivalent).hasSameHashCodeAs(equivalent);
        assertThat(metadata).isNotEqualTo(StreamsMetadata.NOT_AVAILABLE).isNotEqualTo(null);
        assertThat(metadata.toString()).contains("streams.example", "orders-store", "standby-store");
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> metadata.topicPartitions().clear());
    }

    @Test
    void bufferConfigurationsPreserveLimitsStrategiesAndLogging() {
        Suppressed.EagerBufferConfig eagerConfig = Suppressed.BufferConfig.maxRecords(10L)
                .withMaxBytes(1_024L)
                .withLoggingEnabled(Map.of("cleanup.policy", "compact"));
        EagerBufferConfigImpl eager = (EagerBufferConfigImpl) eagerConfig;
        EagerBufferConfigImpl equivalentEager = new EagerBufferConfigImpl(
                10L, 1_024L, Map.of("cleanup.policy", "compact"));

        assertThat(eager.maxRecords()).isEqualTo(10L);
        assertThat(eager.maxBytes()).isEqualTo(1_024L);
        assertThat(eager.bufferFullStrategy().toString()).isEqualTo("EMIT");
        assertThat(eager.isLoggingEnabled()).isTrue();
        assertThat(eager.getLogConfig()).containsEntry("cleanup.policy", "compact");
        assertThat(eager).isEqualTo(equivalentEager).hasSameHashCodeAs(equivalentEager);
        assertThat(eager).isNotEqualTo(null).isNotEqualTo("buffer");
        assertThat(eager.toString()).contains("maxRecords=10", "maxBytes=1024", "cleanup.policy");

        EagerBufferConfigImpl disabled = (EagerBufferConfigImpl) eager.withLoggingDisabled();
        assertThat(disabled.isLoggingEnabled()).isFalse();
        assertThat(disabled.getLogConfig()).isEmpty();

        BufferConfigInternal<?> strict = (BufferConfigInternal<?>) eager.shutDownWhenFull();
        BufferConfigInternal<?> unbounded = (BufferConfigInternal<?>) eager.withNoBound();
        BufferConfigInternal<?> early = (BufferConfigInternal<?>) strict.emitEarlyWhenFull();
        assertThat(strict.maxRecords()).isEqualTo(10L);
        assertThat(strict.maxBytes()).isEqualTo(1_024L);
        assertThat(strict.bufferFullStrategy().toString()).isEqualTo("SHUT_DOWN");
        assertThat(unbounded.maxRecords()).isEqualTo(Long.MAX_VALUE);
        assertThat(unbounded.maxBytes()).isEqualTo(Long.MAX_VALUE);
        assertThat(early.bufferFullStrategy().toString()).isEqualTo("EMIT");
    }

    @Test
    void suppressionBuildersRetainTimingNamingAndBufferContracts() {
        StrictBufferConfigImpl buffer = (StrictBufferConfigImpl) Suppressed.BufferConfig.unbounded()
                .withMaxRecords(50L)
                .withMaxBytes(2_048L)
                .withLoggingDisabled();
        StrictBufferConfigImpl equivalentBuffer = (StrictBufferConfigImpl) Suppressed.BufferConfig.unbounded()
                .withMaxRecords(50L)
                .withMaxBytes(2_048L)
                .withLoggingDisabled();

        assertThat(buffer.maxRecords()).isEqualTo(50L);
        assertThat(buffer.maxBytes()).isEqualTo(2_048L);
        assertThat(buffer.bufferFullStrategy().toString()).isEqualTo("SHUT_DOWN");
        assertThat(buffer.isLoggingEnabled()).isFalse();
        assertThat(buffer.getLogConfig()).isEmpty();
        assertThat(buffer).isEqualTo(equivalentBuffer).hasSameHashCodeAs(equivalentBuffer);
        assertThat(buffer).isNotEqualTo(null).isNotEqualTo("buffer");
        assertThat(buffer.toString()).contains("maxKeys=50", "maxBytes=2048", "SHUT_DOWN");

        SuppressedInternal<String> timed = (SuppressedInternal<String>) Suppressed.<String>untilTimeLimit(
                Duration.ofSeconds(3), buffer).withName("quiet-period");
        SuppressedInternal<String> equivalentTimed = (SuppressedInternal<String>) Suppressed.<String>untilTimeLimit(
                Duration.ofSeconds(3), equivalentBuffer).withName("quiet-period");
        assertThat(timed.name()).isEqualTo("quiet-period");
        assertThat(timed.bufferConfig()).isEqualTo(buffer);
        assertThat(timed).isEqualTo(equivalentTimed).hasSameHashCodeAs(equivalentTimed);
        assertThat(timed).isNotEqualTo(null).isNotEqualTo("quiet-period");
        assertThat(timed.toString()).contains("quiet-period", "PT3S", "maxKeys=50");

        FinalResultsSuppressionBuilder<Windowed> finalResults =
                (FinalResultsSuppressionBuilder<Windowed>) Suppressed.untilWindowCloses(buffer)
                        .withName("window-final");
        SuppressedInternal<Windowed> built = finalResults.buildFinalResultsSuppression(Duration.ofSeconds(1));
        assertThat(finalResults.name()).isEqualTo("window-final");
        assertThat(finalResults.toString()).contains("window-final", "maxKeys=50");
        assertThat(built.name()).isEqualTo("window-final");
        assertThat(built.bufferConfig()).isEqualTo(buffer);
        assertThat(built.toString()).contains("PT1S", "safeToDropTombstones=true");
    }
}
