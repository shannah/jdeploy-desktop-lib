package ca.weblite.jdeploy.app.hive;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple IPC service for broadcasting messages between app instances.
 *
 * <p>This is a static facade that delegates to a pluggable {@link HiveDriver}.
 * Messages sent via {@link #send(String)} are broadcast to all other running
 * instances of the application (but not back to the sender).</p>
 *
 * <h2>Basic Usage</h2>
 * <pre>
 * // Install a driver (e.g., from jdeploy-desktop-lib-hive-filewatcher)
 * FileWatcherHiveDriver.install();
 *
 * // Send a message to all other instances
 * Hive.send("REFRESH_DATA");
 *
 * // Receive messages from other instances
 * Hive.addMessageListener(message -> {
 *     System.out.println("Received: " + message);
 * });
 * </pre>
 *
 * <h2>Instance Discovery</h2>
 * <pre>
 * // Check if other instances are running
 * if (Hive.hasOtherInstances(1000)) {
 *     System.out.println("Another instance is already running");
 * }
 *
 * // Find instances with specific properties
 * Hive.setInstanceProperties(Map.of("role", "worker"));
 * Hive.ping(3000, pong -> {
 *     if ("primary".equals(pong.getProperty("role"))) {
 *         connectToPrimary(pong.getInstanceId());
 *         return false; // Stop looking
 *     }
 *     return true; // Keep looking
 * });
 * </pre>
 */
public final class Hive {

    // Ping/pong protocol prefixes
    static final String PING_PREFIX = "__HIVE_PING__:";
    static final String PONG_PREFIX = "__HIVE_PONG__:";

    private static volatile HiveDriver driver;
    private static volatile Map<String, String> instanceProperties = Collections.emptyMap();

    // Pending ping listeners waiting for pong responses
    private static final ConcurrentHashMap<String, PingContext> pendingPings = new ConcurrentHashMap<>();

    // User message listeners (filtered to exclude ping/pong)
    private static final CopyOnWriteArrayList<HiveMessageListener> userListeners = new CopyOnWriteArrayList<>();

    // Internal listener for handling ping/pong protocol
    private static final HiveMessageListener internalListener = Hive::handleInternalMessage;

    private Hive() {
        // Static utility class
    }

    /**
     * Set the IPC driver implementation.
     *
     * <p>If a driver was previously set, it will be shut down first.</p>
     *
     * @param newDriver the driver to use, or null to disable IPC
     */
    public static synchronized void setDriver(HiveDriver newDriver) {
        HiveDriver oldDriver = driver;
        if (oldDriver != null) {
            oldDriver.removeMessageListener(internalListener);
            oldDriver.shutdown();
        }
        driver = newDriver;
        if (newDriver != null) {
            newDriver.addMessageListener(internalListener);
        }
    }

    /**
     * Get the current driver.
     *
     * @return the current driver, or null if none is set
     */
    public static HiveDriver getDriver() {
        return driver;
    }

    /**
     * Send a message to all other running instances.
     *
     * <p>The message is broadcast to all other instances but not delivered
     * back to this instance's own listeners.</p>
     *
     * <p>No-op if no driver is set or driver is disabled.</p>
     *
     * @param message the message to broadcast
     */
    public static void send(String message) {
        HiveDriver d = driver;
        if (d != null && d.isEnabled()) {
            d.send(message);
        }
    }

    /**
     * Register a listener to receive messages from other instances.
     *
     * <p>The listener will NOT receive messages sent by this instance,
     * nor internal ping/pong protocol messages.</p>
     *
     * @param listener the listener to add
     */
    public static void addMessageListener(HiveMessageListener listener) {
        if (listener != null) {
            userListeners.add(listener);
        }
    }

    /**
     * Remove a previously registered listener.
     *
     * @param listener the listener to remove
     */
    public static void removeMessageListener(HiveMessageListener listener) {
        userListeners.remove(listener);
    }

    /**
     * Set properties that will be included in pong responses.
     *
     * <p>These properties are sent to other instances when they ping
     * to discover running instances. Common uses include advertising
     * app version, state, role, or capabilities.</p>
     *
     * @param properties the properties to advertise, or null/empty to clear
     */
    public static void setInstanceProperties(Map<String, String> properties) {
        instanceProperties = properties != null ? new HashMap<>(properties) : Collections.emptyMap();
    }

    /**
     * Get the current instance properties.
     *
     * @return the properties map, never null
     */
    public static Map<String, String> getInstanceProperties() {
        return Collections.unmodifiableMap(instanceProperties);
    }

    /**
     * Ping all other instances and collect responses.
     *
     * <p>Sends a ping message and waits up to {@code timeoutMs} for responses.
     * The listener is called for each pong response received.</p>
     *
     * @param timeoutMs maximum time to wait for responses in milliseconds
     * @param listener called for each response; return false to stop early
     */
    public static void ping(long timeoutMs, HivePongListener listener) {
        HiveDriver d = driver;
        if (d == null || !d.isEnabled() || listener == null) {
            return;
        }

        String pingId = UUID.randomUUID().toString();
        CountDownLatch stopLatch = new CountDownLatch(1);
        PingContext context = new PingContext(listener, stopLatch);
        pendingPings.put(pingId, context);

        try {
            // Send ping
            d.send(PING_PREFIX + pingId);

            // Wait for timeout or early stop
            stopLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pendingPings.remove(pingId);
        }
    }

    /**
     * Ping all other instances and return the count of responses.
     *
     * @param timeoutMs maximum time to wait for responses in milliseconds
     * @return the number of other instances that responded
     */
    public static int ping(long timeoutMs) {
        AtomicInteger count = new AtomicInteger(0);
        ping(timeoutMs, pong -> {
            count.incrementAndGet();
            return true; // Keep counting
        });
        return count.get();
    }

    /**
     * Check if any other instances of the application are running.
     *
     * @param timeoutMs maximum time to wait for a response in milliseconds
     * @return true if at least one other instance responded
     */
    public static boolean hasOtherInstances(long timeoutMs) {
        final boolean[] found = {false};
        ping(timeoutMs, pong -> {
            found[0] = true;
            return false; // Stop immediately
        });
        return found[0];
    }

    /**
     * Check if IPC is enabled.
     *
     * @return true if a driver is set and operational
     */
    public static boolean isEnabled() {
        HiveDriver d = driver;
        return d != null && d.isEnabled();
    }

    /**
     * Shutdown the IPC service and release resources.
     *
     * <p>Clears all listeners and shuts down the driver.</p>
     */
    public static synchronized void shutdown() {
        HiveDriver d = driver;
        if (d != null) {
            d.removeMessageListener(internalListener);
            d.shutdown();
        }
        driver = null;
        userListeners.clear();
        pendingPings.clear();
        instanceProperties = Collections.emptyMap();
    }

    /**
     * Handle incoming messages from the driver.
     * Routes ping/pong internally and forwards user messages to listeners.
     */
    private static void handleInternalMessage(String message) {
        if (message == null) {
            return;
        }

        if (message.startsWith(PING_PREFIX)) {
            handlePing(message.substring(PING_PREFIX.length()));
        } else if (message.startsWith(PONG_PREFIX)) {
            handlePong(message.substring(PONG_PREFIX.length()));
        } else {
            // User message - dispatch to listeners
            for (HiveMessageListener listener : userListeners) {
                try {
                    listener.onMessage(message);
                } catch (Exception e) {
                    // Don't let one listener break others
                }
            }
        }
    }

    /**
     * Handle an incoming ping by sending a pong response.
     */
    private static void handlePing(String pingId) {
        HiveDriver d = driver;
        if (d == null || !d.isEnabled()) {
            return;
        }

        // Build pong response: pingId:instanceId:properties
        StringBuilder pong = new StringBuilder();
        pong.append(PONG_PREFIX);
        pong.append(pingId);
        pong.append(":");
        pong.append(d.getInstanceId());
        pong.append(":");
        pong.append(encodeProperties(instanceProperties));

        d.send(pong.toString());
    }

    /**
     * Handle an incoming pong by notifying the waiting ping listener.
     */
    private static void handlePong(String payload) {
        // Parse: pingId:instanceId:properties
        int firstColon = payload.indexOf(':');
        if (firstColon < 0) {
            return;
        }

        String pingId = payload.substring(0, firstColon);
        PingContext context = pendingPings.get(pingId);
        if (context == null) {
            return; // Not our ping or already timed out
        }

        int secondColon = payload.indexOf(':', firstColon + 1);
        if (secondColon < 0) {
            return;
        }

        String instanceId = payload.substring(firstColon + 1, secondColon);
        String propsEncoded = payload.substring(secondColon + 1);
        Map<String, String> properties = decodeProperties(propsEncoded);

        HivePong pong = new DefaultHivePong(instanceId, properties);

        try {
            boolean continueListening = context.listener.onPong(pong);
            if (!continueListening) {
                context.stopLatch.countDown();
            }
        } catch (Exception e) {
            // Listener threw - stop listening
            context.stopLatch.countDown();
        }
    }

    /**
     * Encode properties as a simple key=value,key=value format.
     * Values are URL-encoded to handle special characters.
     */
    static String encodeProperties(Map<String, String> props) {
        if (props == null || props.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : props.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append(urlEncode(entry.getKey()));
            sb.append("=");
            sb.append(urlEncode(entry.getValue()));
        }
        return sb.toString();
    }

    /**
     * Decode properties from the encoded format.
     */
    static Map<String, String> decodeProperties(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, String> props = new HashMap<>();
        for (String pair : encoded.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = urlDecode(pair.substring(0, eq));
                String value = urlDecode(pair.substring(eq + 1));
                props.put(key, value);
            }
        }
        return props;
    }

    /**
     * Simple URL encoding for property keys/values.
     */
    private static String urlEncode(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.') {
                sb.append(c);
            } else {
                sb.append('%');
                sb.append(String.format("%02X", (int) c));
            }
        }
        return sb.toString();
    }

    /**
     * Simple URL decoding for property keys/values.
     */
    private static String urlDecode(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                try {
                    int code = Integer.parseInt(s.substring(i + 1, i + 3), 16);
                    sb.append((char) code);
                    i += 2;
                } catch (NumberFormatException e) {
                    sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Context for a pending ping operation.
     */
    private static class PingContext {
        final HivePongListener listener;
        final CountDownLatch stopLatch;

        PingContext(HivePongListener listener, CountDownLatch stopLatch) {
            this.listener = listener;
            this.stopLatch = stopLatch;
        }
    }

    /**
     * Default implementation of HivePong.
     */
    private static class DefaultHivePong implements HivePong {
        private final String instanceId;
        private final Map<String, String> properties;

        DefaultHivePong(String instanceId, Map<String, String> properties) {
            this.instanceId = instanceId;
            this.properties = Collections.unmodifiableMap(new HashMap<>(properties));
        }

        @Override
        public String getInstanceId() {
            return instanceId;
        }

        @Override
        public Map<String, String> getProperties() {
            return properties;
        }
    }
}
