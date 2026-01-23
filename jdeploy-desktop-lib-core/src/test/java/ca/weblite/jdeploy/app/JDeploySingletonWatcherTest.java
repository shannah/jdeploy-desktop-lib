package ca.weblite.jdeploy.app;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class JDeploySingletonWatcherTest {

    private List<File> receivedFiles;
    private List<URI> receivedURIs;
    private int activateCount;

    private JDeployOpenHandler testHandler;

    @Before
    public void setUp() {
        receivedFiles = new ArrayList<>();
        receivedURIs = new ArrayList<>();
        activateCount = 0;

        testHandler = new JDeployOpenHandler() {
            @Override
            public void openFiles(List<File> files) {
                receivedFiles.addAll(files);
            }

            @Override
            public void openURIs(List<URI> uris) {
                receivedURIs.addAll(uris);
            }

            @Override
            public void appActivated() {
                activateCount++;
            }
        };
    }

    @After
    public void tearDown() {
        JDeploySingletonWatcher.shutdown();
    }

    // ===========================================
    // JSON Array Parsing Tests
    // ===========================================

    @Test
    public void testParseJsonArray_empty() {
        List<String> result = JDeploySingletonWatcher.parseJsonArray("[]");
        assertTrue(result.isEmpty());
    }

    @Test
    public void testParseJsonArray_emptyWithWhitespace() {
        List<String> result = JDeploySingletonWatcher.parseJsonArray("[  ]");
        assertTrue(result.isEmpty());
    }

    @Test
    public void testParseJsonArray_simpleStrings() {
        List<String> result = JDeploySingletonWatcher.parseJsonArray("[\"a\", \"b\", \"c\"]");
        assertEquals(3, result.size());
        assertEquals("a", result.get(0));
        assertEquals("b", result.get(1));
        assertEquals("c", result.get(2));
    }

    @Test
    public void testParseJsonArray_stringsWithSpaces() {
        List<String> result = JDeploySingletonWatcher.parseJsonArray("[\"hello world\", \"foo bar\"]");
        assertEquals(2, result.size());
        assertEquals("hello world", result.get(0));
        assertEquals("foo bar", result.get(1));
    }

    @Test
    public void testParseJsonArray_escapedNewline() {
        List<String> result = JDeploySingletonWatcher.parseJsonArray("[\"line1\\nline2\"]");
        assertEquals(1, result.size());
        assertEquals("line1\nline2", result.get(0));
    }

    @Test
    public void testParseJsonArray_escapedTab() {
        List<String> result = JDeploySingletonWatcher.parseJsonArray("[\"col1\\tcol2\"]");
        assertEquals(1, result.size());
        assertEquals("col1\tcol2", result.get(0));
    }

    @Test
    public void testParseJsonArray_escapedBackslash() {
        List<String> result = JDeploySingletonWatcher.parseJsonArray("[\"C:\\\\Users\\\\test\"]");
        assertEquals(1, result.size());
        assertEquals("C:\\Users\\test", result.get(0));
    }

    @Test
    public void testParseJsonArray_escapedQuote() {
        List<String> result = JDeploySingletonWatcher.parseJsonArray("[\"say \\\"hello\\\"\"]");
        assertEquals(1, result.size());
        assertEquals("say \"hello\"", result.get(0));
    }

    @Test
    public void testParseJsonArray_windowsPath() {
        List<String> result = JDeploySingletonWatcher.parseJsonArray(
                "[\"C:\\\\Users\\\\test\\\\Documents\\\\file.txt\"]");
        assertEquals(1, result.size());
        assertEquals("C:\\Users\\test\\Documents\\file.txt", result.get(0));
    }

    @Test
    public void testParseJsonArray_unixPath() {
        List<String> result = JDeploySingletonWatcher.parseJsonArray(
                "[\"/home/user/Documents/file.txt\"]");
        assertEquals(1, result.size());
        assertEquals("/home/user/Documents/file.txt", result.get(0));
    }

    @Test
    public void testParseJsonArray_null() {
        List<String> result = JDeploySingletonWatcher.parseJsonArray(null);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testParseJsonArray_invalidFormat() {
        List<String> result = JDeploySingletonWatcher.parseJsonArray("not an array");
        assertTrue(result.isEmpty());
    }

    // ===========================================
    // Request File Parsing Tests
    // ===========================================

    @Test
    public void testParseRequest_singleOpenFile() {
        JDeploySingletonWatcher.setOpenHandler(testHandler);

        JDeploySingletonWatcher watcher = createWatcherForTesting();
        String path = "/path/to/file.txt";
        watcher.parseAndDispatchRequests("OPEN_FILE:" + path.length() + ":" + path + "\n");

        assertEquals(1, receivedFiles.size());
        assertEquals(path, receivedFiles.get(0).getPath());
    }

    @Test
    public void testParseRequest_multipleOpenFiles() {
        JDeploySingletonWatcher.setOpenHandler(testHandler);

        JDeploySingletonWatcher watcher = createWatcherForTesting();
        String path1 = "/path/to/file.txt";
        String path2 = "/path/to/file2.txt";
        watcher.parseAndDispatchRequests(
                "OPEN_FILE:" + path1.length() + ":" + path1 + "\n" +
                "OPEN_FILE:" + path2.length() + ":" + path2 + "\n");

        assertEquals(2, receivedFiles.size());
        assertEquals(path1, receivedFiles.get(0).getPath());
        assertEquals(path2, receivedFiles.get(1).getPath());
    }

    @Test
    public void testParseRequest_openUri() {
        JDeploySingletonWatcher.setOpenHandler(testHandler);

        JDeploySingletonWatcher watcher = createWatcherForTesting();
        String uri = "myapp://open?id=123";
        watcher.parseAndDispatchRequests("OPEN_URI:" + uri.length() + ":" + uri + "\n");

        assertEquals(1, receivedURIs.size());
        assertEquals(uri, receivedURIs.get(0).toString());
    }

    @Test
    public void testParseRequest_activate() {
        JDeploySingletonWatcher.setOpenHandler(testHandler);

        JDeploySingletonWatcher watcher = createWatcherForTesting();
        watcher.parseAndDispatchRequests("ACTIVATE\n");

        assertEquals(1, activateCount);
    }

    @Test
    public void testParseRequest_mixedContent() {
        JDeploySingletonWatcher.setOpenHandler(testHandler);

        JDeploySingletonWatcher watcher = createWatcherForTesting();
        String path1 = "/path/to/file.txt";
        String path2 = "/path/to/file2.txt";
        String uri = "myapp://open?id=123";
        watcher.parseAndDispatchRequests(
                "OPEN_FILE:" + path1.length() + ":" + path1 + "\n" +
                "OPEN_URI:" + uri.length() + ":" + uri + "\n" +
                "ACTIVATE\n" +
                "OPEN_FILE:" + path2.length() + ":" + path2 + "\n");

        assertEquals(2, receivedFiles.size());
        assertEquals(1, receivedURIs.size());
        assertEquals(1, activateCount);
    }

    @Test
    public void testParseRequest_pathWithSpaces() {
        JDeploySingletonWatcher.setOpenHandler(testHandler);

        JDeploySingletonWatcher watcher = createWatcherForTesting();
        String path = "/path/to/my file name.txt";
        watcher.parseAndDispatchRequests("OPEN_FILE:" + path.length() + ":" + path + "\n");

        assertEquals(1, receivedFiles.size());
        assertEquals(path, receivedFiles.get(0).getPath());
    }

    @Test
    public void testParseRequest_pathWithNewline() {
        JDeploySingletonWatcher.setOpenHandler(testHandler);

        JDeploySingletonWatcher watcher = createWatcherForTesting();
        // Path contains a literal newline character (unusual but possible)
        String pathWithNewline = "/path/to/file\nwith\nnewlines.txt";
        String request = "OPEN_FILE:" + pathWithNewline.length() + ":" + pathWithNewline + "\n";
        watcher.parseAndDispatchRequests(request);

        assertEquals(1, receivedFiles.size());
        assertEquals(pathWithNewline, receivedFiles.get(0).getPath());
    }

    @Test
    public void testParseRequest_windowsPath() {
        JDeploySingletonWatcher.setOpenHandler(testHandler);

        JDeploySingletonWatcher watcher = createWatcherForTesting();
        String windowsPath = "C:\\Users\\test\\Documents\\file.txt";
        watcher.parseAndDispatchRequests("OPEN_FILE:" + windowsPath.length() + ":" + windowsPath + "\n");

        assertEquals(1, receivedFiles.size());
        assertEquals(windowsPath, receivedFiles.get(0).getPath());
    }

    @Test
    public void testParseRequest_emptyContent() {
        JDeploySingletonWatcher.setOpenHandler(testHandler);

        JDeploySingletonWatcher watcher = createWatcherForTesting();
        watcher.parseAndDispatchRequests("");

        assertEquals(0, receivedFiles.size());
        assertEquals(0, receivedURIs.size());
        assertEquals(0, activateCount);
    }

    @Test
    public void testParseRequest_whitespaceOnly() {
        JDeploySingletonWatcher.setOpenHandler(testHandler);

        JDeploySingletonWatcher watcher = createWatcherForTesting();
        watcher.parseAndDispatchRequests("   \n\n   \n");

        assertEquals(0, receivedFiles.size());
        assertEquals(0, receivedURIs.size());
        assertEquals(0, activateCount);
    }

    // ===========================================
    // Event Queuing Tests
    // ===========================================

    @Test
    public void testEventQueuing_filesQueuedWithoutHandler() {
        // Dispatch without handler set
        List<File> files = new ArrayList<>();
        files.add(new File("/test/file.txt"));
        JDeploySingletonWatcher.dispatchFiles(files);

        // Now set handler and dispatch queued events
        JDeploySingletonWatcher.setOpenHandler(testHandler);
        JDeploySingletonWatcher.dispatchQueuedEvents();

        assertEquals(1, receivedFiles.size());
        assertEquals("/test/file.txt", receivedFiles.get(0).getPath());
    }

    @Test
    public void testEventQueuing_urisQueuedWithoutHandler() {
        // Dispatch without handler set
        List<URI> uris = new ArrayList<>();
        uris.add(URI.create("myapp://test"));
        JDeploySingletonWatcher.dispatchURIs(uris);

        // Now set handler and dispatch queued events
        JDeploySingletonWatcher.setOpenHandler(testHandler);
        JDeploySingletonWatcher.dispatchQueuedEvents();

        assertEquals(1, receivedURIs.size());
        assertEquals("myapp://test", receivedURIs.get(0).toString());
    }

    @Test
    public void testEventQueuing_activateQueuedWithoutHandler() {
        // Dispatch without handler set
        JDeploySingletonWatcher.dispatchActivate();

        // Now set handler and dispatch queued events
        JDeploySingletonWatcher.setOpenHandler(testHandler);
        JDeploySingletonWatcher.dispatchQueuedEvents();

        assertEquals(1, activateCount);
    }

    @Test
    public void testEventQueuing_multipleEventsBatched() {
        // Queue multiple events
        List<File> files1 = new ArrayList<>();
        files1.add(new File("/test/file1.txt"));
        JDeploySingletonWatcher.dispatchFiles(files1);

        List<File> files2 = new ArrayList<>();
        files2.add(new File("/test/file2.txt"));
        JDeploySingletonWatcher.dispatchFiles(files2);

        JDeploySingletonWatcher.dispatchActivate();
        JDeploySingletonWatcher.dispatchActivate();

        // Set handler and dispatch
        JDeploySingletonWatcher.setOpenHandler(testHandler);
        JDeploySingletonWatcher.dispatchQueuedEvents();

        assertEquals(2, receivedFiles.size());
        assertEquals(1, activateCount); // Activate should be coalesced or just set once
    }

    @Test
    public void testEventQueuing_dispatchImmediatelyWithHandler() {
        JDeploySingletonWatcher.setOpenHandler(testHandler);

        List<File> files = new ArrayList<>();
        files.add(new File("/test/file.txt"));
        JDeploySingletonWatcher.dispatchFiles(files);

        // Should be dispatched immediately
        assertEquals(1, receivedFiles.size());
    }

    @Test
    public void testEventQueuing_queueClearedAfterDispatch() {
        // Queue events
        List<File> files = new ArrayList<>();
        files.add(new File("/test/file.txt"));
        JDeploySingletonWatcher.dispatchFiles(files);

        // Dispatch
        JDeploySingletonWatcher.setOpenHandler(testHandler);
        JDeploySingletonWatcher.dispatchQueuedEvents();

        assertEquals(1, receivedFiles.size());

        // Clear received and dispatch again - should be empty
        receivedFiles.clear();
        JDeploySingletonWatcher.dispatchQueuedEvents();

        assertEquals(0, receivedFiles.size());
    }

    // ===========================================
    // Integration Tests
    // ===========================================

    @Test
    public void testIntegration_existingRequestFileProcessing() throws Exception {
        // Skip on macOS - watcher is not used on macOS
        Assume.assumeFalse("Skipping on macOS - watcher not used",
                System.getProperty("os.name", "").toLowerCase().contains("mac"));

        // Create temporary IPC directory
        Path tempDir = Files.createTempDirectory("jdeploy-test");
        Path inboxDir = tempDir.resolve("inbox");
        Files.createDirectories(inboxDir);

        try {
            // Create a request file BEFORE initializing (simulates race condition)
            String path = "/path/to/file.txt";
            String requestContent = "OPEN_FILE:" + path.length() + ":" + path + "\nACTIVATE\n";
            Path requestFile = inboxDir.resolve("test.request");
            Files.write(requestFile, requestContent.getBytes(StandardCharsets.UTF_8));

            // Set up system property and initialize
            System.setProperty("jdeploy.singleton.ipcdir", tempDir.toString());
            JDeploySingletonWatcher.setOpenHandler(testHandler);
            JDeploySingletonWatcher.initialize();

            assertTrue(JDeploySingletonWatcher.isActive());

            // Wait for processing of existing files
            Thread.sleep(100);

            assertEquals(1, receivedFiles.size());
            assertEquals(path, receivedFiles.get(0).getPath());
            assertEquals(1, activateCount);

            // Request file should be deleted
            assertFalse(Files.exists(requestFile));

        } finally {
            System.clearProperty("jdeploy.singleton.ipcdir");
            JDeploySingletonWatcher.shutdown();
            deleteDirectory(tempDir.toFile());
        }
    }

    @Test
    public void testIntegration_initialPropertiesProcessed() throws Exception {
        // Skip on macOS - watcher is not used on macOS
        Assume.assumeFalse("Skipping on macOS - watcher not used",
                System.getProperty("os.name", "").toLowerCase().contains("mac"));

        // Create temporary IPC directory
        Path tempDir = Files.createTempDirectory("jdeploy-test");
        Files.createDirectories(tempDir.resolve("inbox"));

        try {
            // Set up system properties
            System.setProperty("jdeploy.singleton.ipcdir", tempDir.toString());
            System.setProperty("jdeploy.singleton.openFiles", "[\"/initial/file.txt\"]");
            System.setProperty("jdeploy.singleton.openURIs", "[\"myapp://initial\"]");

            JDeploySingletonWatcher.initialize();
            JDeploySingletonWatcher.setOpenHandler(testHandler);
            JDeploySingletonWatcher.dispatchQueuedEvents();

            assertEquals(1, receivedFiles.size());
            assertEquals("/initial/file.txt", receivedFiles.get(0).getPath());
            assertEquals(1, receivedURIs.size());
            assertEquals("myapp://initial", receivedURIs.get(0).toString());

        } finally {
            System.clearProperty("jdeploy.singleton.ipcdir");
            System.clearProperty("jdeploy.singleton.openFiles");
            System.clearProperty("jdeploy.singleton.openURIs");
            JDeploySingletonWatcher.shutdown();
            deleteDirectory(tempDir.toFile());
        }
    }

    // ===========================================
    // Helper Methods
    // ===========================================

    private JDeploySingletonWatcher createWatcherForTesting() {
        // Use reflection to create instance for testing parseAndDispatchRequests
        try {
            java.lang.reflect.Constructor<JDeploySingletonWatcher> constructor =
                    JDeploySingletonWatcher.class.getDeclaredConstructor(Path.class);
            constructor.setAccessible(true);
            return constructor.newInstance(Files.createTempDirectory("test"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        dir.delete();
    }
}
