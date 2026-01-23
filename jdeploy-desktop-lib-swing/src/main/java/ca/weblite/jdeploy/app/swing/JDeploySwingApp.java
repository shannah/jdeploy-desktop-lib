package ca.weblite.jdeploy.app.swing;

import ca.weblite.jdeploy.app.JDeployOpenHandler;
import ca.weblite.jdeploy.app.JDeploySingletonWatcher;

import javax.swing.SwingUtilities;
import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.util.Collections;
import java.util.List;

/**
 * Singleton support for Swing applications.
 *
 * <p>Ensures all callbacks happen on the Event Dispatch Thread.
 * On macOS, also hooks into the native Desktop API for file/URI handling.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 * public class MyApp {
 *     private JFrame mainWindow;
 *
 *     public void init() {
 *         mainWindow = new JFrame("My App");
 *         // ... setup UI ...
 *
 *         JDeploySwingApp.setOpenHandler(new JDeployOpenHandler() {
 *             public void openFiles(List&lt;File&gt; files) {
 *                 for (File file : files) {
 *                     openDocument(file);
 *                 }
 *             }
 *
 *             public void openURIs(List&lt;URI&gt; uris) {
 *                 for (URI uri : uris) {
 *                     handleDeepLink(uri);
 *                 }
 *             }
 *
 *             public void appActivated() {
 *                 mainWindow.setState(JFrame.NORMAL);
 *                 mainWindow.toFront();
 *                 mainWindow.requestFocus();
 *             }
 *         });
 *
 *         mainWindow.setVisible(true);
 *     }
 * }
 * </pre>
 *
 * @since 1.0.0
 */
public class JDeploySwingApp {

    private static volatile JDeployOpenHandler userHandler;

    static {
        // Initialize the singleton watcher
        JDeploySingletonWatcher.initialize();

        // On macOS, also hook into native Desktop API
        setupMacOSHandlers();
    }

    private static void setupMacOSHandlers() {
        if (!Desktop.isDesktopSupported()) {
            return;
        }

        Desktop desktop = Desktop.getDesktop();

        // Try to set OpenFilesHandler (only supported on macOS)
        try {
            desktop.setOpenFileHandler(event -> {
                List<File> files = event.getFiles();
                if (files != null && !files.isEmpty()) {
                    dispatchOnEDT(() -> {
                        JDeployOpenHandler h = userHandler;
                        if (h != null) {
                            h.openFiles(Collections.unmodifiableList(files));
                        } else {
                            // Queue for later dispatch via singleton watcher
                            JDeploySingletonWatcher.dispatchFiles(files);
                        }
                    });
                }
            });
        } catch (UnsupportedOperationException e) {
            // Not on macOS, ignore
        }

        // Try to set OpenURIHandler (only supported on macOS)
        try {
            desktop.setOpenURIHandler(event -> {
                URI uri = event.getURI();
                if (uri != null) {
                    dispatchOnEDT(() -> {
                        JDeployOpenHandler h = userHandler;
                        if (h != null) {
                            h.openURIs(Collections.singletonList(uri));
                        } else {
                            // Queue for later dispatch via singleton watcher
                            JDeploySingletonWatcher.dispatchURIs(Collections.singletonList(uri));
                        }
                    });
                }
            });
        } catch (UnsupportedOperationException e) {
            // Not on macOS, ignore
        }
    }

    /**
     * Register your file/URI handler.
     * All callbacks will be dispatched on the Swing Event Dispatch Thread.
     *
     * @param handler your implementation of JDeployOpenHandler
     */
    public static void setOpenHandler(JDeployOpenHandler handler) {
        userHandler = handler;

        // Create a wrapper that ensures EDT dispatch
        JDeployOpenHandler edtWrapper = new JDeployOpenHandler() {
            @Override
            public void openFiles(List<File> files) {
                dispatchOnEDT(() -> {
                    JDeployOpenHandler h = userHandler;
                    if (h != null) {
                        h.openFiles(files);
                    }
                });
            }

            @Override
            public void openURIs(List<URI> uris) {
                dispatchOnEDT(() -> {
                    JDeployOpenHandler h = userHandler;
                    if (h != null) {
                        h.openURIs(uris);
                    }
                });
            }

            @Override
            public void appActivated() {
                dispatchOnEDT(() -> {
                    JDeployOpenHandler h = userHandler;
                    if (h != null) {
                        h.appActivated();
                    }
                });
            }
        };

        // Register the wrapper with the singleton watcher
        JDeploySingletonWatcher.setOpenHandler(edtWrapper);

        // Dispatch any queued events on the EDT
        SwingUtilities.invokeLater(JDeploySingletonWatcher::dispatchQueuedEvents);
    }

    /**
     * Dispatch a runnable on the EDT.
     * If already on EDT, runs immediately. Otherwise, uses invokeLater.
     *
     * @param runnable the code to run on EDT
     */
    private static void dispatchOnEDT(Runnable runnable) {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
        } else {
            SwingUtilities.invokeLater(runnable);
        }
    }

    /**
     * Check if a handler has been registered.
     * Primarily for testing.
     *
     * @return true if a handler is registered
     */
    static boolean hasHandler() {
        return userHandler != null;
    }

    /**
     * Clear the handler. Primarily for testing.
     */
    static void clearHandler() {
        userHandler = null;
    }
}
