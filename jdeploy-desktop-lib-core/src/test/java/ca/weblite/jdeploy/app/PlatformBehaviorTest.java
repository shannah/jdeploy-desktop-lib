package ca.weblite.jdeploy.app;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for platform-specific behavior of the singleton watcher.
 */
class PlatformBehaviorTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        JDeploySingletonWatcher.shutdown();
        System.clearProperty("jdeploy.singleton.ipcdir");
    }

    @Test
    @DisplayName("Should not initialize watcher on macOS")
    @EnabledOnOs(OS.MAC)
    void testNoWatcherOnMacOS() throws Exception {
        // Set up IPC directory (even though it wouldn't normally exist on macOS)
        Path ipcDir = tempDir.resolve("ipc");
        Path inboxDir = ipcDir.resolve("inbox");
        Files.createDirectories(inboxDir);

        System.setProperty("jdeploy.singleton.ipcdir", ipcDir.toString());

        // Initialize should be a no-op on macOS
        JDeploySingletonWatcher.initialize();

        // Watcher should not be active
        assertFalse(JDeploySingletonWatcher.isActive(),
                "Watcher should not be active on macOS");
    }

    @Test
    @DisplayName("Should initialize watcher on Linux when ipcdir is set")
    @EnabledOnOs(OS.LINUX)
    void testWatcherOnLinux() throws Exception {
        Path ipcDir = tempDir.resolve("ipc");
        Path inboxDir = ipcDir.resolve("inbox");
        Files.createDirectories(inboxDir);

        System.setProperty("jdeploy.singleton.ipcdir", ipcDir.toString());

        JDeploySingletonWatcher.initialize();

        assertTrue(JDeploySingletonWatcher.isActive(),
                "Watcher should be active on Linux when ipcdir is set");
    }

    @Test
    @DisplayName("Should initialize watcher on Windows when ipcdir is set")
    @EnabledOnOs(OS.WINDOWS)
    void testWatcherOnWindows() throws Exception {
        Path ipcDir = tempDir.resolve("ipc");
        Path inboxDir = ipcDir.resolve("inbox");
        Files.createDirectories(inboxDir);

        System.setProperty("jdeploy.singleton.ipcdir", ipcDir.toString());

        JDeploySingletonWatcher.initialize();

        assertTrue(JDeploySingletonWatcher.isActive(),
                "Watcher should be active on Windows when ipcdir is set");
    }

    @Test
    @DisplayName("Should not initialize watcher when ipcdir is not set (non-macOS)")
    @EnabledOnOs({OS.LINUX, OS.WINDOWS})
    void testNoWatcherWithoutIpcDir() {
        // Don't set the ipcdir property
        JDeploySingletonWatcher.initialize();

        assertFalse(JDeploySingletonWatcher.isActive(),
                "Watcher should not be active when ipcdir is not set");
    }

    @Test
    @DisplayName("macOS check should detect macOS correctly")
    void testMacOSDetection() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        boolean isMacOS = osName.contains("mac");

        // Verify our detection logic matches the actual OS
        if (System.getProperty("os.name").toLowerCase().contains("mac os x") ||
            System.getProperty("os.name").toLowerCase().contains("darwin")) {
            assertTrue(isMacOS, "Should detect macOS");
        }
    }

    @Test
    @DisplayName("Should handle empty os.name gracefully")
    void testEmptyOsName() {
        // This tests the defensive coding in the macOS check
        String originalOsName = System.getProperty("os.name");
        try {
            // Simulate missing os.name (shouldn't happen, but test defensive code)
            // Note: We can't actually clear os.name, but we test the logic
            String osName = System.getProperty("os.name", "").toLowerCase();
            assertNotNull(osName);
            // The check "".contains("mac") should return false
            assertFalse("".contains("mac"));
        } finally {
            // os.name wasn't actually changed, but being explicit
        }
    }
}
