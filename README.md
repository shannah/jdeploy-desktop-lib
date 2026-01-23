# jdeploy-desktop-lib

Desktop application library for jDeploy applications.

## Modules

### Core (`jdeploy-desktop-lib-core`)

Core utilities and base classes with no GUI dependencies.

```xml
<dependency>
    <groupId>ca.weblite</groupId>
    <artifactId>jdeploy-desktop-lib-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Swing (`jdeploy-desktop-lib-swing`)

Swing-based UI components and utilities.

```xml
<dependency>
    <groupId>ca.weblite</groupId>
    <artifactId>jdeploy-desktop-lib-swing</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### JavaFX (`jdeploy-desktop-lib-javafx`)

JavaFX-based UI components and utilities. JavaFX is expected to be provided by the runtime.

```xml
<dependency>
    <groupId>ca.weblite</groupId>
    <artifactId>jdeploy-desktop-lib-javafx</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Building

```bash
mvn clean install
```

## Requirements

- Java 8 or higher
- Maven 3.6+

## License

MIT License - see [LICENSE](LICENSE) for details.
