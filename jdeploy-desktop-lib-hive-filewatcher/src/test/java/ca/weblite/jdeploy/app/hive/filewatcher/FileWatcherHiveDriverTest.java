package ca.weblite.jdeploy.app.hive.filewatcher;

import ca.weblite.jdeploy.app.hive.Hive;
import ca.weblite.jdeploy.app.hive.HiveMessageListener;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
 * Unit tests for FileWatcherHiveDriver.
 */
@Timeout(10)
class FileWatcherHiveDriverTest {

    @TempDir
    Path tempDir;

    private Path messageDir;
    private FileWatcherHiveDriver driver;

    @BeforeEach
    void setUp() throws IOException {
        messageDir = tempDir.resolve("messages");
        Files.createDirectories(messageDir);
    }

    @AfterEach
    void tearDown() {
        if (driver != null) {
            driver.shutdown();
        }
        Hive.shutdown();
        System.clearProperty("jdeploy.app.name");
        System.clearProperty("jdeploy.app.source");
    }

    // ========== FQN Computation Tests ==========

    @Test
    void testComputeFqn_nameOnly() {
        String fqn = FileWatcherHiveDriver.computeFqn("myapp", null);
        assertEquals("myapp", fqn);
    }

    @Test
    void testComputeFqn_nameOnlyEmptySource() {
        String fqn = FileWatcherHiveDriver.computeFqn("myapp", "");
        assertEquals("myapp", fqn);
    }

    @Test
    void testComputeFqn_nameOnlyWhitespaceSource() {
        String fqn = FileWatcherHiveDriver.computeFqn("myapp", "   ");
        assertEquals("myapp", fqn);
    }

    @Test
    void testComputeFqn_withSource() {
        String fqn = FileWatcherHiveDriver.computeFqn("myapp", "https://example.com/app");
        // Should be md5(source).name
        assertTrue(fqn.endsWith(".myapp"));
        assertTrue(fqn.length() > "myapp".length());
        // MD5 is 32 hex chars
        assertEquals(32, fqn.indexOf("."));
    }

    @Test
    void testComputeFqn_sanitizesName() {
        String fqn = FileWatcherHiveDriver.computeFqn("my app/with:special*chars", null);
        assertFalse(fqn.contains(" "));
        assertFalse(fqn.contains("/"));
        assertFalse(fqn.contains(":"));
        assertFalse(fqn.contains("*"));
    }

    @Test
    void testComputeFqn_sameSourceProducesSameHash() {
        String fqn1 = FileWatcherHiveDriver.computeFqn("app", "https://example.com");
        String fqn2 = FileWatcherHiveDriver.computeFqn("app", "https://example.com");
        assertEquals(fqn1, fqn2);
    }

    @Test
    void testComputeFqn_differentSourceProducesDifferentHash() {
        String fqn1 = FileWatcherHiveDriver.computeFqn("app", "https://example1.com");
        String fqn2 = FileWatcherHiveDriver.computeFqn("app", "https://example2.com");
        assertNotEquals(fqn1, fqn2);
    }

    // ========== Driver Creation Tests ==========

    @Test
    void testCreateFromSystemProperties_noAppName() {
        System.clearProperty("jdeploy.app.name");
        FileWatcherHiveDriver created = FileWatcherHiveDriver.createFromSystemProperties();
        assertNull(created);
    }

    @Test
    void testCreateFromSystemProperties_emptyAppName() {
        System.setProperty("jdeploy.app.name", "");
        FileWatcherHiveDriver created = FileWatcherHiveDriver.createFromSystemProperties();
        assertNull(created);
    }

    @Test
    void testCreateFromSystemProperties_withAppName() {
        System.setProperty("jdeploy.app.name", "testapp");
        FileWatcherHiveDriver created = FileWatcherHiveDriver.createFromSystemProperties();
        assertNotNull(created);
        assertTrue(created.isEnabled());
        created.shutdown();
    }

    @Test
    void testInstall_setsHiveDriver() {
        System.setProperty("jdeploy.app.name", "testapp");
        FileWatcherHiveDriver.install();
        assertNotNull(Hive.getDriver());
        assertTrue(Hive.getDriver() instanceof FileWatcherHiveDriver);
    }

    @Test
    void testInstall_noAppNameDoesNotSetDriver() {
        System.clearProperty("jdeploy.app.name");
        Hive.setDriver(null);
        FileWatcherHiveDriver.install();
        assertNull(Hive.getDriver());
    }

    // ========== Message Sending Tests ==========

    @Test
    void testSend_createsMessageFile() throws Exception {
        driver = new FileWatcherHiveDriver(messageDir, "test-instance");

        driver.send("Hello World");

        // Give it a moment to write
        Thread.sleep(50);

        long count = Files.list(messageDir)
                .filter(p -> p.toString().endsWith(".msg"))
                .count();
        assertEquals(1, count);
    }

    @Test
    void testSend_fileContainsMessage() throws Exception {
        driver = new FileWatcherHiveDriver(messageDir, "test-instance");

        driver.send("Hello World");
        Thread.sleep(50);

        Path msgFile = Files.list(messageDir)
                .filter(p -> p.toString().endsWith(".msg"))
                .findFirst()
                .orElseThrow();

        String content = Files.readString(msgFile, StandardCharsets.UTF_8);
        assertEquals("Hello World", content);
    }

    @Test
    void testSend_fileNameFormat() throws Exception {
        driver = new FileWatcherHiveDriver(messageDir, "my-instance-id");

        driver.send("test");
        Thread.sleep(50);

        Path msgFile = Files.list(messageDir)
                .filter(p -> p.toString().endsWith(".msg"))
                .findFirst()
                .orElseThrow();

        String filename = msgFile.getFileName().toString();
        assertTrue(filename.contains("my-instance-id"), "Filename should contain instance ID");
        assertTrue(filename.endsWith(".msg"), "Filename should end with .msg");
        assertTrue(filename.matches("\\d+_my-instance-id\\.msg"), "Filename format: timestamp_instanceId.msg");
    }

    @Test
    void testSend_createsDirectoryIfNeeded() throws Exception {
        Path newDir = tempDir.resolve("newdir");
        assertFalse(Files.exists(newDir));

        driver = new FileWatcherHiveDriver(newDir, "test");
        driver.send("test");
        Thread.sleep(50);

        assertTrue(Files.exists(newDir));
    }

    // ========== Message Receiving Tests ==========

    @Test
    void testReceive_detectsNewMessages() throws Exception {
        driver = new FileWatcherHiveDriver(messageDir, "receiver");
        CountDownLatch latch = new CountDownLatch(1);
        List<String> received = new ArrayList<>();

        driver.addMessageListener(msg -> {
            received.add(msg);
            latch.countDown();
        });

        // Simulate another instance writing a message
        Thread.sleep(100); // Let watcher start
        writeMessageFile("other-instance", "Hello from other");

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(1, received.size());
        assertEquals("Hello from other", received.get(0));
    }

    @Test
    void testReceive_filtersOwnMessages() throws Exception {
        driver = new FileWatcherHiveDriver(messageDir, "my-instance");
        AtomicInteger count = new AtomicInteger(0);

        driver.addMessageListener(msg -> count.incrementAndGet());

        Thread.sleep(100);

        // Write message from self
        writeMessageFile("my-instance", "from self");
        Thread.sleep(200);

        assertEquals(0, count.get(), "Should not receive own messages");
    }

    @Test
    void testReceive_multipleListeners() throws Exception {
        driver = new FileWatcherHiveDriver(messageDir, "receiver");
        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger count = new AtomicInteger(0);

        driver.addMessageListener(msg -> {
            count.incrementAndGet();
            latch.countDown();
        });
        driver.addMessageListener(msg -> {
            count.incrementAndGet();
            latch.countDown();
        });

        Thread.sleep(100);
        writeMessageFile("sender", "test");

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(2, count.get());
    }

    @Test
    void testReceive_listenerExceptionDoesNotBreakOthers() throws Exception {
        driver = new FileWatcherHiveDriver(messageDir, "receiver");
        CountDownLatch latch = new CountDownLatch(1);
        List<String> received = new ArrayList<>();

        driver.addMessageListener(msg -> {
            throw new RuntimeException("Intentional");
        });
        driver.addMessageListener(msg -> {
            received.add(msg);
            latch.countDown();
        });

        Thread.sleep(100);
        writeMessageFile("sender", "test");

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(1, received.size());
    }

    @Test
    void testRemoveMessageListener() throws Exception {
        driver = new FileWatcherHiveDriver(messageDir, "receiver");
        AtomicInteger count = new AtomicInteger(0);
        CountDownLatch firstLatch = new CountDownLatch(1);
        HiveMessageListener listener = msg -> {
            count.incrementAndGet();
            firstLatch.countDown();
        };

        driver.addMessageListener(listener);
        Thread.sleep(150);

        writeMessageFile("sender", "first");
        assertTrue(firstLatch.await(3, TimeUnit.SECONDS));
        assertEquals(1, count.get());

        driver.removeMessageListener(listener);
        writeMessageFile("sender2", "second");
        Thread.sleep(300);
        assertEquals(1, count.get(), "Should not receive after removal");
    }

    // ========== Driver State Tests ==========

    @Test
    void testIsEnabled_trueAfterCreation() {
        driver = new FileWatcherHiveDriver(messageDir, "test");
        assertTrue(driver.isEnabled());
    }

    @Test
    void testIsEnabled_falseAfterShutdown() {
        driver = new FileWatcherHiveDriver(messageDir, "test");
        driver.shutdown();
        assertFalse(driver.isEnabled());
    }

    @Test
    void testGetInstanceId() {
        driver = new FileWatcherHiveDriver(messageDir, "custom-id");
        assertEquals("custom-id", driver.getInstanceId());
    }

    @Test
    void testGetName() {
        driver = new FileWatcherHiveDriver(messageDir, "test");
        assertEquals("FileWatcher", driver.getName());
    }

    @Test
    void testShutdown_stopsReceiving() throws Exception {
        driver = new FileWatcherHiveDriver(messageDir, "receiver");
        AtomicInteger count = new AtomicInteger(0);

        driver.addMessageListener(msg -> count.incrementAndGet());
        Thread.sleep(100);

        driver.shutdown();

        writeMessageFile("sender", "after shutdown");
        Thread.sleep(200);

        assertEquals(0, count.get());
    }

    // ========== Existing Message Processing Tests ==========

    @Test
    void testProcessesExistingMessages() throws Exception {
        // Write message before creating driver
        writeMessageFile("prior-sender", "existing message");
        Thread.sleep(50); // Ensure file is written

        List<String> received = new ArrayList<>();

        driver = new FileWatcherHiveDriver(messageDir, "new-instance");
        // Message should be queued by now, and dispatched when listener is added
        driver.addMessageListener(msg -> {
            received.add(msg);
        });

        // Give a moment for any async dispatch
        Thread.sleep(100);

        assertEquals(1, received.size());
        assertEquals("existing message", received.get(0));
    }

    // ========== Helper Methods ==========

    private void writeMessageFile(String instanceId, String content) throws IOException {
        String filename = String.format("%d_%s.msg", System.currentTimeMillis(), instanceId);
        Path file = messageDir.resolve(filename);
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
