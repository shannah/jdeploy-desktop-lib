package ca.weblite.jdeploy.app.hive;

/**
 * SPI interface for Hive IPC transport implementations.
 *
 * <p>Implementations handle the actual message delivery mechanism
 * (file system, sockets, message queues, etc.). The driver is responsible
 * for ensuring that messages are delivered to all other instances and
 * that messages from the local instance are not delivered back to itself.</p>
 *
 * <p>Custom drivers can be installed via {@link Hive#setDriver(HiveDriver)}.</p>
 *
 * <p>Example implementation:</p>
 * <pre>
 * public class RedisHiveDriver implements HiveDriver {
 *     private final RedisClient client;
 *     private final String channel;
 *
 *     public RedisHiveDriver(RedisClient client, String appName) {
 *         this.client = client;
 *         this.channel = "hive:" + appName;
 *         this.instanceId = UUID.randomUUID().toString();
 *     }
 *
 *     public void send(String message) {
 *         client.publish(channel, instanceId + ":" + message);
 *     }
 *     // ... etc
 * }
 * </pre>
 */
public interface HiveDriver {

    /**
     * Send a message to all other instances.
     *
     * <p>The message should be delivered to all other running instances
     * but NOT back to this instance's own listeners.</p>
     *
     * @param message the message to broadcast, never null
     */
    void send(String message);

    /**
     * Register a listener to receive messages from other instances.
     *
     * @param listener the listener to add
     */
    void addMessageListener(HiveMessageListener listener);

    /**
     * Remove a previously registered listener.
     *
     * @param listener the listener to remove
     */
    void removeMessageListener(HiveMessageListener listener);

    /**
     * Check if this driver is enabled and operational.
     *
     * @return true if the driver can send and receive messages
     */
    boolean isEnabled();

    /**
     * Shutdown this driver and release resources.
     *
     * <p>After shutdown, the driver should no longer deliver messages
     * and {@link #isEnabled()} should return false.</p>
     */
    void shutdown();

    /**
     * Get the unique identifier for this instance.
     *
     * <p>Used to identify this instance in ping/pong responses.</p>
     *
     * @return the instance ID, never null
     */
    String getInstanceId();

    /**
     * Get a human-readable name for this driver.
     *
     * @return the driver name, e.g., "FileWatcher", "Redis", "WebSocket"
     */
    default String getName() {
        return getClass().getSimpleName();
    }
}
