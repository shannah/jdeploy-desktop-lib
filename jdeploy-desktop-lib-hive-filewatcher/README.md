# jdeploy-desktop-lib-hive-filewatcher

File-based Hive IPC driver using filesystem watching. Ready-to-use implementation.

## Overview

This module provides a file-based implementation of the Hive IPC driver. Messages are written as files to a shared directory and picked up by other instances via NIO WatchService.

**Features:**
- Zero configuration with jDeploy system properties
- Automatic instance ID generation
- Self-filtering (instances don't receive their own messages)
- Message TTL with automatic cleanup (30 seconds)
- Supports all Hive features including ping/pong

## Installation

```xml
<dependency>
    <groupId>ca.weblite</groupId>
    <artifactId>jdeploy-desktop-lib-hive-filewatcher</artifactId>
    <version>1.0.3</version>
</dependency>
```

This module includes `jdeploy-desktop-lib-hive` as a dependency.

## Usage

### Quick Start (Recommended)

One-line setup using jDeploy system properties:

```java
import ca.weblite.jdeploy.app.hive.Hive;
import ca.weblite.jdeploy.app.hive.filewatcher.FileWatcherHiveDriver;

public class MyApp {
    public static void main(String[] args) {
        // Install the file watcher driver
        FileWatcherHiveDriver.install();

        // Now use Hive normally
        Hive.addMessageListener(msg -> {
            System.out.println("Received: " + msg);
        });

        // Send messages to other instances
        Hive.send("APP_STARTED");
    }
}
```

The driver reads `jdeploy.app.name` and `jdeploy.app.source` system properties to determine the message directory. If these aren't set, `install()` silently does nothing.

### Manual Configuration

For custom message directories:

```java
import ca.weblite.jdeploy.app.hive.Hive;
import ca.weblite.jdeploy.app.hive.filewatcher.FileWatcherHiveDriver;

Path messageDir = Paths.get("/path/to/messages");
String instanceId = UUID.randomUUID().toString();

FileWatcherHiveDriver driver = new FileWatcherHiveDriver(messageDir, instanceId);
Hive.setDriver(driver);
```

## How It Works

### Message Directory

Messages are stored in `~/.jdeploy/messages/{fqn}/` where `fqn` is:
- `md5(source).name` if `jdeploy.app.source` is set
- Just `name` otherwise

This ensures different applications (or versions from different sources) use separate message directories.

### File Format

- **Filename:** `{timestamp}_{instanceId}.msg`
- **Content:** Raw UTF-8 message text

Example: `1708300000000_a1b2c3d4-e5f6-7890-abcd-ef1234567890.msg`

### Message Flow

1. Instance A calls `Hive.send("REFRESH")`
2. Driver writes `1708300000000_instanceA.msg` with content "REFRESH"
3. Instance B's WatchService detects the new file
4. Instance B reads the file, sees it's not from itself, dispatches to listeners
5. Background cleanup thread deletes files older than 30 seconds

### Self-Filtering

Each instance has a unique ID. When processing message files, the driver skips files containing its own instance ID in the filename.

## API

### FileWatcherHiveDriver

```java
public class FileWatcherHiveDriver implements HiveDriver {
    // Install using system properties (recommended)
    public static void install();

    // Create from system properties
    public static FileWatcherHiveDriver createFromSystemProperties();

    // Create with explicit configuration
    public FileWatcherHiveDriver(Path messageDir, String instanceId);

    // Compute FQN for a given app name and source
    static String computeFqn(String appName, String appSource);
}
```

## Configuration

### System Properties

| Property | Description | Required |
|----------|-------------|----------|
| `jdeploy.app.name` | Application name | Yes |
| `jdeploy.app.source` | Application source URL (for namespace isolation) | No |

These are automatically set by jDeploy when running packaged applications.

### Tuning

The driver uses these internal settings:
- **Poll timeout:** 100ms
- **File read delay:** 20ms (ensures file is fully written)
- **TTL:** 30 seconds
- **Cleanup interval:** 10 seconds

## Requirements

- Java 11+
- Depends on: `jdeploy-desktop-lib-hive`
