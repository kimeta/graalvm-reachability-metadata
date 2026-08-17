/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org_apache_kafka.kafka_streams;

import org.apache.kafka.streams.query.FailureReason;
import org.apache.kafka.streams.query.KeyQuery;
import org.apache.kafka.streams.query.Position;
import org.apache.kafka.streams.query.PositionBound;
import org.apache.kafka.streams.query.QueryResult;
import org.apache.kafka.streams.query.RangeQuery;
import org.apache.kafka.streams.query.StateQueryRequest;
import org.apache.kafka.streams.query.StateQueryResult;
import org.apache.kafka.streams.query.WindowKeyQuery;
import org.apache.kafka.streams.query.WindowRangeQuery;
import org.apache.kafka.streams.processor.StateStore;
import org.apache.kafka.streams.state.Stores;
import org.apache.kafka.streams.state.ValueAndTimestamp;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class InteractiveQueryCoverageTest {

    @Test
    void positionMaintainsMonotonicIndependentOffsets() {
        Map<Integer, Long> inputPartitions = new HashMap<>(Map.of(0, 4L));
        Map<String, Map<Integer, Long>> input = new HashMap<>(Map.of("orders", inputPartitions));
        Position position = Position.fromMap(input);
        inputPartitions.put(0, 100L);

        position.withComponent("orders", 0, 3L)
                .withComponent("orders", 0, 7L)
                .withComponent("payments", 2, 9L)
                .merge(Position.fromMap(Map.of("orders", Map.of(0, 6L, 1, 8L))))
                .merge(null);

        assertThat(position.getTopics()).containsExactlyInAnyOrder("orders", "payments");
        assertThat(position.getPartitionPositions("orders")).containsExactlyInAnyOrderEntriesOf(
                Map.of(0, 7L, 1, 8L));
        assertThat(position.getPartitionPositions("missing")).isEmpty();
        assertThat(position.copy()).isEqualTo(position).isNotSameAs(position);
        assertThat(position).isNotEqualTo(Position.emptyPosition()).isNotEqualTo(null);
        assertThat(position.toString()).contains("orders", "payments");
        assertThat(position.isEmpty()).isFalse();
        assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(position::hashCode);
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> position.getTopics().clear());
    }

    @Test
    void positionBoundsSnapshotTheirPositionAndUseValueSemantics() {
        Position source = Position.emptyPosition().withComponent("orders", 0, 12L);
        PositionBound bound = PositionBound.at(source);
        source.withComponent("orders", 0, 20L);
        PositionBound equivalent = PositionBound.at(
                Position.emptyPosition().withComponent("orders", 0, 12L));

        assertThat(bound.position().getPartitionPositions("orders")).containsEntry(0, 12L);
        assertThat(bound).isEqualTo(equivalent).isNotEqualTo(PositionBound.unbounded()).isNotEqualTo(null);
        assertThat(bound.isUnbounded()).isFalse();
        assertThat(PositionBound.unbounded().isUnbounded()).isTrue();
        assertThat(bound.toString()).contains("orders", "12");
        assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(bound::hashCode);
    }

    @Test
    void stateQueryRequestRetainsImmutableBuilderChoices() {
        KeyQuery<String, String> query = KeyQuery.<String, String>withKey("customer-1").skipCache();
        PositionBound bound = PositionBound.at(Position.emptyPosition().withComponent("orders", 1, 5L));
        Set<Integer> requestedPartitions = new java.util.HashSet<>(Set.of(1, 2));
        StateQueryRequest<String> request = StateQueryRequest.inStore("customer-store")
                .withQuery(query)
                .withPositionBound(bound)
                .withPartitions(requestedPartitions)
                .enableExecutionInfo()
                .requireActive();
        requestedPartitions.clear();

        assertThat(request.getStoreName()).isEqualTo("customer-store");
        assertThat(request.getQuery()).isSameAs(query);
        assertThat(request.getPositionBound()).isEqualTo(bound);
        assertThat(request.isAllPartitions()).isFalse();
        assertThat(request.getPartitions()).containsExactlyInAnyOrder(1, 2);
        assertThat(request.executionInfoEnabled()).isTrue();
        assertThat(request.isRequireActive()).isTrue();
        assertThat(query.getKey()).isEqualTo("customer-1");
        assertThat(query.isSkipCache()).isTrue();
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> request.getPartitions().clear());

        StateQueryRequest<String> allPartitions = request.withAllPartitions();
        assertThat(allPartitions.isAllPartitions()).isTrue();
        assertThatIllegalStateException().isThrownBy(allPartitions::getPartitions);
        assertThatNullPointerException().isThrownBy(() -> KeyQuery.withKey(null));
    }

    @Test
    void stateQueryResultAggregatesPartitionPositionsAndSelectsSingleValue() {
        QueryResult<String> first = QueryResult.forResult("first");
        first.setPosition(Position.emptyPosition().withComponent("orders", 0, 3L));
        first.addExecutionInfo("served from cache");
        QueryResult<String> empty = QueryResult.forResult(null);
        empty.setPosition(Position.emptyPosition().withComponent("orders", 1, 4L));
        StateQueryResult<String> result = new StateQueryResult<>();
        result.addResult(0, first);
        result.addResult(1, empty);

        assertThat(result.getOnlyPartitionResult()).isSameAs(first);
        assertThat(result.getPartitionResults()).containsEntry(0, first).containsEntry(1, empty);
        assertThat(result.getPosition().getPartitionPositions("orders"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(0, 3L, 1, 4L));
        assertThat(first.isSuccess()).isTrue();
        assertThat(first.isFailure()).isFalse();
        assertThat(first.getResult()).isEqualTo("first");
        assertThat(first.getExecutionInfo()).containsExactly("served from cache");
        assertThat(result.toString()).contains("partitionResults", "first");

        result.addResult(2, QueryResult.forResult("second"));
        assertThatIllegalArgumentException().isThrownBy(result::getOnlyPartitionResult);
    }

    @Test
    void globalAndFailedQueryResultsExposeTheirDiagnostics() {
        QueryResult<String> global = QueryResult.forResult("global-value");
        Position globalPosition = Position.emptyPosition().withComponent("global-topic", 0, 15L);
        global.setPosition(globalPosition);
        StateQueryResult<String> result = new StateQueryResult<>();
        result.setGlobalResult(global);

        assertThat(result.getGlobalResult()).isSameAs(global);
        assertThat(result.getPosition()).isEqualTo(globalPosition);

        QueryResult<String> failure = QueryResult.forFailure(FailureReason.NOT_ACTIVE, "standby replica");
        failure.addExecutionInfo("partition is not active");
        failure.setPosition(Position.emptyPosition());
        assertThat(failure.isFailure()).isTrue();
        assertThat(failure.isSuccess()).isFalse();
        assertThat(failure.getFailureReason()).isEqualTo(FailureReason.NOT_ACTIVE);
        assertThat(failure.getFailureMessage()).isEqualTo("standby replica");
        assertThat(failure.getExecutionInfo()).containsExactly("partition is not active");
        assertThat(failure.toString()).contains("NOT_ACTIVE", "standby replica");
        assertThatIllegalArgumentException().isThrownBy(failure::getResult);
        assertThatIllegalArgumentException().isThrownBy(global::getFailureReason);
        assertThatIllegalArgumentException().isThrownBy(global::getFailureMessage);
    }

    @Test
    void rangeQueriesPreserveIndependentKeyAndWindowBounds() {
        RangeQuery<String, Long> bounded = RangeQuery.withRange("a", "z");
        RangeQuery<String, Long> lowerBounded = RangeQuery.withLowerBound("m");
        RangeQuery<String, Long> upperBounded = RangeQuery.withUpperBound("n");
        RangeQuery<String, Long> unbounded = RangeQuery.withNoBounds();
        WindowRangeQuery<String, Long> keyed = WindowRangeQuery.withKey("account");
        Instant from = Instant.ofEpochMilli(10L);
        Instant to = Instant.ofEpochMilli(20L);
        WindowRangeQuery<String, Long> windowed = WindowRangeQuery.withWindowStartRange(from, to);

        assertThat(bounded.getLowerBound()).contains("a");
        assertThat(bounded.getUpperBound()).contains("z");
        assertThat(lowerBounded.getLowerBound()).contains("m");
        assertThat(lowerBounded.getUpperBound()).isEmpty();
        assertThat(upperBounded.getLowerBound()).isEmpty();
        assertThat(upperBounded.getUpperBound()).contains("n");
        assertThat(unbounded.getLowerBound()).isEmpty();
        assertThat(unbounded.getUpperBound()).isEmpty();
        assertThat(keyed.getKey()).contains("account");
        assertThat(keyed.getTimeFrom()).isEmpty();
        assertThat(keyed.getTimeTo()).isEmpty();
        assertThat(windowed.getKey()).isEmpty();
        assertThat(windowed.getTimeFrom()).contains(from);
        assertThat(windowed.getTimeTo()).contains(to);
        assertThat(windowed.toString()).contains(from.toString(), to.toString());
    }

    @Test
    void queryFailureFactoriesExplainUnsupportedQueriesAndPositionLag() {
        RangeQuery<String, String> query = RangeQuery.withNoBounds();
        StateStore store = Stores.inMemoryKeyValueStore("query-store").get();
        QueryResult<?> unknown = QueryResult.forUnknownQueryType(query, store);
        Position current = Position.emptyPosition().withComponent("orders", 2, 10L);
        PositionBound bound = PositionBound.at(
                Position.emptyPosition().withComponent("orders", 2, 20L));
        QueryResult<?> partitionLag = QueryResult.notUpToBound(current, bound, 2);
        QueryResult<?> uninitialized = QueryResult.notUpToBound(current, bound, null);

        assertThat(unknown.getFailureReason()).isEqualTo(FailureReason.UNKNOWN_QUERY_TYPE);
        assertThat(unknown.getFailureMessage()).contains("InMemoryKeyValueStore", query.toString());
        assertThat(partitionLag.getFailureReason()).isEqualTo(FailureReason.NOT_UP_TO_BOUND);
        assertThat(partitionLag.getFailureMessage()).contains("partition 2", "orders", "10", "20");
        assertThat(uninitialized.getFailureReason()).isEqualTo(FailureReason.NOT_UP_TO_BOUND);
        assertThat(uninitialized.getFailureMessage()).contains("not initialized", "orders", "20");
    }

    @Test
    void windowKeyQueryAndTimestampedValueExposeConfiguredValues() {
        Instant from = Instant.ofEpochMilli(10L);
        Instant to = Instant.ofEpochMilli(20L);
        WindowKeyQuery<String, Long> query =
                WindowKeyQuery.withKeyAndWindowStartRange("account", from, to);
        ValueAndTimestamp<String> nullable = ValueAndTimestamp.makeAllowNullable(null, 42L);
        ValueAndTimestamp<String> value = ValueAndTimestamp.make("balance", 43L);
        ValueAndTimestamp<String> equivalent = ValueAndTimestamp.make("balance", 43L);

        assertThat(query.getKey()).isEqualTo("account");
        // Kafka Streams 3.6 exposes these endpoints in reverse due to its constructor ordering.
        assertThat(query.getTimeFrom()).contains(to);
        assertThat(query.getTimeTo()).contains(from);
        assertThat(query.toString()).contains("account", from.toString(), to.toString());
        assertThat(nullable.value()).isNull();
        assertThat(nullable.toString()).isEqualTo("<null,42>");
        assertThat(ValueAndTimestamp.make(null, 1L)).isNull();
        assertThat(ValueAndTimestamp.<String>getValueOrNull(null)).isNull();
        assertThat(ValueAndTimestamp.getValueOrNull(value)).isEqualTo("balance");
        assertThat(value.timestamp()).isEqualTo(43L);
        assertThat(value).isEqualTo(equivalent).hasSameHashCodeAs(equivalent);
        assertThat(value).isNotEqualTo(nullable).isNotEqualTo(null);
        assertThat(value.toString()).isEqualTo("<balance,43>");
    }
}
