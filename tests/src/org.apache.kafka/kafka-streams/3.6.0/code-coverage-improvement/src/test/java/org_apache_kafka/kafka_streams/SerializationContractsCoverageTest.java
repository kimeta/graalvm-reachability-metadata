/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org_apache_kafka.kafka_streams;

import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.errors.StreamsException;
import org.apache.kafka.streams.errors.TaskAssignmentException;
import org.apache.kafka.streams.errors.TaskIdFormatException;
import org.apache.kafka.streams.kstream.SessionWindowedDeserializer;
import org.apache.kafka.streams.kstream.SessionWindowedSerializer;
import org.apache.kafka.streams.kstream.TimeWindowedDeserializer;
import org.apache.kafka.streams.kstream.TimeWindowedSerializer;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.kstream.WindowedSerdes;
import org.apache.kafka.streams.kstream.internals.SessionWindow;
import org.apache.kafka.streams.kstream.internals.TimeWindow;
import org.apache.kafka.streams.processor.TaskId;
import org.apache.kafka.streams.state.StateSerdes;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SerializationContractsCoverageTest {

    @SuppressWarnings("deprecation")
    @Test
    void taskIdsRoundTripAcrossTextAndLegacyProtocolRepresentations() throws Exception {
        TaskId unnamed = new TaskId(3, 7);
        TaskId named = new TaskId(3, 7, "orders");

        assertThat(TaskId.parse(unnamed.toString())).isEqualTo(unnamed);
        assertThat(TaskId.parse(named.toString())).isEqualTo(named);
        assertThat(named.subtopology()).isEqualTo(3);
        assertThat(named.partition()).isEqualTo(7);
        assertThat(named.topologyName()).isEqualTo("orders");
        assertThat(new TaskId(3, 7, "").topologyName()).isNull();
        assertThatThrownBy(() -> TaskId.parse("not-a-task"))
                .isInstanceOf(TaskIdFormatException.class);

        assertThat(new TaskId(2, 9)).isLessThan(unnamed);
        assertThat(new TaskId(3, 6)).isLessThan(unnamed);
        assertThat(new TaskId(3, 8)).isGreaterThan(unnamed);
        assertThat(new TaskId(3, 7, "alpha")).isLessThan(named);
        assertThatThrownBy(() -> named.compareTo(unnamed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("namedTopology");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        unnamed.writeTo(new DataOutputStream(bytes), 9);
        TaskId dataRoundTrip = TaskId.readFrom(
                new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), 9);
        assertThat(dataRoundTrip).isEqualTo(unnamed);

        ByteBuffer buffer = ByteBuffer.allocate(128);
        named.writeTo(buffer, 10);
        buffer.flip();
        assertThat(TaskId.readFrom(buffer, 10)).isEqualTo(named);
        assertThatThrownBy(() -> named.writeTo(ByteBuffer.allocate(32), 9))
                .isInstanceOf(TaskAssignmentException.class)
                .hasMessageContaining("protocol version 9");
    }

    @Test
    void timeWindowedSerdeRoundTripsKeysAndEnforcesConfigurationSources() {
        Windowed<String> value = new Windowed<>("customer-7", new TimeWindow(100L, 125L));
        TimeWindowedSerializer<String> serializer = new TimeWindowedSerializer<>(Serdes.String().serializer());
        TimeWindowedDeserializer<String> deserializer =
                new TimeWindowedDeserializer<>(Serdes.String().deserializer(), 25L);

        byte[] serialized = serializer.serialize("events", value);
        Windowed<String> restored = deserializer.deserialize("events", serialized);
        assertThat(restored.key()).isEqualTo("customer-7");
        assertThat(restored.window().start()).isEqualTo(100L);
        assertThat(restored.window().end()).isEqualTo(125L);
        assertThat(serializer.serializeBaseKey("events", value))
                .isEqualTo(Serdes.String().serializer().serialize("events", "customer-7"));
        assertThat(serializer.serialize("events", null)).isNull();
        assertThat(deserializer.deserialize("events", null)).isNull();
        assertThat(deserializer.deserialize("events", new byte[0])).isNull();
        assertThat(deserializer.getWindowSize()).isEqualTo(25L);

        Map<String, Object> config = Map.of(
                StreamsConfig.WINDOW_SIZE_MS_CONFIG, "25",
                StreamsConfig.WINDOWED_INNER_CLASS_SERDE, Serdes.StringSerde.class.getName());
        TimeWindowedSerializer<String> configuredSerializer = new TimeWindowedSerializer<>();
        configuredSerializer.configure(config, true);
        TimeWindowedDeserializer<String> configuredDeserializer = new TimeWindowedDeserializer<>();
        configuredDeserializer.configure(config, true);
        assertThat(configuredDeserializer.deserialize(
                "events", configuredSerializer.serialize("events", value))).isEqualTo(value);

        assertThatIllegalArgumentException().isThrownBy(
                () -> new TimeWindowedSerializer<>().configure(Map.of(), true));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new TimeWindowedDeserializer<>().configure(Map.of(), true));
        assertThatIllegalArgumentException().isThrownBy(() -> deserializer.configure(
                Map.of(StreamsConfig.WINDOW_SIZE_MS_CONFIG, 25L), true));
        assertThatThrownBy(() -> new TimeWindowedSerializer<>().configure(
                Map.of(StreamsConfig.WINDOWED_INNER_CLASS_SERDE, "missing.Serde"), true))
                .isInstanceOf(ConfigException.class);
        assertThatNullPointerException().isThrownBy(
                () -> new TimeWindowedSerializer<String>().serialize("events", value));
        assertThatNullPointerException().isThrownBy(
                () -> new TimeWindowedDeserializer<String>(null, 25L).deserialize("events", serialized));

        serializer.close();
        deserializer.close();
        configuredSerializer.close();
        configuredDeserializer.close();
    }

    @Test
    void sessionWindowedSerdePreservesBothInclusiveBoundaries() {
        Windowed<String> value = new Windowed<>("session", new SessionWindow(40L, 75L));
        SessionWindowedSerializer<String> serializer =
                new SessionWindowedSerializer<>(Serdes.String().serializer());
        SessionWindowedDeserializer<String> deserializer =
                new SessionWindowedDeserializer<>(Serdes.String().deserializer());

        byte[] serialized = serializer.serialize("sessions", value);
        assertThat(deserializer.deserialize("sessions", serialized)).isEqualTo(value);
        assertThat(serializer.serializeBaseKey("sessions", value))
                .isEqualTo(Serdes.String().serializer().serialize("sessions", "session"));
        assertThat(serializer.serialize("sessions", null)).isNull();
        assertThat(deserializer.deserialize("sessions", new byte[0])).isNull();

        Map<String, Object> config = Map.of(
                StreamsConfig.WINDOWED_INNER_CLASS_SERDE, Serdes.StringSerde.class.getName());
        SessionWindowedSerializer<String> configuredSerializer = new SessionWindowedSerializer<>();
        SessionWindowedDeserializer<String> configuredDeserializer = new SessionWindowedDeserializer<>();
        configuredSerializer.configure(config, true);
        configuredDeserializer.configure(config, true);
        assertThat(configuredDeserializer.deserialize(
                "sessions", configuredSerializer.serialize("sessions", value))).isEqualTo(value);

        assertThatIllegalArgumentException().isThrownBy(
                () -> new SessionWindowedSerializer<>().configure(Map.of(), true));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new SessionWindowedDeserializer<>().configure(Map.of(), true));
        assertThatNullPointerException().isThrownBy(
                () -> new SessionWindowedSerializer<String>().serialize("sessions", value));
        assertThatNullPointerException().isThrownBy(
                () -> new SessionWindowedDeserializer<String>().deserialize("sessions", serialized));

        serializer.close();
        deserializer.close();
        configuredSerializer.close();
        configuredDeserializer.close();
    }

    @Test
    void serdeFactoriesAndStateSerdesExposeReliableTypedRoundTrips() {
        Serde<Windowed<String>> timeSerde = WindowedSerdes.timeWindowedSerdeFrom(String.class, 10L);
        Windowed<String> timeValue = new Windowed<>("key", new TimeWindow(20L, 30L));
        assertThat(timeSerde.deserializer().deserialize(
                "time", timeSerde.serializer().serialize("time", timeValue))).isEqualTo(timeValue);

        Serde<Windowed<String>> sessionSerde = WindowedSerdes.sessionWindowedSerdeFrom(String.class);
        Windowed<String> sessionValue = new Windowed<>("key", new SessionWindow(20L, 30L));
        assertThat(sessionSerde.deserializer().deserialize(
                "session", sessionSerde.serializer().serialize("session", sessionValue))).isEqualTo(sessionValue);

        StateSerdes<String, Long> stateSerdes =
                StateSerdes.withBuiltinTypes("store-changelog", String.class, Long.class);
        assertThat(stateSerdes.topic()).isEqualTo("store-changelog");
        assertThat(stateSerdes.keySerde()).isNotNull();
        assertThat(stateSerdes.valueSerde()).isNotNull();
        assertThat(stateSerdes.keySerializer()).isNotNull();
        assertThat(stateSerdes.keyDeserializer()).isNotNull();
        assertThat(stateSerdes.valueSerializer()).isNotNull();
        assertThat(stateSerdes.valueDeserializer()).isNotNull();
        assertThat(stateSerdes.keyFrom(stateSerdes.rawKey("account"))).isEqualTo("account");
        assertThat(stateSerdes.valueFrom(stateSerdes.rawValue(42L))).isEqualTo(42L);

        @SuppressWarnings({"rawtypes", "unchecked"})
        StateSerdes raw = stateSerdes;
        assertThatThrownBy(() -> raw.rawKey(12L))
                .isInstanceOf(StreamsException.class)
                .hasMessageContaining("actual key type", Long.class.getName());
        assertThatThrownBy(() -> raw.rawValue("wrong"))
                .isInstanceOf(StreamsException.class)
                .hasMessageContaining("actual value type", String.class.getName());
        assertThatNullPointerException().isThrownBy(
                () -> new StateSerdes<>(null, Serdes.String(), Serdes.Long()));
    }
}
