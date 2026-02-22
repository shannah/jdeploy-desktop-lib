package ca.weblite.jdeploy.app.bridge;

import ca.weblite.jdeploy.app.hive.Hive;

import java.io.*;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Receives and processes commands in the GUI application.
 * <p>
 * This class handles the GUI side of the bridge, receiving commands from
 * CLI processes and dispatching them to your handler.
 *
 * <h2>Setup</h2>
 * <pre>{@code
 * // 1. Create handler for your commands
 * GuiBridgeHandler handler = (command, params) -> {
 *     return switch (command) {
 *         case "list_notes" -> Map.of("count", "5");
 *         case "create_note" -> {
 *             Note n = store.createNote(params.get("title"), params.get("content"));
 *             yield Map.of("id", n.getId());
 *         }
 *         default -> throw new IllegalArgumentException("Unknown: " + command);
 *     };
 * };
 *
 * // 2. Create receiver
 * GuiBridgeReceiver receiver = new GuiBridgeReceiver(handler);
 *
 * // 3. Register for Hive messages (background IPC)
 * receiver.registerHiveListener();
 *
 * // 4. In your deep link handler, call:
 * receiver.processDeepLink(uri);
 * }</pre>
 *
 * <h2>Threading</h2>
 * <p>The Hive listener runs on a background thread. If your handler needs to
 * interact with the UI, you must dispatch to the UI thread:</p>
 * <pre>{@code
 * // JavaFX
 * receiver.registerHiveListener(requestPath -> {
 *     Platform.runLater(() -> receiver.processRequest(requestPath));
 * });
 *
 * // Swing
 * receiver.registerHiveListener(requestPath -> {
 *     SwingUtilities.invokeLater(() -> receiver.processRequest(requestPath));
 * });
 * }</pre>
 *
 * @see GuiBridge The CLI-side sender
 * @see GuiBridgeHandler The handler interface
 */
public class GuiBridgeReceiver {

    private final GuiBridgeHandler handler;

    /**
     * Creates a new receiver with the specified handler.
     *
     * @param handler the handler that processes commands
     */
    public GuiBridgeReceiver(GuiBridgeHandler handler) {
        this.handler = Objects.requireNonNull(handler, "handler must not be null");
    }

    // =========================================================================
    // REGISTRATION
    // =========================================================================

    /**
     * Registers a Hive listener for background IPC.
     * <p>
     * Messages are processed on the Hive background thread.
     * If your handler needs UI access, use {@link #registerHiveListener(RequestCallback)} instead.
     */
    public void registerHiveListener() {
        Hive.addMessageListener(this::processRequest);
    }

    /**
     * Registers a Hive listener with a custom callback for thread dispatching.
     * <p>
     * Use this to dispatch to your UI thread:
     * <pre>{@code
     * // JavaFX
     * receiver.registerHiveListener(path -> Platform.runLater(() -> receiver.processRequest(path)));
     * }</pre>
     *
     * @param callback called with the request file path when a message arrives
     */
    public void registerHiveListener(RequestCallback callback) {
        Hive.addMessageListener(callback::onRequest);
    }

    /**
     * Callback interface for custom thread dispatching.
     */
    @FunctionalInterface
    public interface RequestCallback {
        void onRequest(String requestFilePath);
    }

    // =========================================================================
    // REQUEST PROCESSING
    // =========================================================================

    /**
     * Processes a deep link URI.
     * <p>
     * Call this from your app's deep link handler. The URI should be in the format:
     * {@code myapp://bridge?request=/path/to/request.properties}
     *
     * @param uri the deep link URI
     */
    public void processDeepLink(URI uri) {
        if (!"bridge".equals(uri.getHost())) {
            return; // Not a bridge request
        }

        String query = uri.getQuery();
        if (query == null) {
            return;
        }

        // Parse query parameters
        Map<String, String> queryParams = parseQuery(query);
        String requestPath = queryParams.get("request");

        if (requestPath != null) {
            processRequest(requestPath);
        }
    }

    /**
     * Processes a request from a file path.
     * <p>
     * This is the core processing method. It reads the request file,
     * invokes the handler, and writes the response.
     *
     * @param requestFilePath path to the request properties file
     */
    public void processRequest(String requestFilePath) {
        String responseFile = null;
        String requestId = "unknown";

        try {
            // Read request
            Properties request = new Properties();
            try (InputStream in = Files.newInputStream(Path.of(requestFilePath))) {
                request.load(in);
            }

            requestId = request.getProperty("id", "unknown");
            String command = request.getProperty("command");
            responseFile = request.getProperty("responseFile");

            if (command == null) {
                throw new IllegalArgumentException("Missing 'command' in request");
            }
            if (responseFile == null) {
                throw new IllegalArgumentException("Missing 'responseFile' in request");
            }

            // Extract params (properties with "param." prefix)
            Map<String, String> params = new HashMap<>();
            for (String key : request.stringPropertyNames()) {
                if (key.startsWith("param.")) {
                    params.put(key.substring(6), request.getProperty(key));
                }
            }

            // Invoke handler
            Map<String, String> result = handler.handleCommand(command, params);

            // Write success response
            writeResponse(responseFile, requestId, result, null);

        } catch (Exception e) {
            // Write error response
            if (responseFile != null) {
                try {
                    writeResponse(responseFile, requestId, null, e.getMessage());
                } catch (IOException ignored) {
                }
            }
        }
    }

    // =========================================================================
    // RESPONSE WRITING
    // =========================================================================

    private void writeResponse(String responseFile, String requestId,
                               Map<String, String> result, String error) throws IOException {
        Properties props = new Properties();
        props.setProperty("id", requestId);

        if (error != null) {
            props.setProperty("status", "error");
            props.setProperty("error", error);
        } else {
            props.setProperty("status", "ok");
            if (result != null) {
                for (Map.Entry<String, String> entry : result.entrySet()) {
                    props.setProperty("result." + entry.getKey(), entry.getValue());
                }
            }
        }

        Path responsePath = Path.of(responseFile);
        Files.createDirectories(responsePath.getParent());
        try (OutputStream out = Files.newOutputStream(responsePath)) {
            props.store(out, "GuiBridge Response");
        }
    }

    // =========================================================================
    // UTILITIES
    // =========================================================================

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return params;
        }
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2) {
                params.put(
                        URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return params;
    }
}
