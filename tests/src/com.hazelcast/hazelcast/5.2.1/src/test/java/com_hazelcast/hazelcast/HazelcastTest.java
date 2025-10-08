/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package com_hazelcast.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class HazelcastTest {
    private HazelcastInstance hazelcastInstance;

    @BeforeEach
    void setUp() {
        Config config = new Config();
        hazelcastInstance = Hazelcast.newHazelcastInstance(config);
    }

    @AfterEach
    void tearDown() {
        hazelcastInstance.shutdown();
    }

    @Test
    void testMapPutAndGet() {
        IMap<String, String> map = hazelcastInstance.getMap("testMap");
        map.put("key", "value");
        assertThat(map.get("key")).isEqualTo("value");
    }

    @Test
    void testMapPutWithTTL() throws InterruptedException {
        IMap<String, String> map = hazelcastInstance.getMap("testMapWithTTL");
        map.put("key", "value", 1, TimeUnit.SECONDS);
        assertThat(map.get("key")).isEqualTo("value");
        Thread.sleep(2000);
        assertThat(map.get("key")).isNull();
    }

    @Test
    void testQueueOfferAndPoll() {
        var queue = hazelcastInstance.getQueue("testQueue");
        queue.offer("element");
        assertThat(queue.poll()).isEqualTo("element");
    }

    @Test
    void testSetAddAndContains() {
        var set = hazelcastInstance.getSet("testSet");
        set.add("element");
        assertThat(set.contains("element")).isTrue();
    }

    @Test
    void testListAddAndGet() {
        var list = hazelcastInstance.getList("testList");
        list.add("element");
        assertThat(list.get(0)).isEqualTo("element");
    }

    @Test
    void testMultiMapPutAndGet() {
        var multiMap = hazelcastInstance.getMultiMap("testMultiMap");
        multiMap.put("key", "value1");
        multiMap.put("key", "value2");
        assertThat(multiMap.get("key")).containsExactlyInAnyOrder("value1", "value2");
    }

    @Test
    void testFencedLock() {
        var fencedLock = hazelcastInstance.getCPSubsystem().getLock("testFencedLock");
        fencedLock.lock();
        assertThat(fencedLock.isLocked()).isTrue();
        fencedLock.unlock();
        assertThat(fencedLock.isLocked()).isFalse();
    }

    @Test
    void testTopicPublishAndSubscribe() throws InterruptedException {
        var topic = hazelcastInstance.getTopic("testTopic");
        var latch = new java.util.concurrent.CountDownLatch(1);
        topic.addMessageListener(message -> latch.countDown());
        topic.publish("message");
        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
    }
}
