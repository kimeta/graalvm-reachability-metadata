/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org_apache_zookeeper.zookeeper;

import org.apache.zookeeper.AddWatchMode;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.OpResult;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.NIOServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.zookeeper.Watcher.Event.EventType.NodeCreated;
import static org.apache.zookeeper.Watcher.Event.EventType.NodeDataChanged;
import static org.apache.zookeeper.Watcher.Event.KeeperState.SyncConnected;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZookeeperTest {

    private Path snapDir;
    private Path logDir;
    private ZooKeeperServer zkServer;
    private NIOServerCnxnFactory serverFactory;
    private String connectString;
    private ZooKeeper client;

    @BeforeEach
    void setUp() throws Exception {
        // Create clean snapshot and log directories
        snapDir = Files.createTempDirectory("zk-snap-" + UUID.randomUUID());
        logDir = Files.createTempDirectory("zk-log-" + UUID.randomUUID());

        // Choose an available random port
        int port = randomFreePort();

        // Start an in-process ZooKeeper server
        zkServer = new ZooKeeperServer(snapDir.toFile(), logDir.toFile(), /*tickTime*/ 2000);
        serverFactory = new NIOServerCnxnFactory();
        serverFactory.configure(new InetSocketAddress("127.0.0.1", port), /*maxClientCnxns*/ 100);
        serverFactory.startup(zkServer);

        connectString = "127.0.0.1:" + port;
        client = connect(connectString, /*sessionTimeoutMs*/ 5000);
    }

    @AfterEach
    void tearDown() throws Exception {
        // Close client
        if (client != null) {
            try {
                client.close();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        // Shutdown server
        if (serverFactory != null) {
            serverFactory.shutdown();
        }
        if (zkServer != null) {
            zkServer.shutdown();
        }

        // Best-effort cleanup of temp dirs
        deleteRecursively(logDir);
        deleteRecursively(snapDir);
    }

    @Test
    void persistentNodeCrud() throws Exception {
        String path = "/it-crud-" + UUID.randomUUID();
        byte[] initial = "hello".getBytes(UTF_8);
        byte[] updated = "world".getBytes(UTF_8);

        // Create
        String createdPath = client.create(path, initial, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        assertThat(createdPath).isEqualTo(path);

        // Read and verify
        Stat stat = new Stat();
        byte[] read = client.getData(path, false, stat);
        assertThat(read).isEqualTo(initial);
        int initialVersion = stat.getVersion();
        assertThat(initialVersion).isEqualTo(0);

        // Update
        Stat newStat = client.setData(path, updated, initialVersion);
        assertThat(newStat.getVersion()).isEqualTo(initialVersion + 1);

        // Read updated
        byte[] readUpdated = client.getData(path, false, null);
        assertThat(readUpdated).isEqualTo(updated);

        // Delete
        client.delete(path, /*version*/ newStat.getVersion());

        // Verify deletion
        assertThat(client.exists(path, false)).isNull();
        assertThatThrownBy(() -> client.getData(path, false, null))
                .isInstanceOf(KeeperException.NoNodeException.class);
    }

    @Test
    void ephemeralNodeIsRemovedOnClientClose() throws Exception {
        String path = "/it-ephemeral-" + UUID.randomUUID();

        // Create an ephemeral znode with the current session
        String created = client.create(path, "e".getBytes(UTF_8), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
        assertThat(created).isEqualTo(path);
        assertThat(client.exists(path, false)).isNotNull();

        // Close this client/session; ephemerals should be removed
        client.close();

        // Use a fresh client to verify disappearance (graceful close should delete ephemerals promptly)
        try (ZooKeeper verifier = connect(connectString, 5000)) {
            boolean gone = waitFor(Duration.ofSeconds(10), () -> {
                try {
                    return verifier.exists(path, false) == null;
                } catch (Exception e) {
                    return false;
                }
            });
            assertThat(gone).as("ephemeral node should be removed after session closes").isTrue();
        } finally {
            // Reconnect base client for following tests (if any)
            client = connect(connectString, 5000);
        }
    }

    @Test
    void persistentWatchReceivesMultipleChangeEvents() throws Exception {
        String path = "/it-watch-" + UUID.randomUUID();

        // Create node
        client.create(path, "v0".getBytes(UTF_8), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        // Collect events using a persistent watch
        EventCollector collector = new EventCollector();
        client.addWatch(path, collector, AddWatchMode.PERSISTENT);

        // Trigger multiple data changes
        client.setData(path, "v1".getBytes(UTF_8), -1);
        client.setData(path, "v2".getBytes(UTF_8), -1);

        // We expect at least two NodeDataChanged events for the same path
        List<WatchedEvent> received = collector.awaitAtLeast(2, Duration.ofSeconds(10));

        long dataChangedCount = received.stream()
                .filter(e -> e.getType() == NodeDataChanged && Objects.equals(e.getPath(), path))
                .count();
        assertThat(dataChangedCount).isGreaterThanOrEqualTo(2);

        // Also verify that creating/deleting a child emits appropriate events with a recursive watch
        String parent = "/it-watch-parent-" + UUID.randomUUID();
        String child = parent + "/c1";
        client.create(parent, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        EventCollector recursive = new EventCollector();
        client.addWatch(parent, recursive, AddWatchMode.PERSISTENT_RECURSIVE);

        client.create(child, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        client.delete(child, -1);

        List<WatchedEvent> recvd = recursive.awaitAtLeast(2, Duration.ofSeconds(10));
        assertThat(recvd.stream().anyMatch(e -> e.getType() == NodeCreated && child.equals(e.getPath()))).isTrue();
        assertThat(recvd.stream().anyMatch(e -> e.getType() == Watcher.Event.EventType.NodeDeleted && child.equals(e.getPath()))).isTrue();
    }

    @Test
    void multiOperationIsAtomicCreateAndSetData() throws Exception {
        String path = "/it-multi-" + UUID.randomUUID();

        // Build a transaction: create node, then set data, atomically
        List<Op> ops = Arrays.asList(
                Op.create(path, "a".getBytes(UTF_8), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT),
                Op.setData(path, "b".getBytes(UTF_8), /*expectedVersion*/ 0)
        );

        List<OpResult> results = client.multi(ops);
        assertThat(results).hasSize(2);
        assertThat(client.exists(path, false)).isNotNull();
        assertThat(client.getData(path, false, null)).isEqualTo("b".getBytes(UTF_8));

        // Now attempt an invalid atomic update that should fail entirely
        List<Op> badOps = Arrays.asList(
                Op.setData(path, "c".getBytes(UTF_8), /*wrong version*/ 42), // will fail
                Op.delete(path, /*version doesn't matter as first op fails*/ -1)
        );

        assertThatThrownBy(() -> client.multi(badOps))
                .isInstanceOf(KeeperException.BadVersionException.class);

        // Node should still exist with previous value
        assertThat(client.getData(path, false, null)).isEqualTo("b".getBytes(UTF_8));
        // Clean up
        client.delete(path, -1);
    }

    // ------------------------------ Helpers ------------------------------

    private static ZooKeeper connect(String connectString, int sessionTimeoutMs) throws IOException, InterruptedException {
        CountDownLatch connected = new CountDownLatch(1);
        ZooKeeper zk = new ZooKeeper(connectString, sessionTimeoutMs, event -> {
            if (event.getState() == SyncConnected) {
                connected.countDown();
            }
        });
        boolean ok = connected.await(10, TimeUnit.SECONDS);
        assertThat(ok).as("client should connect to server").isTrue();
        return zk;
    }

    private static int randomFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static boolean waitFor(Duration timeout, CheckedSupplier<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return true;
            }
            Thread.sleep(25);
        }
        return Boolean.TRUE.equals(condition.get());
    }

    private static void deleteRecursively(Path root) {
        if (root == null) {
            return;
        }
        try {
            if (!Files.exists(root)) {
                return;
            }
            // Move to a temp location first on some platforms to reduce interference with open file handles
            Path tmp = root.resolveSibling(root.getFileName().toString() + "-del-" + UUID.randomUUID());
            try {
                Files.move(root, tmp, StandardCopyOption.ATOMIC_MOVE);
                root = tmp;
            } catch (Exception ignored) {
            }
            Files.walk(root)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception ignored) {
        }
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    private static final class EventCollector implements Watcher {
        private final BlockingQueue<WatchedEvent> events = new LinkedBlockingQueue<>();

        @Override
        public void process(WatchedEvent event) {
            events.add(event);
        }

        List<WatchedEvent> awaitAtLeast(int n, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            List<WatchedEvent> list = new ArrayList<>();
            while (list.size() < n) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                WatchedEvent ev = events.poll(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)), TimeUnit.MILLISECONDS);
                if (ev != null) {
                    list.add(ev);
                }
            }
            return list;
        }
    }
}
