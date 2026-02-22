# jdeploy-desktop-lib-hive

Hive IPC module for inter-instance communication. Core interfaces with no dependencies.

## Overview

Hive provides a simple way for multiple instances of the same application to communicate with each other. Use cases include:

- Notifying other instances to refresh data
- Discovering other running instances (ping/pong)
- Coordinating between primary and secondary instances
- Broadcasting state changes

This module contains only the interfaces and facade. For a ready-to-use implementation, see [jdeploy-desktop-lib-hive-filewatcher](../jdeploy-desktop-lib-hive-filewatcher).

## Installation

```xml
<dependency>
    <groupId>ca.weblite</groupId>
    <artifactId>jdeploy-desktop-lib-hive</artifactId>
    <version>1.0.3</version>
</dependency>
```

## Usage

### Sending Messages

```java
import ca.weblite.jdeploy.app.hive.Hive;

// Send a message to all other instances
Hive.send("REFRESH_DATA");
Hive.send("USER_LOGGED_OUT");
```

### Receiving Messages

```java
import ca.weblite.jdeploy.app.hive.Hive;

// Listen for messages from other instances
Hive.addMessageListener(message -> {
    switch (message) {
        case "REFRESH_DATA":
            refreshData();
            break;
        case "USER_LOGGED_OUT":
            handleLogout();
            break;
    }
});
```

### Instance Discovery (Ping/Pong)

```java
import ca.weblite.jdeploy.app.hive.Hive;

// Check if other instances exist
if (Hive.hasOtherInstances(1000)) {
    System.out.println("Another instance is already running");
}

// Count other instances
int count = Hive.ping(2000);
System.out.println("Found " + count + " other instances");

// Find instances with specific properties
Hive.setInstanceProperties(Map.of("role", "worker"));

Hive.ping(3000, pong -> {
    System.out.println("Found: " + pong.getInstanceId());
    System.out.println("Role: " + pong.getProperty("role"));

    if ("primary".equals(pong.getProperty("role"))) {
        connectToPrimary(pong.getInstanceId());
        return false; // Stop searching
    }
    return true; // Keep searching
});
```

## API

### Hive (Static Facade)

```java
public final class Hive {
    // Driver management
    public static void setDriver(HiveDriver driver);
    public static HiveDriver getDriver();
    public static boolean isEnabled();
    public static void shutdown();

    // Messaging
    public static void send(String message);
    public static void addMessageListener(HiveMessageListener listener);
    public static void removeMessageListener(HiveMessageListener listener);

    // Instance discovery
    public static void setInstanceProperties(Map<String, String> properties);
    public static void ping(long timeoutMs, HivePongListener listener);
    public static int ping(long timeoutMs);
    public static boolean hasOtherInstances(long timeoutMs);
}
```

### HiveMessageListener

```java
public interface HiveMessageListener {
    void onMessage(String message);
}
```

### HivePongListener

```java
public interface HivePongListener {
    // Return true to continue listening, false to stop early
    boolean onPong(HivePong pong);
}
```

### HivePong

```java
public interface HivePong {
    String getInstanceId();
    Map<String, String> getProperties();
    String getProperty(String key);
}
```

### HiveDriver (SPI)

Implement this interface to create custom transport mechanisms:

```java
public interface HiveDriver {
    void send(String message);
    void addMessageListener(HiveMessageListener listener);
    void removeMessageListener(HiveMessageListener listener);
    boolean isEnabled();
    void shutdown();
    String getInstanceId();
}
```

## Custom Drivers

To implement your own transport (Redis, WebSocket, etc.):

```java
public class RedisHiveDriver implements HiveDriver {
    private final RedisClient client;
    private final String channel;
    private final String instanceId;

    public RedisHiveDriver(RedisClient client, String appName) {
        this.client = client;
        this.channel = "hive:" + appName;
        this.instanceId = UUID.randomUUID().toString();
        // Subscribe to channel...
    }

    @Override
    public void send(String message) {
        client.publish(channel, instanceId + ":" + message);
    }

    // ... implement other methods
}

// Install your driver
Hive.setDriver(new RedisHiveDriver(redis, "myapp"));
```

## Requirements

- Java 11+
- No external dependencies
