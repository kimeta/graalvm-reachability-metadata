/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package com_hazelcast.hazelcast;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.fail;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;


class HazelcastTest {
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
        assertNotNull(map);
        assertEquals("value", map.get("key"));
    }

    @Test
    void testFencedLock() {
        com.hazelcast.cp.lock.FencedLock lock = hazelcastInstance.getCPSubsystem().getLock("testLock");
        lock.lock();
        assertEquals(true, lock.isLocked());
        lock.unlock();
        assertEquals(false, lock.isLocked());
    }

    @Test
    void testQueue() {
        com.hazelcast.collection.IQueue<String> queue = hazelcastInstance.getQueue("testQueue");
        queue.add("element");
        assertEquals("element", queue.poll());
    }
}
