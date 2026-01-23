package ca.weblite.jdeploy.app;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the singleton watcher functionality.
 *
 * <p>These tests are disabled on macOS because the singleton watcher
 * is not used on macOS (macOS handles single-instance behavior natively).</p>
 */
@Timeout(10)
@DisabledOnOs(OS.MAC)
class SingletonIntegrationTest {

    @TempDir
    Path tempDir;

    private Path ipcDir;
    private Path inboxDir;
    private List<File> receivedFiles;
    private List<URI> receivedURIs;
    private AtomicInteger activateCount;
    private int requestCounter = 0;

    @BeforeEach
    void setUp() throws IOException {
        ipcDir = tempDir.resolve("ipc");
        inboxDir = ipcDir.resolve("inbox");
        Files.createDirectories(inboxDir);

        receivedFiles = new ArrayList<>();
        receivedURIs = new ArrayList<>();
        activateCount = new AtomicInteger(0);
        requestCounter = 0;
    }

    @AfterEach
    void tearDown() {
        JDeploySingletonWatcher.shutdown();
        System.clearProperty("jdeploy.singleton.ipcdir");
        System.clearProperty("jdeploy.singleton.openFiles");
        System.clearProperty("jdeploy.singleton.openURIs");
    }

    // ===========================================
    // Test: Watcher detects new request file
    // ===========================================

    @Test
    void testWatcherDetectsNewRequestFile() throws Exception {
        System.setProperty("jdeploy.singleton.ipcdir", ipcDir.toString());

        CountDownLatch latch = new CountDownLatch(1);
        JDeployOpenHandler handler = createHandler(latch, 1, 0, false);

        JDeploySingletonWatcher.initialize();
        JDeploySingletonWatcher.setOpenHandler(handler);

        // Give watcher time to start
        Thread.sleep(100);

        // Write request file
        String path = "/test/document.txt";
        writeRequestFile(formatOpenFile(path));

        // Wait for processing
        assertTrue(latch.await(2, TimeUnit.SECONDS), "Handler should be called");
        assertEquals(1, receivedFiles.size());
        assertEquals(path, receivedFiles.get(0).getPath());
    }

    // ===========================================
    // Test: Request file format parsing - files with spaces
    // ===========================================

    @Test
    void testParsingFilesWithSpaces() throws Exception {
        System.setProperty("jdeploy.singleton.ipcdir", ipcDir.toString());

        CountDownLatch latch = new CountDownLatch(1);
        JDeployOpenHandler handler = createHandler(latch, 1, 0, false);

        JDeploySingletonWatcher.initialize();
        JDeploySingletonWatcher.setOpenHandler(handler);
        Thread.sleep(100);

        String path = "/path/to/my document file.txt";
        writeRequestFile(formatOpenFile(path));

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(1, receivedFiles.size());
        assertEquals(path, receivedFiles.get(0).getPath());
    }

    // ===========================================
    // Test: Request file format parsing - files with newlines
    // ===========================================

    @Test
    void testParsingFilesWithNewlines() throws Exception {
        System.setProperty("jdeploy.singleton.ipcdir", ipcDir.toString());

        CountDownLatch latch = new CountDownLatch(1);
        JDeployOpenHandler handler = createHandler(latch, 1, 0, false);

        JDeploySingletonWatcher.initialize();
        JDeploySingletonWatcher.setOpenHandler(handler);
        Thread.sleep(100);

        // Path with actual newline character
        String path = "/path/to/file\nwith\nnewlines.txt";
        writeRequestFile(formatOpenFile(path));

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(1, receivedFiles.size());
        assertEquals(path, receivedFiles.get(0).getPath());
    }

    // ===========================================
    // Test: Request file format parsing - URIs
    // ===========================================

    @Test
    void testParsingURIs() throws Exception {
        System.setProperty("jdeploy.singleton.ipcdir", ipcDir.toString());

        CountDownLatch latch = new CountDownLatch(1);
        JDeployOpenHandler handler = createHandler(latch, 0, 1, false);

        JDeploySingletonWatcher.initialize();
        JDeploySingletonWatcher.setOpenHandler(handler);
        Thread.sleep(100);

        String uri = "myapp://open?id=123&foo=bar";
        writeRequestFile(formatOpenUri(uri));

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(1, receivedURIs.size());
        assertEquals(uri, receivedURIs.get(0).toString());
    }

    // ===========================================
    // Test: Request file format parsing - ACTIVATE
    // ===========================================

    @Test
    void testParsingActivate() throws Exception {
        System.setProperty("jdeploy.singleton.ipcdir", ipcDir.toString());

        CountDownLatch latch = new CountDownLatch(1);
        JDeployOpenHandler handler = createHandler(latch, 0, 0, true);

        JDeploySingletonWatcher.initialize();
        JDeploySingletonWatcher.setOpenHandler(handler);
        Thread.sleep(100);

        writeRequestFile("ACTIVATE");

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(1, activateCount.get());
    }

    // ===========================================
    // Test: Request file format parsing - mixed content
    // ===========================================

    @Test
    void testParsingMixedContent() throws Exception {
        System.setProperty("jdeploy.singleton.ipcdir", ipcDir.toString());

        // Expect 2 files + 1 URI + 1 activate = handler called multiple times
        // We'll use a latch that counts down for each event type
        CountDownLatch fileLatch = new CountDownLatch(2);
        CountDownLatch uriLatch = new CountDownLatch(1);
        CountDownLatch activateLatch = new CountDownLatch(1);

        JDeployOpenHandler handler = new JDeployOpenHandler() {
            @Override
            public void openFiles(List<File> files) {
                receivedFiles.addAll(files);
                for (int i = 0; i < files.size(); i++) {
                    fileLatch.countDown();
                }
            }

            @Override
            public void openURIs(List<URI> uris) {
                receivedURIs.addAll(uris);
                for (int i = 0; i < uris.size(); i++) {
                    uriLatch.countDown();
                }
            }

            @Override
            public void appActivated() {
                activateCount.incrementAndGet();
                activateLatch.countDown();
            }
        };

        JDeploySingletonWatcher.initialize();
        JDeploySingletonWatcher.setOpenHandler(handler);
        Thread.sleep(100);

        String file1 = "/path/to/file1.txt";
        String file2 = "/path/to/file2.txt";
        String uri = "myapp://action";

        writeRequestFile(
                formatOpenFile(file1),
                formatOpenUri(uri),
                "ACTIVATE",
                formatOpenFile(file2)
        );

        assertTrue(fileLatch.await(2, TimeUnit.SECONDS), "Files should be received");
        assertTrue(uriLatch.await(2, TimeUnit.SECONDS), "URI should be received");
        assertTrue(activateLatch.await(2, TimeUnit.SECONDS), "Activate should be received");

        assertEquals(2, receivedFiles.size());
        assertEquals(file1, receivedFiles.get(0).getPath());
        assertEquals(file2, receivedFiles.get(1).getPath());
        assertEquals(1, receivedURIs.size());
        assertEquals(uri, receivedURIs.get(0).toString());
        assertEquals(1, activateCount.get());
    }

    // ===========================================
    // Test: Initial files from system properties
    // ===========================================

    @Test
    void testInitialFilesFromSystemProperties() throws Exception {
        System.setProperty("jdeploy.singleton.ipcdir", ipcDir.toString());
        System.setProperty("jdeploy.singleton.openFiles",
                "[\"/initial/file1.txt\", \"/initial/file2.txt\"]");

        CountDownLatch latch = new CountDownLatch(2);
        JDeployOpenHandler handler = createHandler(latch, 2, 0, false);

        JDeploySingletonWatcher.initialize();
        JDeploySingletonWatcher.setOpenHandler(handler);
        JDeploySingletonWatcher.dispatchQueuedEvents();

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(2, receivedFiles.size());
        assertEquals("/initial/file1.txt", receivedFiles.get(0).getPath());
        assertEquals("/initial/file2.txt", receivedFiles.get(1).getPath());
    }

    // ===========================================
    // Test: Initial URIs from system properties
    // ===========================================

    @Test
    void testInitialURIsFromSystemProperties() throws Exception {
        System.setProperty("jdeploy.singleton.ipcdir", ipcDir.toString());
        System.setProperty("jdeploy.singleton.openURIs",
                "[\"myapp://open?id=1\", \"myapp://open?id=2\"]");

        CountDownLatch latch = new CountDownLatch(2);
        JDeployOpenHandler handler = createHandler(latch, 0, 2, false);

        JDeploySingletonWatcher.initialize();
        JDeploySingletonWatcher.setOpenHandler(handler);
        JDeploySingletonWatcher.dispatchQueuedEvents();

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(2, receivedURIs.size());
        assertEquals("myapp://open?id=1", receivedURIs.get(0).toString());
        assertEquals("myapp://open?id=2", receivedURIs.get(1).toString());
    }

    // ===========================================
    // Test: Events queued before handler registration
    // ===========================================

    @Test
    void testEventsQueuedBeforeHandlerRegistration() throws Exception {
        System.setProperty("jdeploy.singleton.ipcdir", ipcDir.toString());
        System.setProperty("jdeploy.singleton.openFiles", "[\"/queued/file.txt\"]");

        // Initialize watcher (events will be queued)
        JDeploySingletonWatcher.initialize();

        // Wait briefly
        Thread.sleep(100);

        // Now register handler
        CountDownLatch latch = new CountDownLatch(1);
        JDeployOpenHandler handler = createHandler(latch, 1, 0, false);

        JDeploySingletonWatcher.setOpenHandler(handler);
        JDeploySingletonWatcher.dispatchQueuedEvents();

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(1, receivedFiles.size());
        assertEquals("/queued/file.txt", receivedFiles.get(0).getPath());
    }

    // ===========================================
    // Test: Request file deleted after processing
    // ===========================================

    @Test
    void testRequestFileDeletedAfterProcessing() throws Exception {
        System.setProperty("jdeploy.singleton.ipcdir", ipcDir.toString());

        CountDownLatch latch = new CountDownLatch(1);
        JDeployOpenHandler handler = createHandler(latch, 1, 0, false);

        JDeploySingletonWatcher.initialize();
        JDeploySingletonWatcher.setOpenHandler(handler);
        Thread.sleep(100);

        Path requestFile = writeRequestFileAndGetPath(formatOpenFile("/test/file.txt"));

        assertTrue(latch.await(2, TimeUnit.SECONDS));

        // Give a bit more time for deletion
        Thread.sleep(100);

        assertFalse(Files.exists(requestFile), "Request file should be deleted after processing");
    }

    // ===========================================
    // Test: Windows paths with backslashes
    // ===========================================

    @Test
    void testWindowsPathsWithBackslashes() throws Exception {
        System.setProperty("jdeploy.singleton.ipcdir", ipcDir.toString());

        CountDownLatch latch = new CountDownLatch(1);
        JDeployOpenHandler handler = createHandler(latch, 1, 0, false);

        JDeploySingletonWatcher.initialize();
        JDeploySingletonWatcher.setOpenHandler(handler);
        Thread.sleep(100);

        String path = "C:\\Users\\test\\Documents\\file.txt";
        writeRequestFile(formatOpenFile(path));

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(1, receivedFiles.size());
        assertEquals(path, receivedFiles.get(0).getPath());
    }

    // ===========================================
    // Test: Multiple request files processed in order
    // ===========================================

    @Test
    void testMultipleRequestFilesProcessed() throws Exception {
        System.setProperty("jdeploy.singleton.ipcdir", ipcDir.toString());

        CountDownLatch latch = new CountDownLatch(3);
        JDeployOpenHandler handler = createHandler(latch, 3, 0, false);

        JDeploySingletonWatcher.initialize();
        JDeploySingletonWatcher.setOpenHandler(handler);
        Thread.sleep(100);

        // Write multiple request files
        writeRequestFile(formatOpenFile("/file1.txt"));
        Thread.sleep(50);
        writeRequestFile(formatOpenFile("/file2.txt"));
        Thread.sleep(50);
        writeRequestFile(formatOpenFile("/file3.txt"));

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals(3, receivedFiles.size());
    }

    // ===========================================
    // Test: JSON escaping in system properties
    // ===========================================

    @Test
    void testJsonEscapingInSystemProperties() throws Exception {
        System.setProperty("jdeploy.singleton.ipcdir", ipcDir.toString());
        // Test escaped characters: newline, tab, backslash, quote
        System.setProperty("jdeploy.singleton.openFiles",
                "[\"/path/with\\nnewline.txt\", \"C:\\\\Users\\\\test\\\\file.txt\"]");

        CountDownLatch latch = new CountDownLatch(2);
        JDeployOpenHandler handler = createHandler(latch, 2, 0, false);

        JDeploySingletonWatcher.initialize();
        JDeploySingletonWatcher.setOpenHandler(handler);
        JDeploySingletonWatcher.dispatchQueuedEvents();

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(2, receivedFiles.size());
        assertEquals("/path/with\nnewline.txt", receivedFiles.get(0).getPath());
        assertEquals("C:\\Users\\test\\file.txt", receivedFiles.get(1).getPath());
    }

    // ===========================================
    // Helper Methods
    // ===========================================

    private JDeployOpenHandler createHandler(CountDownLatch latch,
                                              int expectedFiles,
                                              int expectedURIs,
                                              boolean expectActivate) {
        return new JDeployOpenHandler() {
            @Override
            public void openFiles(List<File> files) {
                receivedFiles.addAll(files);
                for (int i = 0; i < files.size() && latch.getCount() > 0; i++) {
                    if (expectedFiles > 0) {
                        latch.countDown();
                    }
                }
            }

            @Override
            public void openURIs(List<URI> uris) {
                receivedURIs.addAll(uris);
                for (int i = 0; i < uris.size() && latch.getCount() > 0; i++) {
                    if (expectedURIs > 0) {
                        latch.countDown();
                    }
                }
            }

            @Override
            public void appActivated() {
                activateCount.incrementAndGet();
                if (expectActivate && latch.getCount() > 0) {
                    latch.countDown();
                }
            }
        };
    }

    private void writeRequestFile(String... lines) throws IOException {
        writeRequestFileAndGetPath(lines);
    }

    private Path writeRequestFileAndGetPath(String... lines) throws IOException {
        String filename = String.format("%d-%d.request",
                System.currentTimeMillis(), requestCounter++);
        Path requestFile = inboxDir.resolve(filename);
        StringBuilder content = new StringBuilder();
        for (String line : lines) {
            content.append(line).append("\n");
        }
        Files.write(requestFile, content.toString().getBytes(StandardCharsets.UTF_8));
        return requestFile;
    }

    private String formatOpenFile(String path) {
        return "OPEN_FILE:" + path.length() + ":" + path;
    }

    private String formatOpenUri(String uri) {
        return "OPEN_URI:" + uri.length() + ":" + uri;
    }
}
