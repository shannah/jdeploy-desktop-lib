# jdeploy-desktop-lib-swing

Swing integration for jDeploy desktop applications.

## Overview

This module provides thread-safe Swing integration for singleton application behavior. All callbacks are automatically delivered on the Event Dispatch Thread (EDT).

## Installation

```xml
<dependency>
    <groupId>ca.weblite</groupId>
    <artifactId>jdeploy-desktop-lib-swing</artifactId>
    <version>1.0.3</version>
</dependency>
```

## Usage

```java
import ca.weblite.jdeploy.app.JDeployOpenHandler;
import ca.weblite.jdeploy.app.swing.JDeploySwingApp;

public class MyApp {
    private JFrame mainWindow;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MyApp().init());
    }

    private void init() {
        mainWindow = new JFrame("My Application");
        // ... setup UI ...

        // Register handler - callbacks arrive on EDT
        JDeploySwingApp.setOpenHandler(new JDeployOpenHandler() {
            @Override
            public void openFiles(List<File> files) {
                // Safe to update UI directly
                for (File file : files) {
                    openDocument(file);
                }
            }

            @Override
            public void openURIs(List<URI> uris) {
                // Handle custom URL schemes
                for (URI uri : uris) {
                    handleDeepLink(uri);
                }
            }

            @Override
            public void appActivated() {
                // Bring window to front
                mainWindow.setState(JFrame.NORMAL);
                mainWindow.toFront();
            }
        });

        mainWindow.setVisible(true);
    }
}
```

## API

### JDeploySwingApp

```java
public class JDeploySwingApp {
    // Register your handler - callbacks delivered on EDT
    public static void setOpenHandler(JDeployOpenHandler handler);
}
```

## Features

- **EDT Delivery** - All callbacks are automatically dispatched to the Event Dispatch Thread
- **macOS Integration** - On macOS, also hooks into native Desktop API for direct event handling
- **Event Queuing** - Events received before handler registration are queued and delivered once registered

## Requirements

- Java 11+
- Depends on: `jdeploy-desktop-lib-core`
