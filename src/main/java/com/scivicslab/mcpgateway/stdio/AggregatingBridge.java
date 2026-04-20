package com.scivicslab.mcpgateway.stdio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scivicslab.mcpgateway.builtin.AllToolsCache;
import com.scivicslab.mcpgateway.builtin.BuiltinTool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Aggregating MCP endpoint that presents all registered stdio servers as a single
 * virtual server accessible at {@code POST /mcp}.
 *
 * <p>Handles:
 * <ul>
 *   <li>{@code initialize} — returns combined capabilities; session ID = "all"</li>
 *   <li>{@code notifications/initialized} — no-op</li>
 *   <li>{@code tools/list} — merges tool lists from all stdio servers; rebuilds routing table</li>
 *   <li>{@code tools/call} — routes to the correct server based on tool name</li>
 * </ul>
 * </p>
 */
@ApplicationScoped
public class AggregatingBridge {

    private static final Logger logger = Logger.getLogger(AggregatingBridge.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String SESSION_ID = "all";

    private static final String BUILTIN_SERVER = "__builtin__";

    @Inject StdioRegistry registry;
    @Inject Instance<BuiltinTool> builtinTools;
    @Inject AllToolsCache allToolsCache;

    /** Maps tool name → server name (stdio server name, or BUILTIN_SERVER). Rebuilt on each tools/list call. */
    private final ConcurrentHashMap<String, String> toolToServer = new ConcurrentHashMap<>();
    private final AtomicInteger idSeq = new AtomicInteger(100);

    public StdioBridge.StdioResult handle(String jsonRpcBody) {
        try {
            JsonNode req = mapper.readTree(jsonRpcBody);
            String method = req.path("method").asText("");

            return switch (method) {
                case "initialize"              -> handleInitialize(req);
                case "notifications/initialized" -> StdioBridge.StdioResult.notification();
                case "tools/list"              -> handleToolsList(req);
                case "tools/call"              -> handleToolsCall(req, jsonRpcBody);
                default -> StdioBridge.StdioResult.error(
                        errorResponse(idOf(req), -32601,
                                "Method not supported on aggregated endpoint: " + method));
            };

        } catch (Exception e) {
            logger.log(Level.WARNING, "AggregatingBridge error", e);
            return StdioBridge.StdioResult.error(
                    errorResponse(null, -32603, "Internal error: " + e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ //

    private StdioBridge.StdioResult handleInitialize(JsonNode req) throws Exception {
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", "2024-11-05");
        result.putObject("capabilities").putObject("tools");
        result.putObject("serverInfo")
                .put("name", "mcp-gateway-aggregated")
                .put("version", "1.0.0");

        ObjectNode resp = mapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.set("id", req.get("id"));
        resp.set("result", result);
        return StdioBridge.StdioResult.of(mapper.writeValueAsString(resp), SESSION_ID);
    }

    private StdioBridge.StdioResult handleToolsList(JsonNode req) throws Exception {
        ArrayNode merged = mapper.createArrayNode();
        toolToServer.clear();

        // Built-in tools — discovered via CDI; add new BuiltinTool beans to extend
        for (BuiltinTool tool : builtinTools) {
            merged.add(builtinToolNode(tool.name(), tool.description(), tool.inputSchema()));
            toolToServer.put(tool.name(), BUILTIN_SERVER);
        }

        // stdio-backed tools
        for (StdioProcess proc : registry.all()) {
            if (!proc.isAlive()) continue;
            try {
                String listReq = "{\"jsonrpc\":\"2.0\",\"id\":" + idSeq.getAndIncrement()
                        + ",\"method\":\"tools/list\",\"params\":{}}";
                String raw = proc.request(listReq);
                if (raw == null) continue;

                JsonNode resp = mapper.readTree(raw);
                JsonNode tools = resp.path("result").path("tools");
                if (tools.isArray()) {
                    for (JsonNode tool : tools) {
                        merged.add(tool);
                        toolToServer.put(tool.path("name").asText(), proc.getName());
                    }
                }
            } catch (Exception e) {
                logger.warning("tools/list failed for server '" + proc.getName() + "': " + e.getMessage());
            }
        }

        // Update AllToolsCache for FindToolsTool to search (exclude find_tools itself)
        java.util.List<AllToolsCache.Entry> cacheEntries = new java.util.ArrayList<>();
        for (JsonNode t : merged) {
            String n = t.path("name").asText();
            if ("find_tools".equals(n)) continue;
            cacheEntries.add(new AllToolsCache.Entry(
                    n,
                    t.path("description").asText(""),
                    t.path("inputSchema").toString()));
        }
        allToolsCache.update(cacheEntries);

        ObjectNode result = mapper.createObjectNode();
        result.set("tools", merged);

        ObjectNode resp = mapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.set("id", req.get("id"));
        resp.set("result", result);
        return StdioBridge.StdioResult.of(mapper.writeValueAsString(resp), null);
    }

    private StdioBridge.StdioResult handleToolsCall(JsonNode req, String jsonRpcBody) throws Exception {
        String toolName = req.path("params").path("name").asText("");

        String serverName = toolToServer.get(toolName);
        if (serverName == null) {
            // Routing table may be stale — rediscover
            handleToolsList(req);
            serverName = toolToServer.get(toolName);
        }
        if (serverName == null) {
            return StdioBridge.StdioResult.error(
                    errorResponse(idOf(req), -32601, "Tool not found: " + toolName));
        }

        // Built-in tool — execute directly in the gateway JVM
        if (BUILTIN_SERVER.equals(serverName)) {
            return handleBuiltinCall(req, toolName);
        }

        StdioProcess proc = registry.lookup(serverName).orElse(null);
        if (proc == null || !proc.isAlive()) {
            return StdioBridge.StdioResult.error(
                    errorResponse(idOf(req), -32002, "Server unavailable: " + serverName));
        }

        return StdioBridge.StdioResult.of(proc.request(jsonRpcBody), null);
    }

    private StdioBridge.StdioResult handleBuiltinCall(JsonNode req, String toolName) throws Exception {
        JsonNode arguments = req.path("params").path("arguments");
        String text = "Unknown built-in tool: " + toolName;
        for (BuiltinTool tool : builtinTools) {
            if (tool.name().equals(toolName)) {
                text = tool.call(arguments);
                break;
            }
        }

        ObjectNode content = mapper.createObjectNode();
        content.put("type", "text");
        content.put("text", text);

        ObjectNode result = mapper.createObjectNode();
        result.put("isError", false);
        result.set("content", mapper.createArrayNode().add(content));

        ObjectNode resp = mapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.set("id", req.get("id"));
        resp.set("result", result);
        return StdioBridge.StdioResult.of(mapper.writeValueAsString(resp), null);
    }

    private static ObjectNode builtinToolNode(String name, String description, String schemaJson) {
        try {
            ObjectNode tool = mapper.createObjectNode();
            tool.put("name", name);
            tool.put("description", description);
            tool.set("inputSchema", mapper.readTree(schemaJson));
            return tool;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build tool node for: " + name, e);
        }
    }

    // ------------------------------------------------------------------ //

    private static String idOf(JsonNode req) {
        if (req == null) return null;
        JsonNode id = req.get("id");
        return (id != null && !id.isNull()) ? id.asText() : null;
    }

    private static String errorResponse(String id, int code, String message) {
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
