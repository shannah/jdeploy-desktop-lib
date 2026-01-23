# jdeploy-desktop-lib

[![Maven Central](https://img.shields.io/maven-central/v/ca.weblite/jdeploy-desktop-lib-core)](https://central.sonatype.com/artifact/ca.weblite/jdeploy-desktop-lib-core)
[![Java 11+](https://img.shields.io/badge/Java-11%2B-blue)](https://openjdk.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A Java library that enables singleton application behavior for jDeploy-packaged desktop applications. Handle file opens, URI scheme activations, and app reactivation events when users interact with your application.

## Overview

When you package a Java application with jDeploy and enable singleton mode, only one instance of your app runs at a time. When a user double-clicks an associated file or opens a custom URL scheme, jDeploy routes these events to your running application instance.

**This library provides:**
- File open event handling (double-click, drag-drop, "Open With")
- Custom URL scheme handling (deep links, OAuth callbacks)
- App activation events (dock/taskbar click)
- Thread-safe callbacks on the appropriate UI thread

## Installation

Choose the module that matches your UI toolkit:

### For Swing Applications

```xml
<dependency>
    <groupId>ca.weblite</groupId>
    <artifactId>jdeploy-desktop-lib-swing</artifactId>
    <version>1.0.2</version>
</dependency>
```

### For JavaFX Applications

```xml
<dependency>
    <groupId>ca.weblite</groupId>
    <artifactId>jdeploy-desktop-lib-javafx</artifactId>
    <version>1.0.2</version>
</dependency>
```

### Core Only (Advanced)

If you need to handle threading yourself:

```xml
<dependency>
    <groupId>ca.weblite</groupId>
    <artifactId>jdeploy-desktop-lib-core</artifactId>
    <version>1.0.2</version>
</dependency>
```

## Quick Start

### Swing

```java
import ca.weblite.jdeploy.app.JDeployOpenHandler;
import ca.weblite.jdeploy.app.swing.JDeploySwingApp;

public class MyApp {
    public static void main(String[] args) {
        JDeploySwingApp.setOpenHandler(new JDeployOpenHandler() {
            public void openFiles(List<File> files) {
                files.forEach(f -> System.out.println("Open: " + f));
            }
            public void openURIs(List<URI> uris) {
                uris.forEach(u -> System.out.println("URI: " + u));
            }
            public void appActivated() {
                System.out.println("App activated");
            }
        });

        // Your normal Swing initialization...
    }
}
```

### JavaFX

```java
import ca.weblite.jdeploy.app.JDeployOpenHandler;
import ca.weblite.jdeploy.app.javafx.JDeployFXApp;

public class MyApp extends Application {
    @Override
    public void start(Stage stage) {
        JDeployFXApp.setOpenHandler(new JDeployOpenHandler() {
            public void openFiles(List<File> files) {
                files.forEach(f -> System.out.println("Open: " + f));
            }
            public void openURIs(List<URI> uris) {
                uris.forEach(u -> System.out.println("URI: " + u));
            }
            public void appActivated() {
                stage.toFront();
            }
        });

        // Your normal JavaFX initialization...
    }
}
```

## Complete Examples

### Full Swing Application

```java
package com.example;

import ca.weblite.jdeploy.app.JDeployOpenHandler;
import ca.weblite.jdeploy.app.swing.JDeploySwingApp;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.net.URI;
import java.util.List;

public class MySwingApp {
    private JFrame mainWindow;
    private JTextArea logArea;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MySwingApp().init());
    }

    private void init() {
        // Create the main window
        mainWindow = new JFrame("My Application");
        mainWindow.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        mainWindow.setSize(600, 400);

        logArea = new JTextArea();
        logArea.setEditable(false);
        mainWindow.add(new JScrollPane(logArea), BorderLayout.CENTER);

        // Register the open handler
        JDeploySwingApp.setOpenHandler(new JDeployOpenHandler() {
            @Override
            public void openFiles(List<File> files) {
                for (File file : files) {
                    log("Opening file: " + file.getAbsolutePath());
                    openDocument(file);
                }
            }

            @Override
            public void openURIs(List<URI> uris) {
                for (URI uri : uris) {
                    log("Handling URI: " + uri);
                    handleDeepLink(uri);
                }
            }

            @Override
            public void appActivated() {
                log("App activated - bringing window to front");
                mainWindow.setState(JFrame.NORMAL);
                mainWindow.toFront();
                mainWindow.requestFocus();
            }
        });

        mainWindow.setVisible(true);
        log("Application started");
    }

    private void openDocument(File file) {
        // Your file opening logic here
        log("  -> Document loaded: " + file.getName());
    }

    private void handleDeepLink(URI uri) {
        // Your deep link handling logic here
        String host = uri.getHost();
        String path = uri.getPath();
        log("  -> Deep link: host=" + host + ", path=" + path);
    }

    private void log(String message) {
        logArea.append(message + "\n");
    }
}
```

### Full JavaFX Application

```java
package com.example;

import ca.weblite.jdeploy.app.JDeployOpenHandler;
import ca.weblite.jdeploy.app.javafx.JDeployFXApp;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

import java.io.File;
import java.net.URI;
import java.util.List;

public class MyJavaFXApp extends Application {
    private Stage primaryStage;
    private TextArea logArea;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;

        // Create the UI
        BorderPane root = new BorderPane();
        logArea = new TextArea();
        logArea.setEditable(false);
        root.setCenter(logArea);

        Scene scene = new Scene(root, 600, 400);
        stage.setTitle("My Application");
        stage.setScene(scene);

        // Register the open handler
        JDeployFXApp.setOpenHandler(new JDeployOpenHandler() {
            @Override
            public void openFiles(List<File> files) {
                for (File file : files) {
                    log("Opening file: " + file.getAbsolutePath());
                    openDocument(file);
                }
            }

            @Override
            public void openURIs(List<URI> uris) {
                for (URI uri : uris) {
                    log("Handling URI: " + uri);
                    handleDeepLink(uri);
                }
            }

            @Override
            public void appActivated() {
                log("App activated - bringing window to front");
                primaryStage.setIconified(false);
                primaryStage.toFront();
                primaryStage.requestFocus();
            }
        });

        stage.show();
        log("Application started");
    }

    private void openDocument(File file) {
        // Your file opening logic here
        log("  -> Document loaded: " + file.getName());
    }

    private void handleDeepLink(URI uri) {
        // Your deep link handling logic here
        String host = uri.getHost();
        String path = uri.getPath();
        log("  -> Deep link: host=" + host + ", path=" + path);
    }

    private void log(String message) {
        logArea.appendText(message + "\n");
    }
}
```

## API Reference

### JDeployOpenHandler

The interface your application implements to receive events:

```java
public interface JDeployOpenHandler {
    /**
     * Called when files should be opened.
     * @param files the files to open (never null or empty)
     */
    void openFiles(List<File> files);

    /**
     * Called when URIs should be handled.
     * @param uris the URIs to handle (never null or empty)
     */
    void openURIs(List<URI> uris);

    /**
     * Called when the app should be brought to the foreground.
     */
    void appActivated();
}
```

### JDeploySwingApp

For Swing applications. All callbacks are delivered on the Event Dispatch Thread.

```java
public class JDeploySwingApp {
    /**
     * Register your handler. Call once during app initialization.
     */
    public static void setOpenHandler(JDeployOpenHandler handler);
}
```

### JDeployFXApp

For JavaFX applications. All callbacks are delivered on the JavaFX Application Thread.

```java
public class JDeployFXApp {
    /**
     * Register your handler. Call once during app initialization.
     */
    public static void setOpenHandler(JDeployOpenHandler handler);
}
```

## How It Works

### File-Based IPC

When singleton mode is enabled, jDeploy uses a file-based inter-process communication mechanism:

1. The primary instance monitors an "inbox" directory for request files
2. When a secondary instance launches, it writes a request file and exits
3. The primary instance reads the request, dispatches events, then deletes the file

### Event Flow

**File Open (double-click associated file):**
```
User double-clicks file.txt
  -> jDeploy launcher detects running instance
  -> Writes OPEN_FILE request to inbox
  -> Primary instance reads request
  -> Your openFiles() handler is called
```

**URL Scheme (custom protocol):**
```
User clicks myapp://action link
  -> OS routes to jDeploy launcher
  -> Writes OPEN_URI request to inbox
  -> Primary instance reads request
  -> Your openURIs() handler is called
```

**App Activation (dock/taskbar click):**
```
User clicks dock icon
  -> Writes ACTIVATE request to inbox
  -> Your appActivated() handler is called
```

### macOS Integration

On macOS, the Swing module also hooks into the native `Desktop` API for direct file/URI handling, providing immediate response without file-based IPC for events that go directly to the running app.

## Configuration

### Enabling Singleton Mode in jDeploy

In your `package.json`, enable singleton mode:

```json
{
  "jdeploy": {
    "singleton": true,
    "javaVersion": "11"
  }
}
```

### File Associations

To associate file types with your application:

```json
{
  "jdeploy": {
    "singleton": true,
    "documentTypes": [
      {
        "extension": "myapp",
        "mimeType": "application/x-myapp",
        "description": "MyApp Document"
      }
    ]
  }
}
```

### Custom URL Schemes

To register a custom URL scheme:

```json
{
  "jdeploy": {
    "singleton": true,
    "urlSchemes": ["myapp"]
  }
}
```

This allows handling URLs like `myapp://open?id=123`.

For more configuration options, see the [jDeploy documentation](https://www.jdeploy.com/docs).

## Troubleshooting

### Events Not Being Received

1. **Check singleton mode is enabled** - Verify `"singleton": true` in package.json
2. **Register handler early** - Call `setOpenHandler()` during app initialization, before showing any UI
3. **Check for exceptions** - Handler exceptions are logged to stderr with `jDeploy:` prefix

### Debugging

Enable debug logging by checking stderr for messages prefixed with `jDeploy:`:

```
jDeploy: IPC directory does not exist: /path/to/ipc
jDeploy: Error processing request file: ...
```

### Common Issues

| Issue | Cause | Solution |
|-------|-------|----------|
| Files open new instance | Singleton not enabled | Add `"singleton": true` to package.json |
| Handler never called | Handler registered too late | Move `setOpenHandler()` to early initialization |
| UI freezes on file open | Heavy processing in handler | Use background thread for file loading |
| Events arrive before UI ready | Race condition | Events are queued until handler is registered |

### Thread Safety

All callbacks are delivered on the appropriate UI thread:
- **Swing**: Event Dispatch Thread (EDT)
- **JavaFX**: JavaFX Application Thread

You can safely update UI components directly in your handler methods.

## Building from Source

```bash
git clone https://github.com/shannah/jdeploy-desktop-lib.git
cd jdeploy-desktop-lib
mvn clean install
```

## Requirements

- Java 11 or higher
- Maven 3.6+ (for building)

## License

MIT License - see [LICENSE](LICENSE) for details.
