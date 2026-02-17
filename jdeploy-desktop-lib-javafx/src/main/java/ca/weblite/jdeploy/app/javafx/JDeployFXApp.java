package ca.weblite.jdeploy.app.javafx;

import ca.weblite.jdeploy.app.JDeployOpenHandler;
import ca.weblite.jdeploy.app.JDeploySingletonWatcher;

import javafx.application.Platform;
import java.io.File;
import java.net.URI;
import java.util.List;

/**
 * Singleton support for JavaFX applications.
 *
 * <p>Ensures all callbacks happen on the JavaFX Application Thread.</p>
 *
 * <h2>Platform Behavior</h2>
 * <ul>
 *   <li><b>macOS:</b> macOS handles single-instance behavior natively. File/URI opens
 *       are routed to the running instance automatically by the OS. On macOS, this class
 *       relies on the AWT Desktop API (which works with JavaFX) to receive these events.</li>
 *   <li><b>Windows/Linux:</b> Uses file-based IPC via {@link JDeploySingletonWatcher}.
 *       The jDeploy launcher detects an existing instance and writes request files
 *       that this library monitors and dispatches to your handler.</li>
 * </ul>
 *
 * <h2>Important: Initialization Order on macOS</h2>
 * <p>On macOS, the AWT Desktop handlers must be registered <b>before</b> JavaFX starts.
 * You must call {@link #initialize()} in your {@code main()} method before calling
 * {@code Application.launch()}. Failure to do so will result in deep links and file
 * associations not working on macOS.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 * public class MyApp extends Application {
 *     private Stage primaryStage;
 *
 *     public static void main(String[] args) {
 *         // REQUIRED: Initialize before launching JavaFX
 *         JDeployFXApp.initialize();
 *         launch(args);
 *     }
 *
 *     &#64;Override
 *     public void start(Stage stage) {
 *         this.primaryStage = stage;
 *         // ... setup UI ...
 *
 *         JDeployFXApp.setOpenHandler(new JDeployOpenHandler() {
 *             public void openFiles(List&lt;File&gt; files) {
 *                 files.forEach(f -&gt; openDocument(f));
 *             }
 *
 *             public void openURIs(List&lt;URI&gt; uris) {
 *                 uris.forEach(u -&gt; handleDeepLink(u));
 *             }
 *
 *             public void appActivated() {
 *                 primaryStage.setIconified(false);
 *                 primaryStage.toFront();
 *                 primaryStage.requestFocus();
 *             }
 *         });
 *
 *         stage.show();
 *     }
 * }
 * </pre>
 *
 * @since 1.0.0
 */
public class JDeployFXApp {

    private static volatile JDeployOpenHandler userHandler;

    static {
        // Initialize file-based IPC watcher (no-op on macOS, active on Windows/Linux)
        JDeploySingletonWatcher.initialize();

        // On macOS, hook into native Desktop API for file/URI handling
        setupMacOSHandlers();
    }

    /**
     * Initialize JDeployFXApp before launching JavaFX.
     *
     * <p><b>IMPORTANT:</b> On macOS, this method MUST be called in your {@code main()}
     * method BEFORE calling {@code Application.launch()}. This ensures the AWT Desktop
     * handlers are registered while AWT is still the primary toolkit. If JavaFX starts
     * first, deep links and file associations will not work on macOS.</p>
     *
     * <p>On Windows and Linux, calling this method is optional but recommended for
     * consistency.</p>
     *
     * <h3>Example:</h3>
     * <pre>
     * public static void main(String[] args) {
     *     JDeployFXApp.initialize();  // Must be before launch()
     *     launch(args);
     * }
     * </pre>
     *
     * @since 1.0.1
     */
    public static void initialize() {
        // This method's purpose is to force the static initializer to run.
        // The actual initialization happens in the static block above.
        // This is a no-op, but calling it ensures the class is fully initialized.
    }

    private static void setupMacOSHandlers() {
        if (!java.awt.Desktop.isDesktopSupported()) {
            return;
        }

        java.awt.Desktop desktop = java.awt.Desktop.getDesktop();

        // Try to set OpenFilesHandler (only supported on macOS)
        try {
            desktop.setOpenFileHandler(event -> {
                java.util.List<File> files = event.getFiles();
                if (files != null && !files.isEmpty()) {
                    dispatchOnFXThread(() -> {
                        JDeployOpenHandler h = userHandler;
                        if (h != null) {
                            h.openFiles(java.util.Collections.unmodifiableList(files));
                        } else {
                            // Queue for later dispatch via singleton watcher
                            JDeploySingletonWatcher.dispatchFiles(files);
                        }
                    });
                }
            });
        } catch (UnsupportedOperationException e) {
            // OpenFileHandler not supported on this platform
        }

        // Try to set OpenURIHandler (only supported on macOS)
        try {
            desktop.setOpenURIHandler(event -> {
                java.net.URI uri = event.getURI();
                if (uri != null) {
                    dispatchOnFXThread(() -> {
                        JDeployOpenHandler h = userHandler;
                        if (h != null) {
                            h.openURIs(java.util.Collections.singletonList(uri));
                        } else {
                            // Queue for later dispatch via singleton watcher
                            JDeploySingletonWatcher.dispatchURIs(java.util.Collections.singletonList(uri));
                        }
                    });
                }
            });
        } catch (UnsupportedOperationException e) {
            // OpenURIHandler not supported on this platform
        }
    }

    /**
     * Register your file/URI handler.
     * All callbacks will be dispatched on the JavaFX Application Thread.
     *
     * @param handler your implementation of JDeployOpenHandler
     */
    public static void setOpenHandler(JDeployOpenHandler handler) {
        userHandler = handler;

        // Create a wrapper that ensures FX Application Thread dispatch
        JDeployOpenHandler fxWrapper = new JDeployOpenHandler() {
            @Override
            public void openFiles(List<File> files) {
                dispatchOnFXThread(() -> {
                    JDeployOpenHandler h = userHandler;
                    if (h != null) {
                        h.openFiles(files);
                    }
                });
            }

            @Override
            public void openURIs(List<URI> uris) {
                dispatchOnFXThread(() -> {
                    JDeployOpenHandler h = userHandler;
                    if (h != null) {
                        h.openURIs(uris);
                    }
                });
            }

            @Override
            public void appActivated() {
                dispatchOnFXThread(() -> {
                    JDeployOpenHandler h = userHandler;
                    if (h != null) {
                        h.appActivated();
                    }
                });
            }
        };

        // Register the wrapper with the singleton watcher
        JDeploySingletonWatcher.setOpenHandler(fxWrapper);

        // Dispatch any queued events on the FX Application Thread
        Platform.runLater(JDeploySingletonWatcher::dispatchQueuedEvents);
    }

    /**
     * Dispatch a runnable on the FX Application Thread.
     * If already on FX thread, runs immediately. Otherwise, uses runLater.
     *
     * @param runnable the code to run on FX Application Thread
     */
    private static void dispatchOnFXThread(Runnable runnable) {
        if (Platform.isFxApplicationThread()) {
            runnable.run();
        } else {
            Platform.runLater(runnable);
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