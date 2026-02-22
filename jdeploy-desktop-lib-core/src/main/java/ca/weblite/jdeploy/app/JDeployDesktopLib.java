package ca.weblite.jdeploy.app;

import java.io.IOException;

/**
 * Main entry point for jDeploy desktop library core module.
 */
public class JDeployDesktopLib {

    /**
     * Returns the version of the library.
     * @return the version string
     */
    public static String getVersion() {
        return "1.0.0-SNAPSHOT";
    }

    /**
     * Opens a URL using the system's native URL handler.
     * <p>
     * This method properly handles custom URL schemes (e.g., {@code myapp://...})
     * on all platforms without routing through a web browser:
     * <ul>
     *   <li>macOS: Uses the {@code open} command</li>
     *   <li>Windows: Uses {@code cmd /c start}</li>
     *   <li>Linux: Uses {@code xdg-open}</li>
     * </ul>
     * <p>
     * Unlike {@link java.awt.Desktop#browse(java.net.URI)}, this method invokes
     * the registered URL scheme handler directly, which is especially important
     * for custom URL schemes that would otherwise be routed through Safari/browser.
     *
     * @param url the URL to open (can be a custom scheme like {@code myapp://...})
     * @throws IOException if the URL cannot be opened
     */
    public static void openUrl(String url) throws IOException {
        String os = System.getProperty("os.name").toLowerCase();
        try {
            if (os.contains("mac")) {
                // On macOS, use 'open' command to directly invoke URL scheme handler
                Runtime.getRuntime().exec(new String[]{"open", url});
            } else if (os.contains("win")) {
                // On Windows, use 'start' command
                Runtime.getRuntime().exec(new String[]{"cmd", "/c", "start", "", url});
            } else {
                // On Linux/other, try xdg-open
                Runtime.getRuntime().exec(new String[]{"xdg-open", url});
            }
        } catch (IOException e) {
            throw new IOException("Failed to open URL: " + url, e);
        }
    }
}
