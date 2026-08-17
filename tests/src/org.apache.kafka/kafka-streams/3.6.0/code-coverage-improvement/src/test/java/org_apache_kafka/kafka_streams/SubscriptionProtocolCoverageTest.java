/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org_apache_kafka.kafka_streams;

import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.protocol.ByteBufferAccessor;
import org.apache.kafka.common.protocol.MessageUtil;
import org.apache.kafka.streams.internals.generated.SubscriptionInfoData;
import org.apache.kafka.streams.internals.generated.SubscriptionInfoData.ClientTag;
import org.apache.kafka.streams.internals.generated.SubscriptionInfoData.PartitionToOffsetSum;
import org.apache.kafka.streams.internals.generated.SubscriptionInfoData.TaskId;
import org.apache.kafka.streams.internals.generated.SubscriptionInfoData.TaskOffsetSum;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionProtocolCoverageTest {

    @Test
    void legacySubscriptionRoundTripsTaskAssignments() {
        TaskId previous = new TaskId().setTopicGroupId(3).setPartition(4);
        TaskId standby = new TaskId().setTopicGroupId(5).setPartition(6);
        SubscriptionInfoData original = new SubscriptionInfoData()
                .setVersion(6)
                .setLatestSupportedVersion(11)
                .setProcessId(new Uuid(1L, 2L))
                .setPrevTasks(List.of(previous))
                .setStandbyTasks(List.of(standby))
                .setUserEndPoint("host:9092".getBytes(StandardCharsets.UTF_8));

        SubscriptionInfoData decoded = roundTrip(original, (short) 6);

        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.hashCode()).isEqualTo(original.hashCode());
        assertThat(decoded.prevTasks()).containsExactly(previous);
        assertThat(decoded.standbyTasks()).containsExactly(standby);
        assertThat(decoded.userEndPoint()).asString(StandardCharsets.UTF_8).isEqualTo("host:9092");
        assertThat(decoded.toString()).contains("version=6", "topicGroupId=3", "partition=6");
        assertTaskIdContract(previous, 3, 4);
        assertTaskIdContract(standby, 5, 6);
    }

    @Test
    void currentSubscriptionRoundTripsOffsetsTagsAndVersionedFields() {
        TaskOffsetSum taskOffset = new TaskOffsetSum()
                .setTopicGroupId(8)
                .setPartition(9)
                .setOffsetSum(5678L)
                .setNamedTopology("payments");
        ClientTag clientTag = new ClientTag()
                .setKey("zone".getBytes(StandardCharsets.UTF_8))
                .setValue("west".getBytes(StandardCharsets.UTF_8));
        SubscriptionInfoData original = new SubscriptionInfoData()
                .setVersion(11)
                .setLatestSupportedVersion(11)
                .setProcessId(new Uuid(10L, 20L))
                .setUserEndPoint("worker:9092".getBytes(StandardCharsets.UTF_8))
                .setTaskOffsetSums(List.of(taskOffset))
                .setUniqueField((byte) 12)
                .setErrorCode(13)
                .setClientTags(List.of(clientTag));

        SubscriptionInfoData decoded = roundTrip(original, (short) 11);
        SubscriptionInfoData duplicate = decoded.duplicate();

        assertThat(decoded).isEqualTo(original);
        assertThat(duplicate).isEqualTo(decoded).isNotSameAs(decoded);
        assertThat(duplicate.taskOffsetSums().get(0)).isNotSameAs(decoded.taskOffsetSums().get(0));
        assertThat(decoded.apiKey()).isEqualTo((short) -1);
        assertThat(decoded.lowestSupportedVersion()).isEqualTo((short) 1);
        assertThat(decoded.highestSupportedVersion()).isEqualTo((short) 11);
        assertThat(decoded.uniqueField()).isEqualTo((byte) 12);
        assertThat(decoded.errorCode()).isEqualTo(13);
        assertThat(decoded.unknownTaggedFields()).isEmpty();
        assertTaskOffsetContract(decoded.taskOffsetSums().get(0));
        assertClientTagContract(decoded.clientTags().get(0));
    }

    @Test
    void offsetMapSubscriptionRoundTripsPartitionOffsets() {
        PartitionToOffsetSum partitionOffset = new PartitionToOffsetSum()
                .setPartition(7)
                .setOffsetSum(1234L);
        TaskOffsetSum taskOffset = new TaskOffsetSum()
                .setTopicGroupId(8)
                .setPartitionToOffsetSum(List.of(partitionOffset));
        SubscriptionInfoData original = new SubscriptionInfoData()
                .setVersion(9)
                .setLatestSupportedVersion(11)
                .setProcessId(new Uuid(30L, 40L))
                .setTaskOffsetSums(List.of(taskOffset))
                .setUniqueField((byte) 14)
                .setErrorCode(15);

        SubscriptionInfoData decoded = roundTrip(original, (short) 9);
        PartitionToOffsetSum decodedOffset = decoded.taskOffsetSums().get(0).partitionToOffsetSum().get(0);

        assertThat(decoded).isEqualTo(original);
        assertThat(decodedOffset.lowestSupportedVersion()).isEqualTo((short) 7);
        assertThat(decodedOffset.highestSupportedVersion()).isEqualTo((short) 9);
        assertThat(decodedOffset.partition()).isEqualTo(7);
        assertThat(decodedOffset.offsetSum()).isEqualTo(1234L);
        assertThat(decodedOffset.unknownTaggedFields()).isEmpty();
        assertThat(decodedOffset.duplicate()).isEqualTo(decodedOffset).isNotSameAs(decodedOffset);
        assertThat(decodedOffset.hashCode()).isEqualTo(decodedOffset.duplicate().hashCode());
        assertThat(decodedOffset.toString()).contains("partition=7", "offsetSum=1234");
    }

    private static SubscriptionInfoData roundTrip(SubscriptionInfoData data, short version) {
        ByteBuffer serialized = MessageUtil.toByteBuffer(data, version);
        return new SubscriptionInfoData(new ByteBufferAccessor(serialized), version);
    }

    private static void assertTaskIdContract(TaskId task, int group, int partition) {
        TaskId duplicate = task.duplicate();
        assertThat(task.lowestSupportedVersion()).isEqualTo((short) 1);
        assertThat(task.highestSupportedVersion()).isEqualTo((short) 6);
        assertThat(task.topicGroupId()).isEqualTo(group);
        assertThat(task.partition()).isEqualTo(partition);
        assertThat(task.unknownTaggedFields()).isEmpty();
        assertThat(duplicate).isEqualTo(task).isNotSameAs(task);
        assertThat(duplicate.hashCode()).isEqualTo(task.hashCode());
        assertThat(task.toString()).contains("topicGroupId=" + group, "partition=" + partition);
    }

    private static void assertTaskOffsetContract(TaskOffsetSum task) {
        TaskOffsetSum duplicate = task.duplicate();
        assertThat(task.lowestSupportedVersion()).isEqualTo((short) 7);
        assertThat(task.highestSupportedVersion()).isEqualTo(Short.MAX_VALUE);
        assertThat(task.topicGroupId()).isEqualTo(8);
        assertThat(task.partition()).isEqualTo(9);
        assertThat(task.offsetSum()).isEqualTo(5678L);
        assertThat(task.namedTopology()).isEqualTo("payments");
        assertThat(task.unknownTaggedFields()).isEmpty();
        assertThat(duplicate).isEqualTo(task).isNotSameAs(task);
        assertThat(duplicate.hashCode()).isEqualTo(task.hashCode());
        assertThat(task.partitionToOffsetSum()).isEmpty();
        assertThat(task.toString()).contains("namedTopology='payments'");
    }

    private static void assertClientTagContract(ClientTag tag) {
        ClientTag duplicate = tag.duplicate();
        assertThat(tag.lowestSupportedVersion()).isEqualTo((short) 11);
        assertThat(tag.highestSupportedVersion()).isEqualTo(Short.MAX_VALUE);
        assertThat(tag.key()).asString(StandardCharsets.UTF_8).isEqualTo("zone");
        assertThat(tag.value()).asString(StandardCharsets.UTF_8).isEqualTo("west");
        assertThat(tag.unknownTaggedFields()).isEmpty();
        assertThat(duplicate).isEqualTo(tag).isNotSameAs(tag);
        assertThat(duplicate.hashCode()).isEqualTo(tag.hashCode());
        assertThat(tag.toString()).contains("key=", "value=");
    }
}
