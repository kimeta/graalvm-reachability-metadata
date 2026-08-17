/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org_apache_kafka.kafka_streams;

import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Branched;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.GlobalKTable;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.JoinWindows;
import org.apache.kafka.streams.kstream.Joined;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Named;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.Repartitioned;
import org.apache.kafka.streams.kstream.StreamJoined;
import org.apache.kafka.streams.kstream.TableJoined;
import org.apache.kafka.streams.state.KeyValueBytesStoreSupplier;
import org.apache.kafka.streams.state.SessionBytesStoreSupplier;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;
import org.apache.kafka.streams.state.VersionedBytesStoreSupplier;
import org.apache.kafka.streams.state.WindowBytesStoreSupplier;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TopologyDslCoverageTest {

    private static final Serde<String> STRINGS = Serdes.String();

    @Test
    void streamTransformationsProduceTheExpectedProcessorGraph() {
        StreamsBuilder builder = new StreamsBuilder();
        KStream<String, String> source = builder.stream("events", Consumed.with(STRINGS, STRINGS));
        KStream<String, String> transformed = source
                .filterNot((key, value) -> value.isBlank(), Named.as("non-blank"))
                .selectKey((key, value) -> value, Named.as("select-value-key"))
                .map((key, value) -> KeyValue.pair(key.toUpperCase(), value), Named.as("uppercase-key"))
                .mapValues(value -> value.trim(), Named.as("trim-value"))
                .mapValues((key, value) -> key + ":" + value, Named.as("prefix-value"))
                .flatMap((key, value) -> List.of(KeyValue.pair(key, value)), Named.as("flat-map"))
                .flatMapValues(value -> List.of(value, value.toUpperCase()), Named.as("duplicate-value"))
                .flatMapValues((key, value) -> List.of(key + value), Named.as("combine-value"))
                .peek((key, value) -> { }, Named.as("audit"));

        transformed.split(Named.as("route-"))
                .branch((key, value) -> value.length() > 5, Branched.as("long"))
                .defaultBranch(Branched.as("short"));
        transformed.foreach((key, value) -> { }, Named.as("consume"));
        transformed.merge(source, Named.as("merge-original"))
                .repartition(Repartitioned.<String, String>as("normalized").withNumberOfPartitions(2))
                .to("normalized-output", Produced.with(STRINGS, STRINGS));
        transformed.through("intermediate", Produced.with(STRINGS, STRINGS))
                .toTable(Named.as("latest"), Materialized.as("latest-store"));
        transformed.groupBy((key, value) -> value, Grouped.with(STRINGS, STRINGS))
                .count(Named.as("value-counts"), Materialized.as("count-store"));

        String description = builder.build().describe().toString();
        assertThat(description).contains(
                "non-blank", "select-value-key", "uppercase-key", "trim-value", "prefix-value",
                "flat-map", "duplicate-value", "combine-value", "audit", "route-long",
                "route-short", "consume", "merge-original", "normalized-repartition",
                "normalized-output", "intermediate", "latest", "value-counts");
    }

    @Test
    void streamAndTableJoinsProduceNamedJoinGraphs() {
        StreamsBuilder builder = new StreamsBuilder();
        KStream<String, String> orders = builder.stream("orders", Consumed.with(STRINGS, STRINGS));
        KStream<String, String> payments = builder.stream("payments", Consumed.with(STRINGS, STRINGS));
        KTable<String, String> customers = builder.table(
                "customers", Consumed.with(STRINGS, STRINGS), Materialized.as("customers-store"));
        KTable<String, String> regions = builder.table(
                "regions", Consumed.with(STRINGS, STRINGS), Materialized.as("regions-store"));
        GlobalKTable<String, String> countries = builder.globalTable(
                "countries", Consumed.with(STRINGS, STRINGS), Materialized.as("countries-store"));
        JoinWindows windows = JoinWindows.ofTimeDifferenceWithNoGrace(Duration.ofMinutes(1));
        StreamJoined<String, String, String> streamJoined = StreamJoined
                .with(STRINGS, STRINGS, STRINGS)
                .withName("order-payment");

        orders.join(payments, (left, right) -> left + right, windows, streamJoined)
                .to("paid-orders");
        orders.leftJoin(payments, (key, left, right) -> left + right, windows,
                        streamJoined.withName("optional-payment"))
                .to("possibly-paid-orders");
        orders.outerJoin(payments, (left, right) -> left + right, windows,
                        streamJoined.withName("all-orders-payments"))
                .to("all-orders-payments-output");
        orders.join(customers, (order, customer) -> order + customer,
                        Joined.with(STRINGS, STRINGS, STRINGS).withName("customer-join"))
                .to("customer-orders");
        orders.leftJoin(customers, (key, order, customer) -> order + customer,
                        Joined.with(STRINGS, STRINGS, STRINGS).withName("optional-customer"))
                .to("optional-customer-orders");
        orders.join(countries, (key, order) -> key, (order, country) -> order + country,
                        Named.as("country-join"))
                .to("country-orders");
        orders.leftJoin(countries, (key, order) -> key, (key, order, country) -> order + country,
                        Named.as("optional-country"))
                .to("optional-country-orders");

        customers.filter((key, value) -> !value.isBlank(), Named.as("valid-customer"),
                        Materialized.as("valid-customer-store"))
                .mapValues((key, value) -> value.toUpperCase(), Named.as("uppercase-customer"),
                        Materialized.as("uppercase-customer-store"));
        customers.join(regions, (customer, region) -> customer + region,
                        Named.as("customer-region"), Materialized.as("customer-region-store"));
        customers.leftJoin(regions, (customer, region) -> customer + region,
                        Named.as("optional-region"), Materialized.as("optional-region-store"));
        customers.outerJoin(regions, (customer, region) -> customer + region,
                        Named.as("all-customer-regions"), Materialized.as("all-customer-regions-store"));
        customers.join(regions, value -> value, (customer, region) -> customer + region,
                        TableJoined.as("foreign-region"), Materialized.as("foreign-region-store"));

        String description = builder.build().describe().toString();
        assertThat(description).contains(
                "order-payment", "optional-payment", "all-orders-payments", "customer-join",
                "optional-customer", "country-join", "optional-country", "valid-customer",
                "uppercase-customer", "customer-region", "optional-region", "all-customer-regions",
                "foreign-region");
    }

    @Test
    void storeFactoriesRetainTheirStorageAndRetentionContracts() {
        KeyValueBytesStoreSupplier persistent = Stores.persistentKeyValueStore("persistent-kv");
        KeyValueBytesStoreSupplier memory = Stores.inMemoryKeyValueStore("memory-kv");
        KeyValueBytesStoreSupplier lru = Stores.lruMap("bounded-kv", 20);
        VersionedBytesStoreSupplier versioned = Stores.persistentVersionedKeyValueStore(
                "versioned-kv", Duration.ofHours(1), Duration.ofMinutes(5));
        WindowBytesStoreSupplier window = Stores.persistentWindowStore(
                "window", Duration.ofHours(1), Duration.ofMinutes(5), true);
        WindowBytesStoreSupplier timestampedWindow = Stores.persistentTimestampedWindowStore(
                "timestamped-window", Duration.ofHours(1), Duration.ofMinutes(5), false);
        SessionBytesStoreSupplier session = Stores.inMemorySessionStore("session", Duration.ofMinutes(30));

        StoreBuilder<?> persistentBuilder = Stores.keyValueStoreBuilder(persistent, STRINGS, STRINGS)
                .withCachingEnabled().withLoggingEnabled(Map.of("cleanup.policy", "compact"));
        StoreBuilder<?> memoryBuilder = Stores.keyValueStoreBuilder(memory, STRINGS, STRINGS);
        StoreBuilder<?> lruBuilder = Stores.keyValueStoreBuilder(lru, STRINGS, STRINGS);
        StoreBuilder<?> versionedBuilder = Stores.versionedKeyValueStoreBuilder(versioned, STRINGS, STRINGS);
        StoreBuilder<?> windowBuilder = Stores.windowStoreBuilder(window, STRINGS, STRINGS);
        StoreBuilder<?> timestampedBuilder = Stores.timestampedWindowStoreBuilder(
                timestampedWindow, STRINGS, STRINGS);
        StoreBuilder<?> sessionBuilder = Stores.sessionStoreBuilder(session, STRINGS, STRINGS);

        assertThat(persistentBuilder.name()).isEqualTo("persistent-kv");
        assertThat(persistentBuilder.loggingEnabled()).isTrue();
        assertThat(persistentBuilder.logConfig()).containsEntry("cleanup.policy", "compact");
        assertThat(memoryBuilder.name()).isEqualTo("memory-kv");
        assertThat(lruBuilder.name()).isEqualTo("bounded-kv");
        assertThat(versionedBuilder.name()).isEqualTo("versioned-kv");
        assertThat(windowBuilder.name()).isEqualTo("window");
        assertThat(timestampedBuilder.name()).isEqualTo("timestamped-window");
        assertThat(sessionBuilder.name()).isEqualTo("session");
        assertThat(persistent.get().persistent()).isTrue();
        assertThat(memory.get().persistent()).isFalse();
    }
}
