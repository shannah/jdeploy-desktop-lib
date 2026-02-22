# jdeploy-desktop-lib-core

Core module for jDeploy desktop applications. Pure Java with no GUI dependencies.

## Overview

This module provides the foundational components for singleton application behavior:

- **JDeployOpenHandler** - Interface for receiving file open, URI, and activation events
- **JDeploySingletonWatcher** - File-based IPC mechanism for inter-process communication

Most applications should use the [Swing](../jdeploy-desktop-lib-swing) or [JavaFX](../jdeploy-desktop-lib-javafx) modules instead, which provide thread-safe wrappers.

## Installation

```xml
<dependency>
    <groupId>ca.weblite</groupId>
    <artifactId>jdeploy-desktop-lib-core</artifactId>
    <version>1.0.3</version>
</dependency>
```

## Usage

Only use this module directly if you need to handle threading yourself:

```java
import ca.weblite.jdeploy.app.JDeployOpenHandler;
import ca.weblite.jdeploy.app.JDeploySingletonWatcher;

// Initialize the watcher (reads jdeploy.singleton.ipcdir system property)
JDeploySingletonWatcher.initialize();

// Register your handler
JDeploySingletonWatcher.setOpenHandler(new JDeployOpenHandler() {
    @Override
    public void openFiles(List<File> files) {
        // Handle file opens - called on watcher thread!
        SwingUtilities.invokeLater(() -> processFiles(files));
    }

    @Override
    public void openURIs(List<URI> uris) {
        // Handle URI opens - called on watcher thread!
        SwingUtilities.invokeLater(() -> processURIs(uris));
    }

    @Override
    public void appActivated() {
        // Handle app activation - called on watcher thread!
        SwingUtilities.invokeLater(() -> bringToFront());
    }
});
```

## API

### JDeployOpenHandler

```java
public interface JDeployOpenHandler {
    void openFiles(List<File> files);
    void openURIs(List<URI> uris);
    void appActivated();
}
```

### JDeploySingletonWatcher

```java
public class JDeploySingletonWatcher {
    // Initialize the watcher from system properties
    public static void initialize();

    // Register a handler for events
    public static void setOpenHandler(JDeployOpenHandler handler);

    // Check if watcher is active
    public static boolean isActive();

    // Shutdown the watcher
    public static void shutdown();
}
```

## Threading

Unlike the Swing and JavaFX modules, callbacks in this module are delivered on a **background watcher thread**. You must dispatch to the appropriate UI thread yourself.

## Requirements

- Java 11+
- No external dependencies
