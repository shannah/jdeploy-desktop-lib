package ca.weblite.jdeploy.app;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Monitors an inbox directory for singleton IPC requests from secondary launcher instances.
 *
 * <p>When jDeploy launches an application in singleton mode and another instance is already
 * running, the secondary instance writes request files to an inbox directory. This class
 * watches that directory and dispatches events to the registered {@link JDeployOpenHandler}.</p>
 *
 * <p>Request files use a length-prefixed format:</p>
 * <pre>
 * TYPE:LENGTH:CONTENT
 * ACTIVATE
 * </pre>
 *
 * <p>This class is automatically initialized by the Swing and JavaFX modules. Applications
 * should not need to interact with it directly.</p>
 *
 * @since 1.0.0
 */
public class JDeploySingletonWatcher {

    private static final String PROP_IPC_DIR = "jdeploy.singleton.ipcdir";
    private static final String PROP_OPEN_FILES = "jdeploy.singleton.openFiles";
    private static final String PROP_OPEN_URIS = "jdeploy.singleton.openURIs";

    private static final String TYPE_OPEN_FILE = "OPEN_FILE";
    private static final String TYPE_OPEN_URI = "OPEN_URI";
    private static final String TYPE_ACTIVATE = "ACTIVATE";

    private static JDeploySingletonWatcher instance;
    private static volatile JDeployOpenHandler handler;

    private static final List<File> queuedFiles = new CopyOnWriteArrayList<>();
    private static final List<URI> queuedURIs = new CopyOnWriteArrayList<>();
    private static volatile boolean queuedActivate = false;

    private final Path ipcDir;
    private final Path inboxDir;
    private WatchService watchService;
    private Thread watcherThread;
    private volatile boolean running;

    private JDeploySingletonWatcher(Path ipcDir) {
        this.ipcDir = ipcDir;
        this.inboxDir = ipcDir.resolve("inbox");
    }

    /**
     * Initialize the singleton watcher. Called automatically by
     * JDeploySwingApp or JDeployFXApp static initializers.
     *
     * <p>Reads the {@code jdeploy.singleton.ipcdir} system property to determine
     * the IPC directory. If not set, singleton mode is disabled and this method
     * returns without doing anything.</p>
     */
    public static synchronized void initialize() {
        if (instance != null) {
            return;
        }

        String ipcDirPath = System.getProperty(PROP_IPC_DIR);
        if (ipcDirPath == null || ipcDirPath.isEmpty()) {
            return;
        }

        Path ipcDir = Paths.get(ipcDirPath);
        if (!Files.isDirectory(ipcDir)) {
            log("IPC directory does not exist: " + ipcDirPath);
            return;
        }

        instance = new JDeploySingletonWatcher(ipcDir);
        instance.start();
        instance.processInitialProperties();
    }

    /**
     * Register the handler for file/URI events.
     *
     * <p>If events arrived before registration, they remain queued until
     * {@link #dispatchQueuedEvents()} is called on the appropriate UI thread.</p>
     *
     * @param openHandler the handler to receive events
     */
    public static void setOpenHandler(JDeployOpenHandler openHandler) {
        handler = openHandler;
    }

    /**
     * Dispatch any events that were queued before handler registration.
     *
     * <p>Called on the appropriate thread by Swing/JavaFX modules after
     * the handler is registered and the UI is ready.</p>
     */
    public static void dispatchQueuedEvents() {
        if (handler == null) {
            return;
        }

        if (!queuedFiles.isEmpty()) {
            List<File> files = new ArrayList<>(queuedFiles);
            queuedFiles.clear();
            dispatchFiles(files);
        }

        if (!queuedURIs.isEmpty()) {
            List<URI> uris = new ArrayList<>(queuedURIs);
            queuedURIs.clear();
            dispatchURIs(uris);
        }

        if (queuedActivate) {
            queuedActivate = false;
            dispatchActivate();
        }
    }

    /**
     * Dispatch file open events to the handler or queue them.
     *
     * @param files the files to dispatch
     */
    public static void dispatchFiles(List<File> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        JDeployOpenHandler h = handler;
        if (h != null) {
            try {
                h.openFiles(Collections.unmodifiableList(files));
            } catch (Exception e) {
                log("Error dispatching files: " + e.getMessage());
            }
        } else {
            queuedFiles.addAll(files);
        }
    }

    /**
     * Dispatch URI open events to the handler or queue them.
     *
     * @param uris the URIs to dispatch
     */
    public static void dispatchURIs(List<URI> uris) {
        if (uris == null || uris.isEmpty()) {
            return;
        }
        JDeployOpenHandler h = handler;
        if (h != null) {
            try {
                h.openURIs(Collections.unmodifiableList(uris));
            } catch (Exception e) {
                log("Error dispatching URIs: " + e.getMessage());
            }
        } else {
            queuedURIs.addAll(uris);
        }
    }

    /**
     * Dispatch activation event to the handler or queue it.
     */
    public static void dispatchActivate() {
        JDeployOpenHandler h = handler;
        if (h != null) {
            try {
                h.appActivated();
            } catch (Exception e) {
                log("Error dispatching activate: " + e.getMessage());
            }
        } else {
            queuedActivate = true;
        }
    }

    private void start() {
        try {
            if (!Files.exists(inboxDir)) {
                Files.createDirectories(inboxDir);
            }

            watchService = FileSystems.getDefault().newWatchService();
            inboxDir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);

            running = true;
            watcherThread = new Thread(this::watchLoop, "jDeploy-SingletonWatcher");
            watcherThread.setDaemon(true);
            watcherThread.start();

            // Process any existing requests (race condition coverage)
            processExistingRequests();

        } catch (IOException e) {
            log("Failed to start watcher: " + e.getMessage());
        }
    }

    private void watchLoop() {
        while (running) {
            try {
                WatchKey key = watchService.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (key == null) {
                    continue;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                        Path fileName = (Path) event.context();
                        if (fileName.toString().endsWith(".request")) {
                            // Small delay to ensure file is fully written
                            Thread.sleep(20);
                            processRequestFile(inboxDir.resolve(fileName));
                        }
                    }
                }

                key.reset();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log("Error in watch loop: " + e.getMessage());
            }
        }
    }

    private void processExistingRequests() {
        try {
            Files.list(inboxDir)
                    .filter(p -> p.toString().endsWith(".request"))
                    .sorted()
                    .forEach(this::processRequestFile);
        } catch (IOException e) {
            log("Error processing existing requests: " + e.getMessage());
        }
    }

    private void processRequestFile(Path requestFile) {
        try {
            if (!Files.exists(requestFile)) {
                return;
            }

            String content = new String(Files.readAllBytes(requestFile), StandardCharsets.UTF_8);
            parseAndDispatchRequests(content);

            Files.deleteIfExists(requestFile);
        } catch (IOException e) {
            log("Error processing request file: " + e.getMessage());
        }
    }

    private void processInitialProperties() {
        String openFilesJson = System.getProperty(PROP_OPEN_FILES);
        if (openFilesJson != null && !openFilesJson.isEmpty()) {
            List<String> paths = parseJsonArray(openFilesJson);
            List<File> files = new ArrayList<>();
            for (String path : paths) {
                files.add(new File(path));
            }
            if (!files.isEmpty()) {
                dispatchFiles(files);
            }
        }

        String openUrisJson = System.getProperty(PROP_OPEN_URIS);
        if (openUrisJson != null && !openUrisJson.isEmpty()) {
            List<String> uriStrings = parseJsonArray(openUrisJson);
            List<URI> uris = new ArrayList<>();
            for (String uriString : uriStrings) {
                try {
                    uris.add(new URI(uriString));
                } catch (URISyntaxException e) {
                    log("Invalid URI in system property: " + uriString);
                }
            }
            if (!uris.isEmpty()) {
                dispatchURIs(uris);
            }
        }
    }

    /**
     * Parse request content and dispatch events.
     *
     * <p>Format: TYPE:LENGTH:CONTENT or just ACTIVATE</p>
     *
     * @param content the request file content
     */
    void parseAndDispatchRequests(String content) {
        List<File> files = new ArrayList<>();
        List<URI> uris = new ArrayList<>();
        boolean activate = false;

        int pos = 0;
        while (pos < content.length()) {
            // Skip whitespace
            while (pos < content.length() && Character.isWhitespace(content.charAt(pos))) {
                pos++;
            }
            if (pos >= content.length()) {
                break;
            }

            // Find the type (up to first ':' or end of line for ACTIVATE)
            int colonPos = content.indexOf(':', pos);
            int newlinePos = content.indexOf('\n', pos);
            if (newlinePos == -1) {
                newlinePos = content.length();
            }

            String type;
            if (colonPos == -1 || colonPos > newlinePos) {
                // No colon before newline - must be ACTIVATE
                type = content.substring(pos, newlinePos).trim();
                pos = newlinePos + 1;

                if (TYPE_ACTIVATE.equals(type)) {
                    activate = true;
                }
                continue;
            }

            type = content.substring(pos, colonPos);
            pos = colonPos + 1;

            if (TYPE_ACTIVATE.equals(type)) {
                activate = true;
                // Skip to next line
                pos = newlinePos + 1;
                continue;
            }

            // Read length
            int lengthEnd = content.indexOf(':', pos);
            if (lengthEnd == -1) {
                log("Malformed request: missing length delimiter");
                break;
            }

            int length;
            try {
                length = Integer.parseInt(content.substring(pos, lengthEnd));
            } catch (NumberFormatException e) {
                log("Malformed request: invalid length");
                break;
            }
            pos = lengthEnd + 1;

            // Read content of specified length
            if (pos + length > content.length()) {
                log("Malformed request: content shorter than specified length");
                break;
            }

            String payload = content.substring(pos, pos + length);
            pos = pos + length;

            // Skip trailing newline if present
            if (pos < content.length() && content.charAt(pos) == '\n') {
                pos++;
            }

            if (TYPE_OPEN_FILE.equals(type)) {
                files.add(new File(payload));
            } else if (TYPE_OPEN_URI.equals(type)) {
                try {
                    uris.add(new URI(payload));
                } catch (URISyntaxException e) {
                    log("Invalid URI in request: " + payload);
                }
            }
        }

        // Dispatch collected events
        if (!files.isEmpty()) {
            dispatchFiles(files);
        }
        if (!uris.isEmpty()) {
            dispatchURIs(uris);
        }
        if (activate) {
            dispatchActivate();
        }
    }

    /**
     * Parse a JSON array of strings.
     *
     * <p>Handles escape sequences: \n, \t, \r, \\, \"</p>
     *
     * @param json the JSON array string
     * @return list of parsed strings
     */
    static List<String> parseJsonArray(String json) {
        List<String> result = new ArrayList<>();
        if (json == null) {
            return result;
        }

        json = json.trim();
        if (!json.startsWith("[") || !json.endsWith("]")) {
            return result;
        }

        // Remove brackets
        json = json.substring(1, json.length() - 1).trim();
        if (json.isEmpty()) {
            return result;
        }

        int pos = 0;
        while (pos < json.length()) {
            // Skip whitespace
            while (pos < json.length() && Character.isWhitespace(json.charAt(pos))) {
                pos++;
            }
            if (pos >= json.length()) {
                break;
            }

            // Expect opening quote
            if (json.charAt(pos) != '"') {
                // Skip to next comma or end
                int nextComma = json.indexOf(',', pos);
                pos = nextComma == -1 ? json.length() : nextComma + 1;
                continue;
            }
            pos++; // Skip opening quote

            // Parse string content
            StringBuilder sb = new StringBuilder();
            while (pos < json.length()) {
                char c = json.charAt(pos);
                if (c == '"') {
                    // End of string
                    pos++;
                    break;
                } else if (c == '\\' && pos + 1 < json.length()) {
                    // Escape sequence
                    char next = json.charAt(pos + 1);
                    switch (next) {
                        case 'n':
                            sb.append('\n');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '"':
                            sb.append('"');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        default:
                            sb.append(next);
                            break;
                    }
                    pos += 2;
                } else {
                    sb.append(c);
                    pos++;
                }
            }

            result.add(sb.toString());

            // Skip to next comma
            while (pos < json.length() && json.charAt(pos) != ',') {
                pos++;
            }
            pos++; // Skip comma
        }

        return result;
    }

    private static void log(String message) {
        System.err.println("jDeploy: " + message);
    }

    /**
     * Shutdown the watcher. Primarily for testing.
     */
    public static synchronized void shutdown() {
        if (instance != null) {
            instance.running = false;
            if (instance.watcherThread != null) {
                instance.watcherThread.interrupt();
            }
            if (instance.watchService != null) {
                try {
                    instance.watchService.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
            instance = null;
        }
        handler = null;
        queuedFiles.clear();
        queuedURIs.clear();
        queuedActivate = false;
    }

    /**
     * Check if singleton mode is active. Primarily for testing.
     *
     * @return true if the watcher is running
     */
    static boolean isActive() {
        return instance != null && instance.running;
    }
}
