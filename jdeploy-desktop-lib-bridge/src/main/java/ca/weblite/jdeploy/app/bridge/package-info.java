/**
 * GUI Bridge module for inter-process communication between CLI and GUI processes.
 * <p>
 * This package provides a simple way to send commands from a CLI process
 * (such as an MCP server) to a GUI application and receive responses.
 *
 * <h2>Architecture</h2>
 * <pre>
 * ┌─────────────────┐                    ┌─────────────────┐
 * │   CLI Process   │                    │   GUI Process   │
 * │   (MCP Server)  │                    │   (JavaFX/Swing)│
 * └────────┬────────┘                    └────────┬────────┘
 *          │                                      │
 *          │  GuiBridge                GuiBridgeReceiver
 *          │                                      │
 *          │ 1. Write request.properties          │
 *          ▼                                      │
 *    ┌───────────┐                                │
 *    │ /tmp/req  │                                │
 *    │.properties│                                │
 *    └─────┬─────┘                                │
 *          │                                      │
 *          │ 2. Send path via IPC ───────────────►│
 *          │    (Hive or Deep Link)               │
 *          │                                      │ 3. Read request,
 *          │                                      │    invoke handler
 *          │                                      ▼
 *          │                               ┌───────────┐
 *          │◄──────────────────────────────│ /tmp/resp │
 *          │ 4. Poll for response          │.properties│
 *          │                               └───────────┘
 *          ▼
 *    Return result
 * </pre>
 *
 * <h2>Key Classes</h2>
 * <ul>
 *   <li>{@link ca.weblite.jdeploy.app.bridge.GuiBridge} - Sender side (CLI process)</li>
 *   <li>{@link ca.weblite.jdeploy.app.bridge.GuiBridgeReceiver} - Receiver side (GUI process)</li>
 *   <li>{@link ca.weblite.jdeploy.app.bridge.GuiBridgeHandler} - Handler interface</li>
 * </ul>
 *
 * <h2>Quick Start - CLI Side</h2>
 * <pre>{@code
 * GuiBridge bridge = new GuiBridge("myapp");
 * Map<String, String> result = bridge.sendCommand("list_notes", Map.of());
 * }</pre>
 *
 * <h2>Quick Start - GUI Side</h2>
 * <pre>{@code
 * GuiBridgeHandler handler = (command, params) -> {
 *     return switch (command) {
 *         case "list_notes" -> Map.of("count", "5");
 *         default -> throw new IllegalArgumentException("Unknown: " + command);
 *     };
 * };
 * GuiBridgeReceiver receiver = new GuiBridgeReceiver(handler);
 * receiver.registerHiveListener();
 * }</pre>
 *
 * @see ca.weblite.jdeploy.app.bridge.GuiBridge
 * @see ca.weblite.jdeploy.app.bridge.GuiBridgeReceiver
 * @see ca.weblite.jdeploy.app.bridge.GuiBridgeHandler
 */
package ca.weblite.jdeploy.app.bridge;
