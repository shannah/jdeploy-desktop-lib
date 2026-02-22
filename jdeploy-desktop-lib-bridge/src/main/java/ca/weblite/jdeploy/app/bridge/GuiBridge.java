package ca.weblite.jdeploy.app.bridge;

import ca.weblite.jdeploy.app.JDeployDesktopLib;
import ca.weblite.jdeploy.app.hive.Hive;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Bridges commands from a CLI process to a GUI application.
 * <p>
 * This class enables inter-process communication between a CLI process
 * (such as an MCP server) and a GUI application. It supports two IPC mechanisms:
 *
 * <h2>Basic: Deep Links (Foreground)</h2>
 * <p>Uses custom URL schemes (e.g., {@code myapp://command?file=/path/to/request})
 * to send commands. This approach:</p>
 * <ul>
 *   <li>Always works - launches app if not running</li>
 *   <li>Brings the app to the foreground</li>
 *   <li>Best for commands that should show UI</li>
 * </ul>
 *
 * <h2>Advanced: Hive IPC (Background)</h2>
 * <p>Uses file-based IPC via Hive for background messaging. This approach:</p>
 * <ul>
 *   <li>Processes commands without interrupting the user</li>
 *   <li>GUI stays in background</li>
 *   <li>Falls back to deep link if GUI isn't running</li>
 *   <li>Best for data queries</li>
 * </ul>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * GuiBridge bridge = new GuiBridge("myapp");
 *
 * // Background command (GUI stays in background)
 * Map<String, String> notes = bridge.sendCommand("list_notes", Map.of());
 *
 * // Foreground command (brings GUI to front)
 * bridge.sendCommandViaDeepLink("show_note", Map.of("id", "abc123"));
 * }</pre>
 *
 * @see GuiBridgeReceiver The GUI-side receiver
 * @see GuiBridgeHandler The handler interface for processing commands
 */
public class GuiBridge {

    private static final long DEFAULT_TIMEOUT_MS = 10_000;
    private static final long DEFAULT_POLL_INTERVAL_MS = 100;

    private final String urlScheme;
    private long timeoutMs = DEFAULT_TIMEOUT_MS;
    private long pollIntervalMs = DEFAULT_POLL_INTERVAL_MS;

    /**
     * Creates a new GuiBridge with the specified URL scheme.
     *
     * @param urlScheme the custom URL scheme registered for the app (e.g., "myapp")
     */
    public GuiBridge(String urlScheme) {
        this.urlScheme = Objects.requireNonNull(urlScheme, "urlScheme must not be null");
    }

    /**
     * Sets the timeout for waiting for responses.
     *
     * @param timeoutMs timeout in milliseconds (default: 10000)
     * @return this builder for chaining
     */
    public GuiBridge withTimeout(long timeoutMs) {
        this.timeoutMs = timeoutMs;
        return this;
    }

    /**
     * Sets the poll interval for checking response files.
     *
     * @param pollIntervalMs poll interval in milliseconds (default: 100)
     * @return this builder for chaining
     */
    public GuiBridge withPollInterval(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
        return this;
    }

    // =========================================================================
    // PUBLIC API
    // =========================================================================

    /**
     * Sends a command to the GUI using background IPC (Advanced approach).
     * <p>
     * Tries Hive IPC first. If the GUI doesn't respond (timeout),
     * falls back to deep link to launch the app.
     *
     * @param command the command name
     * @param params  the command parameters
     * @return the result from the GUI
     * @throws IOException if communication fails
     */
    public Map<String, String> sendCommand(String command, Map<String, String> params)
            throws IOException {
        RequestContext ctx = createRequest(command, params);

        // Try background IPC first
        sendViaHive(ctx.requestPath);
        Map<String, String> result = waitForResponse(ctx);

        if (result != null) {
            return result;
        }

        // GUI didn't respond - maybe not running. Launch via deep link.
        sendViaDeepLink(ctx.requestPath);
        result = waitForResponse(ctx);

        if (result != null) {
            return result;
        }

        cleanup(ctx);
        throw new IOException("Timeout waiting for GUI response. Is the application running?");
    }

    /**
     * Sends a command to the GUI using deep link (Basic approach).
     * <p>
     * Always uses deep links, which brings the GUI to the foreground.
     * If the app isn't running, it will be launched.
     *
     * @param command the command name
     * @param params  the command parameters
     * @return the result from the GUI
     * @throws IOException if communication fails
     */
    public Map<String, String> sendCommandViaDeepLink(String command, Map<String, String> params)
            throws IOException {
        RequestContext ctx = createRequest(command, params);

        sendViaDeepLink(ctx.requestPath);
        Map<String, String> result = waitForResponse(ctx);

        if (result != null) {
            return result;
        }

        cleanup(ctx);
        throw new IOException("Timeout waiting for GUI response. Is the application running?");
    }

    // =========================================================================
    // REQUEST/RESPONSE HANDLING
    // =========================================================================

    private static class RequestContext {
        final Path tempDir;
        final Path requestPath;
        final Path responsePath;

        RequestContext(Path tempDir, Path requestPath, Path responsePath) {
            this.tempDir = tempDir;
            this.requestPath = requestPath;
            this.responsePath = responsePath;
        }
    }

    /**
     * Creates a request file using Java Properties format.
     */
    private RequestContext createRequest(String command, Map<String, String> params)
            throws IOException {
        String requestId = UUID.randomUUID().toString().substring(0, 8);

        Path tempDir = Files.createTempDirectory("guibridge");
        Path requestPath = tempDir.resolve(requestId + "-request.properties");
        Path responsePath = tempDir.resolve(requestId + "-response.properties");

        // Build request properties
        Properties props = new Properties();
        props.setProperty("id", requestId);
        props.setProperty("command", command);
        props.setProperty("responseFile", responsePath.toAbsolutePath().toString());

        // Add params with "param." prefix
        for (Map.Entry<String, String> entry : params.entrySet()) {
            props.setProperty("param." + entry.getKey(), entry.getValue());
        }

        // Write request file
        try (OutputStream out = Files.newOutputStream(requestPath)) {
            props.store(out, "GuiBridge Request");
        }

        return new RequestContext(tempDir, requestPath, responsePath);
    }

    /**
     * Waits for the GUI to write a response file.
     */
    private Map<String, String> waitForResponse(RequestContext ctx) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(ctx.responsePath) && Files.size(ctx.responsePath) > 0) {
                // Read response
                Properties props = new Properties();
                try (InputStream in = Files.newInputStream(ctx.responsePath)) {
                    props.load(in);
                }

                cleanup(ctx);

                // Check for error
                String status = props.getProperty("status", "ok");
                if ("error".equals(status)) {
                    throw new IOException(props.getProperty("error", "Unknown error"));
                }

                // Extract result (properties with "result." prefix)
                Map<String, String> result = new HashMap<>();
                for (String key : props.stringPropertyNames()) {
                    if (key.startsWith("result.")) {
                        result.put(key.substring(7), props.getProperty(key));
                    }
                }
                return result;
            }

            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for response");
            }
        }

        return null; // Timeout
    }

    private static void cleanup(RequestContext ctx) {
        try {
            Files.deleteIfExists(ctx.requestPath);
            Files.deleteIfExists(ctx.responsePath);
            Files.deleteIfExists(ctx.tempDir);
        } catch (IOException ignored) {
        }
    }

    // =========================================================================
    // IPC MECHANISMS
    // =========================================================================

    private static void sendViaHive(Path requestPath) {
        Hive.send(requestPath.toAbsolutePath().toString());
    }

    private void sendViaDeepLink(Path requestPath) throws IOException {
        String encoded = URLEncoder.encode(
                requestPath.toAbsolutePath().toString(),
                StandardCharsets.UTF_8);
        String url = urlScheme + "://bridge?request=" + encoded;
        JDeployDesktopLib.openUrl(url);
    }
}
