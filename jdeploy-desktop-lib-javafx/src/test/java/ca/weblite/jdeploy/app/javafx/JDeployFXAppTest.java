package ca.weblite.jdeploy.app.javafx;

import ca.weblite.jdeploy.app.JDeployOpenHandler;
import ca.weblite.jdeploy.app.JDeploySingletonWatcher;
import javafx.application.Platform;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class JDeployFXAppTest {

    private static boolean toolkitInitialized = false;

    private List<File> receivedFiles;
    private List<URI> receivedURIs;
    private AtomicInteger activateCount;
    private AtomicBoolean wasOnFXThread;

    @BeforeClass
    public static void initToolkit() throws Exception {
        if (!toolkitInitialized) {
            // Initialize JavaFX toolkit
            CountDownLatch latch = new CountDownLatch(1);
            try {
                Platform.startup(() -> latch.countDown());
            } catch (IllegalStateException e) {
                // Toolkit already initialized
                latch.countDown();
            }
            latch.await(5, TimeUnit.SECONDS);
            toolkitInitialized = true;
        }
    }

    @Before
    public void setUp() {
        receivedFiles = new ArrayList<>();
        receivedURIs = new ArrayList<>();
        activateCount = new AtomicInteger(0);
        wasOnFXThread = new AtomicBoolean(false);
    }

    @After
    public void tearDown() {
        JDeployFXApp.clearHandler();
        JDeploySingletonWatcher.shutdown();
    }

    // ===========================================
    // Handler Registration Tests
    // ===========================================

    @Test
    public void testHandlerRegistration() {
        assertFalse(JDeployFXApp.hasHandler());

        JDeployFXApp.setOpenHandler(createTestHandler());

        assertTrue(JDeployFXApp.hasHandler());
    }

    @Test
    public void testHandlerCleared() {
        JDeployFXApp.setOpenHandler(createTestHandler());
        assertTrue(JDeployFXApp.hasHandler());

        JDeployFXApp.clearHandler();
        assertFalse(JDeployFXApp.hasHandler());
    }

    // ===========================================
    // FX Thread Dispatch Tests
    // ===========================================

    @Test
    public void testOpenFilesDispatchedOnFXThread() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        JDeployOpenHandler handler = new JDeployOpenHandler() {
            @Override
            public void openFiles(List<File> files) {
                wasOnFXThread.set(Platform.isFxApplicationThread());
                receivedFiles.addAll(files);
                latch.countDown();
            }

            @Override
            public void openURIs(List<URI> uris) {
            }

            @Override
            public void appActivated() {
            }
        };

        JDeployFXApp.setOpenHandler(handler);

        // Dispatch from non-FX thread
        List<File> files = new ArrayList<>();
        files.add(new File("/test/file.txt"));
        JDeploySingletonWatcher.dispatchFiles(files);

        assertTrue("Callback should complete within timeout", latch.await(2, TimeUnit.SECONDS));
        assertTrue("Callback should be on FX Application Thread", wasOnFXThread.get());
        assertEquals(1, receivedFiles.size());
    }

    @Test
    public void testOpenURIsDispatchedOnFXThread() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        JDeployOpenHandler handler = new JDeployOpenHandler() {
            @Override
            public void openFiles(List<File> files) {
            }

            @Override
            public void openURIs(List<URI> uris) {
                wasOnFXThread.set(Platform.isFxApplicationThread());
                receivedURIs.addAll(uris);
                latch.countDown();
            }

            @Override
            public void appActivated() {
            }
        };

        JDeployFXApp.setOpenHandler(handler);

        // Dispatch from non-FX thread
        List<URI> uris = new ArrayList<>();
        uris.add(URI.create("myapp://test"));
        JDeploySingletonWatcher.dispatchURIs(uris);

        assertTrue("Callback should complete within timeout", latch.await(2, TimeUnit.SECONDS));
        assertTrue("Callback should be on FX Application Thread", wasOnFXThread.get());
        assertEquals(1, receivedURIs.size());
    }

    @Test
    public void testAppActivatedDispatchedOnFXThread() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        JDeployOpenHandler handler = new JDeployOpenHandler() {
            @Override
            public void openFiles(List<File> files) {
            }

            @Override
            public void openURIs(List<URI> uris) {
            }

            @Override
            public void appActivated() {
                wasOnFXThread.set(Platform.isFxApplicationThread());
                activateCount.incrementAndGet();
                latch.countDown();
            }
        };

        JDeployFXApp.setOpenHandler(handler);

        // Dispatch from non-FX thread
        JDeploySingletonWatcher.dispatchActivate();

        assertTrue("Callback should complete within timeout", latch.await(2, TimeUnit.SECONDS));
        assertTrue("Callback should be on FX Application Thread", wasOnFXThread.get());
        assertEquals(1, activateCount.get());
    }

    @Test
    public void testDispatchFromFXThreadRunsImmediately() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean completedSynchronously = new AtomicBoolean(false);

        JDeployOpenHandler handler = new JDeployOpenHandler() {
            @Override
            public void openFiles(List<File> files) {
                wasOnFXThread.set(Platform.isFxApplicationThread());
                receivedFiles.addAll(files);
                latch.countDown();
            }

            @Override
            public void openURIs(List<URI> uris) {
            }

            @Override
            public void appActivated() {
            }
        };

        JDeployFXApp.setOpenHandler(handler);

        // Dispatch from FX thread
        CountDownLatch setupLatch = new CountDownLatch(1);
        Platform.runLater(() -> {
            List<File> files = new ArrayList<>();
            files.add(new File("/test/file.txt"));
            JDeploySingletonWatcher.dispatchFiles(files);
            // Check if it completed synchronously
            completedSynchronously.set(latch.getCount() == 0);
            setupLatch.countDown();
        });

        assertTrue("Setup should complete", setupLatch.await(2, TimeUnit.SECONDS));
        assertTrue("Callback should complete", latch.await(2, TimeUnit.SECONDS));
        assertTrue("Callback should be on FX thread", wasOnFXThread.get());
        assertTrue("Should complete synchronously when called from FX thread", completedSynchronously.get());
    }

    // ===========================================
    // Queued Events Tests
    // ===========================================

    @Test
    public void testQueuedFilesDispatchedAfterHandlerSet() throws Exception {
        // Queue files before handler is set
        List<File> files = new ArrayList<>();
        files.add(new File("/queued/file.txt"));
        JDeploySingletonWatcher.dispatchFiles(files);

        CountDownLatch latch = new CountDownLatch(1);

        JDeployOpenHandler handler = new JDeployOpenHandler() {
            @Override
            public void openFiles(List<File> files) {
                wasOnFXThread.set(Platform.isFxApplicationThread());
                receivedFiles.addAll(files);
                latch.countDown();
            }

            @Override
            public void openURIs(List<URI> uris) {
            }

            @Override
            public void appActivated() {
            }
        };

        // Set handler - should trigger dispatch of queued events
        JDeployFXApp.setOpenHandler(handler);

        assertTrue("Queued events should be dispatched", latch.await(2, TimeUnit.SECONDS));
        assertTrue("Callback should be on FX Application Thread", wasOnFXThread.get());
        assertEquals(1, receivedFiles.size());
        assertEquals("/queued/file.txt", receivedFiles.get(0).getPath());
    }

    @Test
    public void testQueuedURIsDispatchedAfterHandlerSet() throws Exception {
        // Queue URIs before handler is set
        List<URI> uris = new ArrayList<>();
        uris.add(URI.create("myapp://queued"));
        JDeploySingletonWatcher.dispatchURIs(uris);

        CountDownLatch latch = new CountDownLatch(1);

        JDeployOpenHandler handler = new JDeployOpenHandler() {
            @Override
            public void openFiles(List<File> files) {
            }

            @Override
            public void openURIs(List<URI> uris) {
                wasOnFXThread.set(Platform.isFxApplicationThread());
                receivedURIs.addAll(uris);
                latch.countDown();
            }

            @Override
            public void appActivated() {
            }
        };

        // Set handler - should trigger dispatch of queued events
        JDeployFXApp.setOpenHandler(handler);

        assertTrue("Queued events should be dispatched", latch.await(2, TimeUnit.SECONDS));
        assertTrue("Callback should be on FX Application Thread", wasOnFXThread.get());
        assertEquals(1, receivedURIs.size());
        assertEquals("myapp://queued", receivedURIs.get(0).toString());
    }

    @Test
    public void testQueuedActivateDispatchedAfterHandlerSet() throws Exception {
        // Queue activate before handler is set
        JDeploySingletonWatcher.dispatchActivate();

        CountDownLatch latch = new CountDownLatch(1);

        JDeployOpenHandler handler = new JDeployOpenHandler() {
            @Override
            public void openFiles(List<File> files) {
            }

            @Override
            public void openURIs(List<URI> uris) {
            }

            @Override
            public void appActivated() {
                wasOnFXThread.set(Platform.isFxApplicationThread());
                activateCount.incrementAndGet();
                latch.countDown();
            }
        };

        // Set handler - should trigger dispatch of queued events
        JDeployFXApp.setOpenHandler(handler);

        assertTrue("Queued events should be dispatched", latch.await(2, TimeUnit.SECONDS));
        assertTrue("Callback should be on FX Application Thread", wasOnFXThread.get());
        assertEquals(1, activateCount.get());
    }

    // ===========================================
    // Helper Methods
    // ===========================================

    private JDeployOpenHandler createTestHandler() {
        return new JDeployOpenHandler() {
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
                activateCount.incrementAndGet();
            }
        };
    }
}
