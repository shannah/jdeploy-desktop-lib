# jdeploy-desktop-lib-javafx

JavaFX integration for jDeploy desktop applications.

## Overview

This module provides thread-safe JavaFX integration for singleton application behavior. All callbacks are automatically delivered on the JavaFX Application Thread.

## Installation

```xml
<dependency>
    <groupId>ca.weblite</groupId>
    <artifactId>jdeploy-desktop-lib-javafx</artifactId>
    <version>1.0.3</version>
</dependency>
```

## Usage

```java
import ca.weblite.jdeploy.app.JDeployOpenHandler;
import ca.weblite.jdeploy.app.javafx.JDeployFXApp;
import javafx.application.Application;
import javafx.stage.Stage;

public class MyApp extends Application {
    private Stage primaryStage;

    public static void main(String[] args) {
        // IMPORTANT: Call before launch() for macOS deep link support
        JDeployFXApp.initialize();
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;
        // ... setup UI ...

        // Register handler - callbacks arrive on FX Application Thread
        JDeployFXApp.setOpenHandler(new JDeployOpenHandler() {
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
                primaryStage.setIconified(false);
                primaryStage.toFront();
            }
        });

        stage.show();
    }
}
```

## API

### JDeployFXApp

```java
public class JDeployFXApp {
    // Initialize for macOS deep link support - call BEFORE launch()
    public static void initialize();

    // Register your handler - callbacks delivered on FX Application Thread
    public static void setOpenHandler(JDeployOpenHandler handler);
}
```

## Important: macOS Deep Links

On macOS, you **must** call `JDeployFXApp.initialize()` before `Application.launch()` to receive deep link events that arrive before the JavaFX application starts:

```java
public static void main(String[] args) {
    JDeployFXApp.initialize();  // Must be first!
    launch(args);
}
```

## Features

- **FX Thread Delivery** - All callbacks are automatically dispatched to the JavaFX Application Thread
- **macOS Integration** - On macOS, hooks into native Desktop API for direct event handling
- **Event Queuing** - Events received before handler registration are queued and delivered once registered
- **Early Initialization** - `initialize()` captures deep link events before JavaFX starts

## Requirements

- Java 11+
- JavaFX 17+ (provided scope - you supply the JavaFX runtime)
- Depends on: `jdeploy-desktop-lib-core`
