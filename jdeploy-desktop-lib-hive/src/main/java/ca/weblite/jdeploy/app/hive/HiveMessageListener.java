package ca.weblite.jdeploy.app.hive;

/**
 * Listener for receiving IPC messages from other instances.
 *
 * <p>Implementations receive messages broadcast by other running instances
 * of the same application. Messages sent by the local instance are not
 * delivered to its own listeners.</p>
 */
public interface HiveMessageListener {

    /**
     * Called when a message is received from another instance.
     *
     * <p>This method is called on a background thread. Implementations
     * should dispatch to the appropriate thread (e.g., EDT for Swing,
     * Platform.runLater for JavaFX) if UI updates are needed.</p>
     *
     * @param message the message content, never null
     */
    void onMessage(String message);
}
