# jdeploy-desktop-lib-bridge

A library for communication between CLI processes (like MCP servers) and GUI applications.

## Overview

This module provides a simple API for sending commands from a CLI process to a running GUI application and receiving responses. It supports two modes:

- **Background** - Commands are processed without bringing the GUI to the foreground. Ideal for data queries.
- **Foreground** - Commands bring the GUI to the foreground. Use for UI navigation commands.

## Installation

Add the dependency to your project:

**Gradle:**
```groovy
implementation platform('ca.weblite:jdeploy-desktop-lib-bom:1.0.0')
implementation 'ca.weblite:jdeploy-desktop-lib-bridge'
implementation 'ca.weblite:jdeploy-desktop-lib-hive-filewatcher'
```

**Maven:**
```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>ca.weblite</groupId>
            <artifactId>jdeploy-desktop-lib-bom</artifactId>
            <version>1.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>ca.weblite</groupId>
        <artifactId>jdeploy-desktop-lib-bridge</artifactId>
    </dependency>
    <dependency>
        <groupId>ca.weblite</groupId>
        <artifactId>jdeploy-desktop-lib-hive-filewatcher</artifactId>
    </dependency>
</dependencies>
```

## Usage

### 1. Initialize the IPC driver (both processes)

In your application's entry point, initialize the background IPC:

```java
import ca.weblite.jdeploy.app.hive.filewatcher.FileWatcherHiveDriver;

public static void main(String[] args) {
    // Required: set the app name for IPC
    if (System.getProperty("jdeploy.app.name") == null) {
        System.setProperty("jdeploy.app.name", "myapp");
    }

    // Initialize background IPC
    FileWatcherHiveDriver.install();

    // ... rest of your app
}
```

### 2. Set up the receiver (GUI side)

In your GUI application, create a `GuiBridgeReceiver` with a command handler:

```java
import ca.weblite.jdeploy.app.bridge.GuiBridgeReceiver;
import ca.weblite.jdeploy.app.bridge.GuiBridgeHandler;

// Create handler for commands
GuiBridgeHandler handler = (command, params) -> {
    return switch (command) {
        case "get_data" -> Map.of("result", "some data");
        case "create_item" -> {
            String name = params.get("name");
            // ... create item
            yield Map.of("id", "123", "name", name);
        }
        default -> throw new IllegalArgumentException("Unknown command: " + command);
    };
};

// Create receiver and register for commands
GuiBridgeReceiver receiver = new GuiBridgeReceiver(handler);

// For JavaFX - dispatch to UI thread
receiver.registerHiveListener(requestPath ->
    Platform.runLater(() -> receiver.processRequest(requestPath)));

// For Swing - dispatch to EDT
receiver.registerHiveListener(requestPath ->
    SwingUtilities.invokeLater(() -> receiver.processRequest(requestPath)));
```

### 3. Send commands (CLI side)

In your CLI process, use `GuiBridge` to send commands:

```java
import ca.weblite.jdeploy.app.bridge.GuiBridge;

GuiBridge bridge = new GuiBridge("myapp");  // URL scheme

// Background command - GUI stays in background
Map<String, String> result = bridge.sendCommand("get_data", Map.of());

// Foreground command - brings GUI to front
Map<String, String> result = bridge.sendCommandViaDeepLink("show_item", Map.of("id", "123"));
```

## API Reference

### GuiBridge

The sender-side class for CLI processes.

| Method | Description |
|--------|-------------|
| `GuiBridge(String urlScheme)` | Create a bridge with the given URL scheme |
| `sendCommand(String command, Map<String, String> params)` | Send a background command |
| `sendCommandViaDeepLink(String command, Map<String, String> params)` | Send a foreground command |
| `withTimeout(long ms)` | Set response timeout (default: 10000ms) |
| `withPollInterval(long ms)` | Set response poll interval (default: 100ms) |

### GuiBridgeReceiver

The receiver-side class for GUI applications.

| Method | Description |
|--------|-------------|
| `GuiBridgeReceiver(GuiBridgeHandler handler)` | Create a receiver with the given handler |
| `registerHiveListener()` | Register for background commands (calls handler on background thread) |
| `registerHiveListener(RequestCallback callback)` | Register with custom thread dispatching |
| `processRequest(String requestFilePath)` | Process a command from a request file |
| `processDeepLink(URI uri)` | Process a command from a deep link URI |

### GuiBridgeHandler

Functional interface for command handling.

```java
@FunctionalInterface
public interface GuiBridgeHandler {
    Map<String, String> handleCommand(String command, Map<String, String> params) throws Exception;
}
```

## Requirements

- Java 11 or higher
- `jdeploy-desktop-lib-hive-filewatcher` for background IPC

## License

MIT License
