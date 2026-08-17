/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org_apache_kafka.kafka_streams;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.errors.BrokerNotFoundException;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.errors.InvalidStateStorePartitionException;
import org.apache.kafka.streams.errors.LockException;
import org.apache.kafka.streams.errors.ProcessorStateException;
import org.apache.kafka.streams.errors.StateStoreMigratedException;
import org.apache.kafka.streams.errors.StateStoreNotAvailableException;
import org.apache.kafka.streams.errors.StreamsException;
import org.apache.kafka.streams.errors.StreamsNotStartedException;
import org.apache.kafka.streams.errors.StreamsRebalancingException;
import org.apache.kafka.streams.errors.StreamsStoppedException;
import org.apache.kafka.streams.errors.TaskAssignmentException;
import org.apache.kafka.streams.errors.TaskIdFormatException;
import org.apache.kafka.streams.errors.TaskMigratedException;
import org.apache.kafka.streams.errors.TopologyException;
import org.apache.kafka.streams.errors.UnknownStateStoreException;
import org.apache.kafka.streams.internals.UpgradeFromValues;
import org.apache.kafka.streams.kstream.Branched;
import org.apache.kafka.streams.kstream.EmitStrategy;
import org.apache.kafka.streams.processor.BatchingStateRestoreCallback;
import org.apache.kafka.streams.processor.FailOnInvalidTimestamp;
import org.apache.kafka.streams.processor.StreamPartitioner;
import org.apache.kafka.streams.processor.UsePartitionTimeOnInvalidTimestamp;
import org.apache.kafka.streams.processor.WallclockTimestampExtractor;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.processor.internals.QuickUnion;
import org.apache.kafka.streams.processor.internals.StaticTopicNameExtractor;
import org.apache.kafka.streams.state.TimestampedBytesStore;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.apache.kafka.clients.consumer.ConsumerRecord.NO_TIMESTAMP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoreApiContractsCoverageTest {

    @Test
    void processorRecordsAreImmutableValueObjectsWithDefensiveHeaders() {
        RecordHeaders sourceHeaders = new RecordHeaders();
        sourceHeaders.add("trace", new byte[]{1});
        Record<String, Integer> record = new Record<>("key", 7, 10L, sourceHeaders);
        sourceHeaders.add("late", new byte[]{2});

        Record<String, Integer> equivalent = new Record<>(
                "key", 7, 10L, new RecordHeaders().add("trace", new byte[]{1}));
        Record<String, Integer> replacedHeaders = record.withHeaders(
                new RecordHeaders().add("replacement", new byte[]{3}));

        assertThat(record.headers().lastHeader("trace").value()).containsExactly(1);
        assertThat(record.headers().lastHeader("late")).isNull();
        assertThat(record.withTimestamp(11L).timestamp()).isEqualTo(11L);
        assertThat(record.withKey("other").key()).isEqualTo("other");
        assertThat(record.withValue(8).value()).isEqualTo(8);
        assertThat(replacedHeaders.headers().lastHeader("replacement").value()).containsExactly(3);
        assertThat(record).isEqualTo(equivalent).hasSameHashCodeAs(equivalent);
        assertThat(record).isNotEqualTo(replacedHeaders).isNotEqualTo(null).isNotEqualTo("record");
        assertThat(record.toString()).contains("key=key", "value=7", "timestamp=10", "trace");
        assertThatThrownBy(() -> new Record<>("key", "value", -1L))
                .isInstanceOf(StreamsException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void branchingAndEmissionFactoriesEnforceTheirConfigurationContracts() {
        Branched<String, Integer> named = Branched.<String, Integer>as("positive").withName("renamed");
        Branched<String, Integer> function = Branched.withFunction(stream -> stream, "mapped");
        Branched<String, Integer> unnamedFunction = Branched.withFunction(stream -> stream);
        Branched<String, Integer> consumer = Branched.withConsumer(stream -> { }, "observed");
        Branched<String, Integer> unnamedConsumer = Branched.withConsumer(stream -> { });

        assertThat(named).isNotNull();
        assertThat(function).isNotNull();
        assertThat(unnamedFunction).isNotNull();
        assertThat(consumer).isNotNull();
        assertThat(unnamedConsumer).isNotNull();
        assertThatNullPointerException().isThrownBy(() -> Branched.as(null));
        assertThatNullPointerException().isThrownBy(() -> named.withName(null));
        assertThatNullPointerException().isThrownBy(() -> Branched.withFunction(null));
        assertThatNullPointerException().isThrownBy(() -> Branched.withConsumer(null));

        EmitStrategy close = EmitStrategy.onWindowClose();
        EmitStrategy update = EmitStrategy.onWindowUpdate();
        assertThat(close.type()).isEqualTo(EmitStrategy.StrategyType.ON_WINDOW_CLOSE);
        assertThat(update.type()).isEqualTo(EmitStrategy.StrategyType.ON_WINDOW_UPDATE);
        assertThat(EmitStrategy.StrategyType.forType(close.type()).type()).isEqualTo(close.type());
        assertThat(EmitStrategy.StrategyType.forType(update.type()).type()).isEqualTo(update.type());
    }

    @SuppressWarnings("deprecation")
    @Test
    void compatibilityHelpersAdaptLegacyDataWithoutLosingSemantics() {
        byte[] plainValue = new byte[]{4, 5, 6};
        byte[] timestamped = TimestampedBytesStore.convertToTimestampedFormat(plainValue);
        ByteBuffer decoded = ByteBuffer.wrap(timestamped);

        assertThat(decoded.getLong()).isEqualTo(NO_TIMESTAMP);
        assertThat(new byte[]{decoded.get(), decoded.get(), decoded.get()}).containsExactly(plainValue);
        assertThat(TimestampedBytesStore.convertToTimestampedFormat(null)).isNull();
        assertThat(UpgradeFromValues.getValueFromString("3.5"))
                .isEqualTo(UpgradeFromValues.UPGRADE_FROM_35)
                .hasToString("3.5");

        StreamPartitioner<String, String> selected = (topic, key, value, count) -> 2;
        StreamPartitioner<String, String> defaulted = (topic, key, value, count) -> null;
        assertThat(selected.partitions("output", "key", "value", 4)).contains(Set.of(2));
        assertThat(defaulted.partitions("output", "key", "value", 4)).isEqualTo(Optional.empty());

        BatchingStateRestoreCallback callback = records -> {
            assertThat(records).hasSize(1);
            KeyValue<byte[], byte[]> restored = records.iterator().next();
            assertThat(restored.key).containsExactly(1);
            assertThat(restored.value).containsExactly(2);
        };
        callback.restoreAll(List.of(KeyValue.pair(new byte[]{1}, new byte[]{2})));
        assertThatThrownBy(() -> callback.restore(new byte[]{1}, new byte[]{2}))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void timestampExtractorsRejectOrRecoverFromInvalidBrokerTimestamps() {
        ConsumerRecord<Object, Object> valid = new ConsumerRecord<>(
                "events", 1, 4L, 25L, TimestampType.CREATE_TIME, -1L, -1, -1, "key", "value");
        FailOnInvalidTimestamp strict = new FailOnInvalidTimestamp();
        UsePartitionTimeOnInvalidTimestamp fallback = new UsePartitionTimeOnInvalidTimestamp();
        WallclockTimestampExtractor wallclock = new WallclockTimestampExtractor();

        assertThat(strict.extract(valid, 30L)).isEqualTo(25L);
        assertThat(fallback.extract(valid, 30L)).isEqualTo(25L);
        assertThat(wallclock.extract(valid, 30L)).isPositive();

        ConsumerRecord<Object, Object> missing = new ConsumerRecord<>("events", 1, -1L, "key", "value");
        assertThatThrownBy(() -> strict.extract(missing, 30L))
                .isInstanceOf(StreamsException.class)
                .hasMessageContaining("invalid (negative) timestamp");
        assertThat(fallback.extract(missing, 30L)).isEqualTo(30L);
        assertThatThrownBy(() -> fallback.extract(missing, -1L))
                .isInstanceOf(StreamsException.class)
                .hasMessageContaining("partition time is unknown");
    }

    @Test
    void internalUtilityValueContractsDistinguishIdentityAndMembership() {
        QuickUnion<String> union = new QuickUnion<>();
        union.add("alpha");
        union.add("beta");

        assertThat(union.exists("alpha")).isTrue();
        assertThat(union.exists("missing")).isFalse();
        assertThat(union.root("alpha")).isEqualTo("alpha");
        assertThatThrownBy(() -> union.root("missing"))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessageContaining("missing");

        StaticTopicNameExtractor<String, Integer> extractor = new StaticTopicNameExtractor<>("events");
        StaticTopicNameExtractor<String, Integer> equivalent = new StaticTopicNameExtractor<>("events");
        assertThat(extractor.extract("key", 1, null)).isEqualTo("events");
        assertThat(extractor).isEqualTo(equivalent).hasSameHashCodeAs(equivalent);
        assertThat(extractor).isNotEqualTo(new StaticTopicNameExtractor<>("other"))
                .isNotEqualTo(null)
                .isNotEqualTo("events");
        assertThat(extractor).hasToString("StaticTopicNameExtractor(events)");
    }

    @Test
    void streamExceptionFamiliesRetainMessagesAndCausesAcrossConstructors() {
        RuntimeException cause = new RuntimeException("root cause");
        List<BiFunction<String, Throwable, StreamsException>> withCause = List.of(
                BrokerNotFoundException::new,
                InvalidStateStoreException::new,
                LockException::new,
                ProcessorStateException::new,
                TaskAssignmentException::new,
                TaskIdFormatException::new,
                TopologyException::new,
                InvalidStateStorePartitionException::new,
                StateStoreMigratedException::new,
                StateStoreNotAvailableException::new,
                StreamsNotStartedException::new,
                StreamsRebalancingException::new,
                StreamsStoppedException::new,
                TaskMigratedException::new,
                UnknownStateStoreException::new);
        List<Function<String, StreamsException>> messageOnly = List.of(
                BrokerNotFoundException::new,
                InvalidStateStoreException::new,
                LockException::new,
                ProcessorStateException::new,
                TaskAssignmentException::new,
                TaskIdFormatException::new,
                TopologyException::new,
                InvalidStateStorePartitionException::new,
                StateStoreMigratedException::new,
                StateStoreNotAvailableException::new,
                StreamsNotStartedException::new,
                StreamsRebalancingException::new,
                StreamsStoppedException::new,
                TaskMigratedException::new,
                UnknownStateStoreException::new);

        assertThat(withCause).allSatisfy(factory -> {
            StreamsException exception = factory.apply("operation failed", cause);
            assertThat(exception).hasMessageContaining("operation failed").hasCause(cause);
        });
        assertThat(messageOnly).allSatisfy(factory ->
                assertThat(factory.apply("operation failed")).hasMessageContaining("operation failed"));

        List<Function<Throwable, StreamsException>> causeOnly = List.of(
                BrokerNotFoundException::new,
                InvalidStateStoreException::new,
                LockException::new,
                ProcessorStateException::new,
                TaskAssignmentException::new,
                TaskIdFormatException::new,
                TopologyException::new);
        assertThat(causeOnly).allSatisfy(factory -> assertThat(factory.apply(cause)).hasCause(cause));
    }
}
