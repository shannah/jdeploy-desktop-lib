package ca.weblite.jdeploy.app.hive;

/**
 * Listener for ping responses from other instances.
 *
 * <p>Used with {@link Hive#ping(long, HivePongListener)} to discover
 * other running instances of the application.</p>
 */
public interface HivePongListener {

    /**
     * Called when a pong response is received from another instance.
     *
     * <p>This method is called on a background thread for each response
     * received within the ping timeout period.</p>
     *
     * @param pong the response containing instance information
     * @return true to continue listening for more responses,
     *         false to stop early and return from the ping call
     */
    boolean onPong(HivePong pong);
}
