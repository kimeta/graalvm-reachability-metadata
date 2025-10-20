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
    void testMapPutGet() {
        IMap<String, String> map = hazelcastInstance.getMap("testMap");
        map.put("key", "value");
        assertThat(map.get("key")).isEqualTo("value");
    }

    @Test
    void testMapSize() {
        IMap<String, String> map = hazelcastInstance.getMap("testMap");
        map.put("key1", "value1");
        map.put("key2", "value2");
        assertThat(map.size()).isEqualTo(2);
    }
}
