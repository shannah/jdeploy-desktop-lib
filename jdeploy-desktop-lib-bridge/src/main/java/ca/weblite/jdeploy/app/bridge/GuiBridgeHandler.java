package ca.weblite.jdeploy.app.bridge;

import java.util.Map;

/**
 * Handler interface for processing commands received by the GUI application.
 * <p>
 * Implement this interface to define how your GUI application responds to
 * commands sent from CLI processes (e.g., MCP servers).
 *
 * <h2>Example Implementation</h2>
 * <pre>{@code
 * GuiBridgeHandler handler = (command, params) -> {
 *     return switch (command) {
 *         case "list_notes" -> {
 *             List<Note> notes = store.getAllNotes();
 *             Map<String, String> result = new HashMap<>();
 *             result.put("count", String.valueOf(notes.size()));
 *             // Add note data...
 *             yield result;
 *         }
 *         case "create_note" -> {
 *             Note n = store.createNote(params.get("title"), params.get("content"));
 *             yield Map.of("id", n.getId(), "title", n.getTitle());
 *         }
 *         default -> throw new IllegalArgumentException("Unknown command: " + command);
 *     };
 * };
 * }</pre>
 *
 * @see GuiBridgeReceiver
 * @see GuiBridge
 */
@FunctionalInterface
public interface GuiBridgeHandler {

    /**
     * Handle a command from a CLI process.
     *
     * @param command the command name (e.g., "list_notes", "create_note")
     * @param params  the command parameters as key-value pairs
     * @return the result as key-value pairs
     * @throws Exception if the command fails (message will be sent back as error)
     */
    Map<String, String> handleCommand(String command, Map<String, String> params) throws Exception;
}
