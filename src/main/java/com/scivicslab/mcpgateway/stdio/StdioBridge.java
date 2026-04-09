package com.scivicslab.mcpgateway.stdio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Routes MCP JSON-RPC requests to stdio-backed server processes.
 *
 * Special handling:
 * - "initialize": the stdio process is already initialized at startup.
 *   Return cached capabilities without touching the process.
 *   Also returns a session ID header (serverName) so MCP clients can establish sessions.
 * - "notifications/initialized": no-op (process already past this state).
 * - All other methods: forward to the stdio process and return the response.
 */
@ApplicationScoped
public class StdioBridge {

    private static final Logger logger = Logger.getLogger(StdioBridge.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();

    @Inject
    StdioRegistry registry;

    @Inject
    AggregatingBridge aggregatingBridge;

    /**
     * Result of handling an MCP request for a stdio-backed server.
     *
     * @param body      JSON-RPC response string, or null for notifications that need no response
     * @param sessionId Mcp-Session-Id to include in the response header, or null
     */
    public record StdioResult(String body, String sessionId) {
        static StdioResult of(String body, String sessionId) { return new StdioResult(body, sessionId); }
        static StdioResult notification() { return new StdioResult(null, null); }
        static StdioResult error(String body) { return new StdioResult(body, null); }
    }

    /**
     * Handle an MCP JSON-RPC request for a stdio-backed server.
     * The virtual server name "_all" aggregates all registered stdio servers.
     */
    public StdioResult handle(String serverName, String jsonRpcBody) {
        if ("_all".equals(serverName)) {
            return aggregatingBridge.handle(jsonRpcBody);
        }

        StdioProcess proc = registry.lookup(serverName).orElse(null);
        if (proc == null) {
            return StdioResult.error(errorResponse(idOf(jsonRpcBody), -32001, "stdio server not found: " + serverName));
        }
        if (!proc.isAlive()) {
            return StdioResult.error(errorResponse(idOf(jsonRpcBody), -32002, "stdio server process has exited: " + serverName));
        }

        try {
            JsonNode req = mapper.readTree(jsonRpcBody);
            String method = req.path("method").asText("");

            // The process is already initialized — return cached capabilities.
            // Include a stable session ID so MCP clients can cache it.
            if ("initialize".equals(method)) {
                JsonNode caps = proc.getCachedCapabilities();
                ObjectNode resp = mapper.createObjectNode();
                resp.put("jsonrpc", "2.0");
                resp.set("id", req.get("id"));
                resp.set("result", caps != null ? caps : mapper.createObjectNode());
                return StdioResult.of(mapper.writeValueAsString(resp), serverName);
            }

            // Process is past this state — acknowledge silently
            if ("notifications/initialized".equals(method)) {
                return StdioResult.notification();
            }

            // Forward everything else (tools/list, tools/call, ping, etc.)
            return StdioResult.of(proc.request(jsonRpcBody), null);

        } catch (Exception e) {
            logger.log(Level.WARNING, "StdioBridge error for server '" + serverName + "'", e);
            return StdioResult.error(errorResponse(idOf(jsonRpcBody), -32603, "Internal error: " + e.getMessage()));
        }
    }

    private String idOf(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            return node.has("id") && !node.get("id").isNull() ? node.get("id").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String errorResponse(String id, int code, String message) {
        try {
            ObjectNode resp = mapper.createObjectNode();
            resp.put("jsonrpc", "2.0");
            if (id != null) resp.put("id", id); else resp.putNull("id");
            ObjectNode error = resp.putObject("error");
            error.put("code", code);
            error.put("message", message);
            return mapper.writeValueAsString(resp);
        } catch (Exception e) {
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}";
        }
    }
}
