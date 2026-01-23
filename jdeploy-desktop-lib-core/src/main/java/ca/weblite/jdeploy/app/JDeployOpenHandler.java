package ca.weblite.jdeploy.app;

import java.io.File;
import java.net.URI;
import java.util.List;

/**
 * Handler for file/URI open events and application activation.
 *
 * <p>Implement this interface and register with {@link ca.weblite.jdeploy.app.swing.JDeploySwingApp}
 * or {@link ca.weblite.jdeploy.app.javafx.JDeployFXApp} to receive notifications when:</p>
 * <ul>
 *   <li>Files are opened (double-click, drag-drop, "Open With")</li>
 *   <li>Custom URL schemes are activated (deep links)</li>
 *   <li>The application is activated (user clicks dock icon, etc.)</li>
 * </ul>
 *
 * <p>For Swing applications, all methods are called on the Event Dispatch Thread.
 * For JavaFX applications, all methods are called on the JavaFX Application Thread.</p>
 *
 * @since 1.0.0
 */
public interface JDeployOpenHandler {

    /**
     * Called when one or more files should be opened.
     *
     * <p>This method is called when:</p>
     * <ul>
     *   <li>User double-clicks associated files</li>
     *   <li>User drags files onto the application</li>
     *   <li>User selects "Open With" from context menu</li>
     *   <li>Another instance forwards files to this instance (singleton mode)</li>
     * </ul>
     *
     * @param files the files to open, never null or empty
     */
    void openFiles(List<File> files);

    /**
     * Called when one or more URIs should be handled.
     *
     * <p>This method is called when custom URL schemes registered by the application
     * are activated. Common uses include deep linking and OAuth callbacks.</p>
     *
     * @param uris the URIs to handle, never null or empty
     */
    void openURIs(List<URI> uris);

    /**
     * Called when the application should be brought to the foreground.
     *
     * <p>This method is called when:</p>
     * <ul>
     *   <li>User clicks the dock/taskbar icon while app is running</li>
     *   <li>Another instance activates this instance (singleton mode)</li>
     * </ul>
     *
     * <p>Typical implementation:</p>
     * <pre>
     * // Swing
     * frame.setState(JFrame.NORMAL);
     * frame.toFront();
     * frame.requestFocus();
     *
     * // JavaFX
     * stage.setIconified(false);
     * stage.toFront();
     * stage.requestFocus();
     * </pre>
     */
    void appActivated();
}
