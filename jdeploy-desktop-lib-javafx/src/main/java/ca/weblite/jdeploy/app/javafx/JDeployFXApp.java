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
 * <h2>Usage</h2>
 * <pre>
 * public class MyApp extends Application {
 *     private Stage primaryStage;
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
        // Initialize the singleton watcher
        JDeploySingletonWatcher.initialize();
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
