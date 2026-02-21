package ca.weblite.jdeploy.app.hive;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Hive facade.
 */
class HiveTest {

    private MockHiveDriver mockDriver;

    @BeforeEach
    void setUp() {
        mockDriver = new MockHiveDriver();
        Hive.setDriver(mockDriver);
    }

    @AfterEach
    void tearDown() {
        Hive.shutdown();
    }

    // ========== Property Encoding/Decoding Tests ==========

    @Test
    void testEncodeProperties_empty() {
        String encoded = Hive.encodeProperties(Collections.emptyMap());
        assertEquals("", encoded);
    }

    @Test
    void testEncodeProperties_null() {
        String encoded = Hive.encodeProperties(null);
        assertEquals("", encoded);
    }

    @Test
    void testEncodeProperties_singleProperty() {
        Map<String, String> props = Map.of("key", "value");
        String encoded = Hive.encodeProperties(props);
        assertEquals("key=value", encoded);
    }

    @Test
    void testEncodeProperties_multipleProperties() {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("name", "test");
        props.put("version", "1.0");
        String encoded = Hive.encodeProperties(props);
        assertTrue(encoded.contains("name=test"));
        assertTrue(encoded.contains("version=1.0"));
        assertTrue(encoded.contains(","));
    }

    @Test
    void testEncodeProperties_specialCharacters() {
        Map<String, String> props = Map.of("key", "value with spaces");
        String encoded = Hive.encodeProperties(props);
        assertFalse(encoded.contains(" "));
        assertTrue(encoded.contains("%20"));
    }

    @Test
    void testDecodeProperties_empty() {
        Map<String, String> props = Hive.decodeProperties("");
        assertTrue(props.isEmpty());
    }

    @Test
    void testDecodeProperties_null() {
        Map<String, String> props = Hive.decodeProperties(null);
        assertTrue(props.isEmpty());
    }

    @Test
    void testDecodeProperties_singleProperty() {
        Map<String, String> props = Hive.decodeProperties("key=value");
        assertEquals(1, props.size());
        assertEquals("value", props.get("key"));
    }

    @Test
    void testDecodeProperties_multipleProperties() {
        Map<String, String> props = Hive.decodeProperties("name=test,version=1.0");
        assertEquals(2, props.size());
        assertEquals("test", props.get("name"));
        assertEquals("1.0", props.get("version"));
    }

    @Test
    void testDecodeProperties_specialCharacters() {
        Map<String, String> props = Hive.decodeProperties("key=value%20with%20spaces");
        assertEquals("value with spaces", props.get("key"));
    }

    @Test
    void testEncodeDecodeRoundTrip() {
        Map<String, String> original = new HashMap<>();
        original.put("name", "My App");
        original.put("version", "1.2.3");
        original.put("special", "a=b,c:d");

        String encoded = Hive.encodeProperties(original);
        Map<String, String> decoded = Hive.decodeProperties(encoded);

        assertEquals(original.size(), decoded.size());
        for (Map.Entry<String, String> entry : original.entrySet()) {
            assertEquals(entry.getValue(), decoded.get(entry.getKey()));
        }
    }

    // ========== Driver Management Tests ==========

    @Test
    void testSetDriver_registersInternalListener() {
        assertTrue(mockDriver.hasListeners());
    }

    @Test
    void testSetDriver_shutdownsPreviousDriver() {
        MockHiveDriver oldDriver = mockDriver;
        MockHiveDriver newDriver = new MockHiveDriver();

        Hive.setDriver(newDriver);

        assertTrue(oldDriver.isShutdown());
        assertFalse(newDriver.isShutdown());
    }

    @Test
    void testSetDriver_null() {
        Hive.setDriver(null);
        assertNull(Hive.getDriver());
        assertFalse(Hive.isEnabled());
    }

    @Test
    void testIsEnabled_withDriver() {
        assertTrue(Hive.isEnabled());
    }

    @Test
    void testIsEnabled_withoutDriver() {
        Hive.setDriver(null);
        assertFalse(Hive.isEnabled());
    }

    // ========== Message Sending Tests ==========

    @Test
    void testSend_delegatesToDriver() {
        Hive.send("test message");
        assertEquals(1, mockDriver.getSentMessages().size());
        assertEquals("test message", mockDriver.getSentMessages().get(0));
    }

    @Test
    void testSend_noDriverDoesNotThrow() {
        Hive.setDriver(null);
        assertDoesNotThrow(() -> Hive.send("test"));
    }

    @Test
    void testSend_disabledDriverDoesNotSend() {
        mockDriver.setEnabled(false);
        Hive.send("test");
        assertTrue(mockDriver.getSentMessages().isEmpty());
    }

    // ========== Message Listening Tests ==========

    @Test
    void testAddMessageListener_receivesMessages() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        List<String> received = new ArrayList<>();

        Hive.addMessageListener(msg -> {
            received.add(msg);
            latch.countDown();
        });

        // Simulate incoming message from driver
        mockDriver.simulateIncomingMessage("hello");

        assertTrue(latch.await(1, TimeUnit.SECONDS));
        assertEquals(1, received.size());
        assertEquals("hello", received.get(0));
    }

    @Test
    void testAddMessageListener_multipleListeners() throws Exception {
        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger count = new AtomicInteger(0);

        Hive.addMessageListener(msg -> {
            count.incrementAndGet();
            latch.countDown();
        });
        Hive.addMessageListener(msg -> {
            count.incrementAndGet();
            latch.countDown();
        });

        mockDriver.simulateIncomingMessage("test");

        assertTrue(latch.await(1, TimeUnit.SECONDS));
        assertEquals(2, count.get());
    }

    @Test
    void testRemoveMessageListener() throws Exception {
        AtomicInteger count = new AtomicInteger(0);
        HiveMessageListener listener = msg -> count.incrementAndGet();

        Hive.addMessageListener(listener);
        mockDriver.simulateIncomingMessage("first");
        assertEquals(1, count.get());

        Hive.removeMessageListener(listener);
        mockDriver.simulateIncomingMessage("second");
        assertEquals(1, count.get()); // Should not increment
    }

    @Test
    void testMessageListener_doesNotReceivePingMessages() throws Exception {
        AtomicInteger count = new AtomicInteger(0);
        Hive.addMessageListener(msg -> count.incrementAndGet());

        mockDriver.simulateIncomingMessage("__HIVE_PING__:test-id");
        Thread.sleep(50);
        assertEquals(0, count.get());
    }

    @Test
    void testMessageListener_doesNotReceivePongMessages() throws Exception {
        AtomicInteger count = new AtomicInteger(0);
        Hive.addMessageListener(msg -> count.incrementAndGet());

        mockDriver.simulateIncomingMessage("__HIVE_PONG__:test-id:instance-1:key=value");
        Thread.sleep(50);
        assertEquals(0, count.get());
    }

    @Test
    void testMessageListener_exceptionDoesNotBreakOthers() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        List<String> received = new ArrayList<>();

        Hive.addMessageListener(msg -> {
            throw new RuntimeException("Intentional exception");
        });
        Hive.addMessageListener(msg -> {
            received.add(msg);
            latch.countDown();
        });

        mockDriver.simulateIncomingMessage("test");

        assertTrue(latch.await(1, TimeUnit.SECONDS));
        assertEquals(1, received.size());
    }

    // ========== Ping/Pong Tests ==========

    @Test
    void testPing_sendsPingMessage() {
        Hive.ping(100, pong -> true);

        assertEquals(1, mockDriver.getSentMessages().size());
        assertTrue(mockDriver.getSentMessages().get(0).startsWith("__HIVE_PING__:"));
    }

    @Test
    void testPing_receivesPongResponses() throws Exception {
        List<HivePong> pongs = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        Thread pinger = new Thread(() -> {
            Hive.ping(500, pong -> {
                pongs.add(pong);
                latch.countDown();
                return false; // Stop after first
            });
        });
        pinger.start();

        // Wait for ping to be sent
        Thread.sleep(50);

        // Extract ping ID and send pong
        String pingMsg = mockDriver.getSentMessages().get(0);
        String pingId = pingMsg.substring("__HIVE_PING__:".length());
        mockDriver.simulateIncomingMessage("__HIVE_PONG__:" + pingId + ":other-instance:role=primary");

        assertTrue(latch.await(1, TimeUnit.SECONDS));
        assertEquals(1, pongs.size());
        assertEquals("other-instance", pongs.get(0).getInstanceId());
        assertEquals("primary", pongs.get(0).getProperty("role"));

        pinger.join(1000);
    }

    @Test
    void testPing_countMethod() throws Exception {
        Thread counter = new Thread(() -> {
            int count = Hive.ping(200);
            assertEquals(2, count);
        });
        counter.start();

        // Wait for ping
        Thread.sleep(50);

        String pingMsg = mockDriver.getSentMessages().get(0);
        String pingId = pingMsg.substring("__HIVE_PING__:".length());

        // Send two pongs
        mockDriver.simulateIncomingMessage("__HIVE_PONG__:" + pingId + ":instance-1:");
        mockDriver.simulateIncomingMessage("__HIVE_PONG__:" + pingId + ":instance-2:");

        counter.join(1000);
    }

    @Test
    void testHasOtherInstances_true() throws Exception {
        Thread checker = new Thread(() -> {
            boolean result = Hive.hasOtherInstances(200);
            assertTrue(result);
        });
        checker.start();

        Thread.sleep(50);

        String pingMsg = mockDriver.getSentMessages().get(0);
        String pingId = pingMsg.substring("__HIVE_PING__:".length());
        mockDriver.simulateIncomingMessage("__HIVE_PONG__:" + pingId + ":other:");

        checker.join(1000);
    }

    @Test
    void testHasOtherInstances_false() {
        // No pong received
        boolean result = Hive.hasOtherInstances(100);
        assertFalse(result);
    }

    @Test
    void testPing_stopsEarlyWhenListenerReturnsFalse() throws Exception {
        AtomicInteger count = new AtomicInteger(0);
        long start = System.currentTimeMillis();

        Thread pinger = new Thread(() -> {
            Hive.ping(2000, pong -> {
                count.incrementAndGet();
                return false; // Stop immediately
            });
        });
        pinger.start();

        Thread.sleep(50);

        String pingMsg = mockDriver.getSentMessages().get(0);
        String pingId = pingMsg.substring("__HIVE_PING__:".length());
        mockDriver.simulateIncomingMessage("__HIVE_PONG__:" + pingId + ":other:");

        pinger.join(1000);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(1, count.get());
        assertTrue(elapsed < 1000, "Should have stopped early, but took " + elapsed + "ms");
    }

    @Test
    void testPing_respondsToPingsFromOthers() throws Exception {
        // When we receive a ping, we should auto-respond with pong
        Hive.setInstanceProperties(Map.of("role", "worker"));

        mockDriver.simulateIncomingMessage("__HIVE_PING__:remote-ping-123");

        // Give it time to respond
        Thread.sleep(50);

        // Should have sent a pong
        boolean foundPong = mockDriver.getSentMessages().stream()
                .anyMatch(msg -> msg.startsWith("__HIVE_PONG__:remote-ping-123:" + mockDriver.getInstanceId()));
        assertTrue(foundPong, "Should have responded with pong");
    }

    // ========== Instance Properties Tests ==========

    @Test
    void testSetInstanceProperties() {
        Map<String, String> props = Map.of("version", "1.0");
        Hive.setInstanceProperties(props);
        assertEquals(props, Hive.getInstanceProperties());
    }

    @Test
    void testSetInstanceProperties_null() {
        Hive.setInstanceProperties(Map.of("key", "value"));
        Hive.setInstanceProperties(null);
        assertTrue(Hive.getInstanceProperties().isEmpty());
    }

    @Test
    void testGetInstanceProperties_returnsUnmodifiable() {
        Hive.setInstanceProperties(Map.of("key", "value"));
        assertThrows(UnsupportedOperationException.class, () -> {
            Hive.getInstanceProperties().put("new", "value");
        });
    }

    // ========== Shutdown Tests ==========

    @Test
    void testShutdown_shutsDownDriver() {
        Hive.shutdown();
        assertTrue(mockDriver.isShutdown());
    }

    @Test
    void testShutdown_clearsListeners() {
        AtomicInteger count = new AtomicInteger(0);
        Hive.addMessageListener(msg -> count.incrementAndGet());

        Hive.shutdown();

        // Re-create driver and send message - listener should not receive
        mockDriver = new MockHiveDriver();
        Hive.setDriver(mockDriver);
        mockDriver.simulateIncomingMessage("test");

        assertEquals(0, count.get());
    }

    // ========== Mock Driver ==========

    private static class MockHiveDriver implements HiveDriver {
        private final String instanceId = "mock-" + UUID.randomUUID().toString();
        private final List<String> sentMessages = new CopyOnWriteArrayList<>();
        private final List<HiveMessageListener> listeners = new CopyOnWriteArrayList<>();
        private volatile boolean enabled = true;
        private volatile boolean shutdown = false;

        @Override
        public void send(String message) {
            if (enabled && !shutdown) {
                sentMessages.add(message);
            }
        }

        @Override
        public void addMessageListener(HiveMessageListener listener) {
            listeners.add(listener);
        }

        @Override
        public void removeMessageListener(HiveMessageListener listener) {
            listeners.remove(listener);
        }

        @Override
        public boolean isEnabled() {
            return enabled && !shutdown;
        }

        @Override
        public void shutdown() {
            shutdown = true;
            listeners.clear();
        }

        @Override
        public String getInstanceId() {
            return instanceId;
        }

        // Test helpers

        void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        boolean isShutdown() {
            return shutdown;
        }

        List<String> getSentMessages() {
            return sentMessages;
        }

        boolean hasListeners() {
            return !listeners.isEmpty();
        }

        void simulateIncomingMessage(String message) {
            for (HiveMessageListener listener : listeners) {
                listener.onMessage(message);
            }
        }
    }
}
