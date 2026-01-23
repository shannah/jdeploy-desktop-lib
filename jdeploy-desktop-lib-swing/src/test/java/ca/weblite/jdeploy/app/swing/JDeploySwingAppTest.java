package ca.weblite.jdeploy.app.swing;

import ca.weblite.jdeploy.app.JDeployOpenHandler;
import ca.weblite.jdeploy.app.JDeploySingletonWatcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class JDeploySwingAppTest {

    private List<File> receivedFiles;
    private List<URI> receivedURIs;
    private AtomicInteger activateCount;
    private AtomicBoolean wasOnEDT;

    @Before
    public void setUp() {
        receivedFiles = new ArrayList<>();
        receivedURIs = new ArrayList<>();
        activateCount = new AtomicInteger(0);
        wasOnEDT = new AtomicBoolean(false);
    }

    @After
    public void tearDown() {
        JDeploySwingApp.clearHandler();
        JDeploySingletonWatcher.shutdown();
    }

    // ===========================================
    // Handler Registration Tests
    // ===========================================

    @Test
    public void testHandlerRegistration() {
        assertFalse(JDeploySwingApp.hasHandler());

        JDeploySwingApp.setOpenHandler(createTestHandler());

        assertTrue(JDeploySwingApp.hasHandler());
    }

    @Test
    public void testHandlerCleared() {
        JDeploySwingApp.setOpenHandler(createTestHandler());
        assertTrue(JDeploySwingApp.hasHandler());

        JDeploySwingApp.clearHandler();
        assertFalse(JDeploySwingApp.hasHandler());
    }

    // ===========================================
    // EDT Dispatch Tests
    // ===========================================

    @Test
    public void testOpenFilesDispatchedOnEDT() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        JDeployOpenHandler handler = new JDeployOpenHandler() {
            @Override
            public void openFiles(List<File> files) {
                wasOnEDT.set(SwingUtilities.isEventDispatchThread());
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

        JDeploySwingApp.setOpenHandler(handler);

        // Dispatch from non-EDT thread
        List<File> files = new ArrayList<>();
        files.add(new File("/test/file.txt"));
        JDeploySingletonWatcher.dispatchFiles(files);

        assertTrue("Callback should complete within timeout", latch.await(2, TimeUnit.SECONDS));
        assertTrue("Callback should be on EDT", wasOnEDT.get());
        assertEquals(1, receivedFiles.size());
    }

    @Test
    public void testOpenURIsDispatchedOnEDT() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        JDeployOpenHandler handler = new JDeployOpenHandler() {
            @Override
            public void openFiles(List<File> files) {
            }

            @Override
            public void openURIs(List<URI> uris) {
                wasOnEDT.set(SwingUtilities.isEventDispatchThread());
                receivedURIs.addAll(uris);
                latch.countDown();
            }

            @Override
            public void appActivated() {
            }
        };

        JDeploySwingApp.setOpenHandler(handler);

        // Dispatch from non-EDT thread
        List<URI> uris = new ArrayList<>();
        uris.add(URI.create("myapp://test"));
        JDeploySingletonWatcher.dispatchURIs(uris);

        assertTrue("Callback should complete within timeout", latch.await(2, TimeUnit.SECONDS));
        assertTrue("Callback should be on EDT", wasOnEDT.get());
        assertEquals(1, receivedURIs.size());
    }

    @Test
    public void testAppActivatedDispatchedOnEDT() throws Exception {
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
                wasOnEDT.set(SwingUtilities.isEventDispatchThread());
                activateCount.incrementAndGet();
                latch.countDown();
            }
        };

        JDeploySwingApp.setOpenHandler(handler);

        // Dispatch from non-EDT thread
        JDeploySingletonWatcher.dispatchActivate();

        assertTrue("Callback should complete within timeout", latch.await(2, TimeUnit.SECONDS));
        assertTrue("Callback should be on EDT", wasOnEDT.get());
        assertEquals(1, activateCount.get());
    }

    @Test
    public void testDispatchFromEDTRunsImmediately() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean completedSynchronously = new AtomicBoolean(false);

        JDeployOpenHandler handler = new JDeployOpenHandler() {
            @Override
            public void openFiles(List<File> files) {
                wasOnEDT.set(SwingUtilities.isEventDispatchThread());
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

        JDeploySwingApp.setOpenHandler(handler);

        // Dispatch from EDT
        SwingUtilities.invokeAndWait(() -> {
            List<File> files = new ArrayList<>();
            files.add(new File("/test/file.txt"));
            JDeploySingletonWatcher.dispatchFiles(files);
            // Check if it completed synchronously
            completedSynchronously.set(latch.getCount() == 0);
        });

        assertTrue("Callback should complete", latch.await(2, TimeUnit.SECONDS));
        assertTrue("Callback should be on EDT", wasOnEDT.get());
        assertTrue("Should complete synchronously when called from EDT", completedSynchronously.get());
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
                wasOnEDT.set(SwingUtilities.isEventDispatchThread());
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
        JDeploySwingApp.setOpenHandler(handler);

        assertTrue("Queued events should be dispatched", latch.await(2, TimeUnit.SECONDS));
        assertTrue("Callback should be on EDT", wasOnEDT.get());
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
                wasOnEDT.set(SwingUtilities.isEventDispatchThread());
                receivedURIs.addAll(uris);
                latch.countDown();
            }

            @Override
            public void appActivated() {
            }
        };

        // Set handler - should trigger dispatch of queued events
        JDeploySwingApp.setOpenHandler(handler);

        assertTrue("Queued events should be dispatched", latch.await(2, TimeUnit.SECONDS));
        assertTrue("Callback should be on EDT", wasOnEDT.get());
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
                wasOnEDT.set(SwingUtilities.isEventDispatchThread());
                activateCount.incrementAndGet();
                latch.countDown();
            }
        };

        // Set handler - should trigger dispatch of queued events
        JDeploySwingApp.setOpenHandler(handler);

        assertTrue("Queued events should be dispatched", latch.await(2, TimeUnit.SECONDS));
        assertTrue("Callback should be on EDT", wasOnEDT.get());
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
