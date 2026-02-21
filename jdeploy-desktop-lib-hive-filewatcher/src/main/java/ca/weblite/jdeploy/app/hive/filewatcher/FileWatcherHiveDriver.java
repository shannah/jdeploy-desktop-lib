package ca.weblite.jdeploy.app.hive.filewatcher;

import ca.weblite.jdeploy.app.hive.Hive;
import ca.weblite.jdeploy.app.hive.HiveDriver;
import ca.weblite.jdeploy.app.hive.HiveMessageListener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * File-based Hive IPC driver using filesystem watching.
 *
 * <p>Messages are written as files to a shared directory and picked up
 * by other instances via NIO WatchService. Each instance filters out
 * its own messages using a unique instance ID embedded in the filename.</p>
 *
 * <h2>Message Directory</h2>
 * <p>Messages are stored in {@code ~/.jdeploy/messages/{fqn}/} where
 * {@code fqn} (fully qualified name) is derived from system properties:</p>
 * <ul>
 *   <li>If {@code jdeploy.app.source} is set: {@code md5(source).name}</li>
 *   <li>Otherwise: just {@code name}</li>
 * </ul>
 *
 * <h2>File Format</h2>
 * <ul>
 *   <li>Filename: {@code {timestamp}_{instanceId}.msg}</li>
 *   <li>Content: raw UTF-8 message text</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>
 * // Plug-and-play setup (uses system properties)
 * FileWatcherHiveDriver.install();
 *
 * // Or manual configuration
 * FileWatcherHiveDriver driver = new FileWatcherHiveDriver(messageDir, "my-instance");
 * Hive.setDriver(driver);
 * </pre>
 */
public class FileWatcherHiveDriver implements HiveDriver {

    private static final String SYSTEM_PROP_APP_NAME = "jdeploy.app.name";
    private static final String SYSTEM_PROP_APP_SOURCE = "jdeploy.app.source";
    private static final String MESSAGE_EXTENSION = ".msg";
    private static final long TTL_MILLIS = 30_000; // 30 seconds
    private static final long CLEANUP_INTERVAL_MILLIS = 10_000; // 10 seconds
    private static final long POLL_TIMEOUT_MILLIS = 100;
    private static final long FILE_READ_DELAY_MILLIS = 20;

    private final Path messageDir;
    private final String instanceId;
    private final CopyOnWriteArrayList<HiveMessageListener> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> queuedMessages = new CopyOnWriteArrayList<>();

    private volatile boolean running;
    private volatile boolean enabled;
    private WatchService watchService;
    private Thread watcherThread;
    private Thread cleanupThread;

    /**
     * Install this driver as the Hive driver using system properties.
     *
     * <p>Reads {@code jdeploy.app.name} and optionally {@code jdeploy.app.source}
     * to determine the message directory. If {@code jdeploy.app.name} is not set,
     * this method does nothing (IPC silently disabled).</p>
     *
     * <p>Call once at application startup:</p>
     * <pre>
     * public static void main(String[] args) {
     *     FileWatcherHiveDriver.install();
     *     // ... rest of app
     * }
     * </pre>
     */
    public static void install() {
        FileWatcherHiveDriver driver = createFromSystemProperties();
        if (driver != null) {
            Hive.setDriver(driver);
        }
    }

    /**
     * Create a driver using system properties.
     *
     * @return configured driver, or null if required properties are missing
     */
    public static FileWatcherHiveDriver createFromSystemProperties() {
        String appName = System.getProperty(SYSTEM_PROP_APP_NAME);
        if (appName == null || appName.trim().isEmpty()) {
            return null;
        }

        String appSource = System.getProperty(SYSTEM_PROP_APP_SOURCE);
        String fqn = computeFqn(appName, appSource);

        Path baseDir = Paths.get(System.getProperty("user.home"), ".jdeploy", "messages", fqn);
        String instanceId = UUID.randomUUID().toString();

        return new FileWatcherHiveDriver(baseDir, instanceId);
    }

    /**
     * Compute the fully qualified name for the message directory.
     *
     * @param appName the application name (required)
     * @param appSource the application source (optional)
     * @return the FQN: md5(source).name if source is present, otherwise just name
     */
    static String computeFqn(String appName, String appSource) {
        if (appSource == null || appSource.trim().isEmpty()) {
            return sanitizeName(appName);
        }
        return md5(appSource) + "." + sanitizeName(appName);
    }

    /**
     * Sanitize a name for use in a directory path.
     */
    private static String sanitizeName(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * Compute MD5 hash of a string.
     */
    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // MD5 is always available
            throw new RuntimeException(e);
        }
    }

    /**
     * Create a driver with explicit configuration.
     *
     * @param messageDir the directory for message files
     * @param instanceId unique identifier for this instance
     */
    public FileWatcherHiveDriver(Path messageDir, String instanceId) {
        this.messageDir = messageDir;
        this.instanceId = instanceId;
        this.enabled = true;
        start();
    }

    @Override
    public void send(String message) {
        if (!enabled || message == null) {
            return;
        }

        try {
            if (!Files.exists(messageDir)) {
                Files.createDirectories(messageDir);
            }

            String filename = String.format("%d_%s%s",
                    System.currentTimeMillis(), instanceId, MESSAGE_EXTENSION);
            Path messagePath = messageDir.resolve(filename);

            Files.write(messagePath, message.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (IOException e) {
            // Silently ignore send failures
        }
    }

    @Override
    public void addMessageListener(HiveMessageListener listener) {
        if (listener != null) {
            listeners.add(listener);
            // Dispatch any queued messages to the new listener
            dispatchQueuedMessages();
        }
    }

    /**
     * Dispatch any messages that were queued before listeners were added.
     */
    private void dispatchQueuedMessages() {
        if (listeners.isEmpty() || queuedMessages.isEmpty()) {
            return;
        }
        List<String> toDispatch = new ArrayList<>(queuedMessages);
        queuedMessages.clear();
        for (String message : toDispatch) {
            dispatchMessage(message);
        }
    }

    @Override
    public void removeMessageListener(HiveMessageListener listener) {
        listeners.remove(listener);
    }

    @Override
    public boolean isEnabled() {
        return enabled && running;
    }

    @Override
    public void shutdown() {
        enabled = false;
        running = false;

        if (watcherThread != null) {
            watcherThread.interrupt();
        }
        if (cleanupThread != null) {
            cleanupThread.interrupt();
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                // Ignore
            }
        }

        listeners.clear();
        queuedMessages.clear();
    }

    @Override
    public String getInstanceId() {
        return instanceId;
    }

    @Override
    public String getName() {
        return "FileWatcher";
    }

    /**
     * Start the watcher and cleanup threads.
     */
    private void start() {
        try {
            if (!Files.exists(messageDir)) {
                Files.createDirectories(messageDir);
            }

            watchService = FileSystems.getDefault().newWatchService();
            messageDir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);

            running = true;

            // Start watcher thread
            watcherThread = new Thread(this::watchLoop, "Hive-FileWatcher");
            watcherThread.setDaemon(true);
            watcherThread.start();

            // Start cleanup thread
            cleanupThread = new Thread(this::cleanupLoop, "Hive-Cleanup");
            cleanupThread.setDaemon(true);
            cleanupThread.start();

            // Process any existing messages (race condition coverage)
            processExistingMessages();

        } catch (IOException e) {
            enabled = false;
        }
    }

    /**
     * Main watch loop - monitors for new message files.
     */
    private void watchLoop() {
        while (running) {
            try {
                WatchKey key = watchService.poll(POLL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                if (key == null) {
                    continue;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                        Path fileName = (Path) event.context();
                        if (fileName.toString().endsWith(MESSAGE_EXTENSION)) {
                            // Small delay to ensure file is fully written
                            Thread.sleep(FILE_READ_DELAY_MILLIS);
                            processMessageFile(messageDir.resolve(fileName));
                        }
                    }
                }

                key.reset();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Continue watching despite errors
            }
        }
    }

    /**
     * Cleanup loop - removes old message files.
     */
    private void cleanupLoop() {
        while (running) {
            try {
                Thread.sleep(CLEANUP_INTERVAL_MILLIS);
                cleanupOldMessages();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Continue cleanup despite errors
            }
        }
    }

    /**
     * Process any existing message files (for startup race conditions).
     * Messages are sorted by filename which starts with timestamp for ordering.
     */
    private void processExistingMessages() {
        try {
            Files.list(messageDir)
                    .filter(p -> p.toString().endsWith(MESSAGE_EXTENSION))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(this::processMessageFile);
        } catch (IOException e) {
            // Ignore
        }
    }

    /**
     * Process a single message file.
     */
    private void processMessageFile(Path messagePath) {
        try {
            if (!Files.exists(messagePath)) {
                return;
            }

            String filename = messagePath.getFileName().toString();

            // Skip our own messages
            if (filename.contains("_" + instanceId + MESSAGE_EXTENSION)) {
                return;
            }

            String content = new String(Files.readAllBytes(messagePath), StandardCharsets.UTF_8);
            dispatchMessage(content);

        } catch (IOException e) {
            // Ignore read errors
        }
    }

    /**
     * Dispatch a message to all listeners, or queue if none registered.
     */
    private void dispatchMessage(String message) {
        if (listeners.isEmpty()) {
            queuedMessages.add(message);
            return;
        }
        for (HiveMessageListener listener : listeners) {
            try {
                listener.onMessage(message);
            } catch (Exception e) {
                // Don't let one listener break others
            }
        }
    }

    /**
     * Remove message files older than TTL.
     */
    private void cleanupOldMessages() {
        try {
            long cutoff = System.currentTimeMillis() - TTL_MILLIS;

            Files.list(messageDir)
                    .filter(p -> p.toString().endsWith(MESSAGE_EXTENSION))
                    .forEach(path -> {
                        try {
                            long lastModified = Files.getLastModifiedTime(path).toMillis();
                            if (lastModified < cutoff) {
                                Files.deleteIfExists(path);
                            }
                        } catch (IOException e) {
                            // Ignore deletion errors
                        }
                    });
        } catch (IOException e) {
            // Ignore
        }
    }
}
