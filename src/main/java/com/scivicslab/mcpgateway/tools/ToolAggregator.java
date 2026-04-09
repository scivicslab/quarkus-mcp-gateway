package com.scivicslab.mcpgateway.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scivicslab.mcpgateway.registry.ServerEntry;
import com.scivicslab.mcpgateway.registry.ServerRegistry;
import com.scivicslab.mcpgateway.registry.ServerRegistryEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Aggregates tools from all registered MCP servers.
 * Maintains a cached view of available tools per server.
 * Handles tool discovery via MCP initialize + tools/list,
 * and routes tool calls to the correct backend server.
 */
@ApplicationScoped
public class ToolAggregator {

    private static final Logger logger = Logger.getLogger(ToolAggregator.class.getName());
    private static final Duration CALL_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration LIST_TIMEOUT = Duration.ofSeconds(30);

    @Inject
    ServerRegistry registry;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger rpcIdCounter = new AtomicInteger(1);

    // serverName -> list of tools from that server
    private final Map<String, List<ToolInfo>> toolsByServer = new ConcurrentHashMap<>();

    // serverName -> MCP session ID (from initialize)
    private final Map<String, String> mcpSessions = new ConcurrentHashMap<>();

    /**
     * React to server registration/unregistration events.
     */
    void onRegistryEvent(@Observes ServerRegistryEvent event) {
        if ("registered".equals(event.action())) {
            refreshServer(event.serverName());
        } else if ("unregistered".equals(event.action())) {
            toolsByServer.remove(event.serverName());
            mcpSessions.remove(event.serverName());
            logger.info("Removed tools for unregistered server: " + event.serverName());
        }
    }

    /**
     * Refresh tools for all currently registered healthy servers.
     */
    public void refreshAll() {
        for (ServerEntry entry : registry.listAll()) {
            if (entry.isHealthy()) {
                refreshServer(entry.getName());
            }
        }
    }

    /**
     * Refresh tools for a single server by performing MCP initialize + tools/list.
     */
    public void refreshServer(String serverName) {
        Optional<ServerEntry> opt = registry.lookup(serverName);
        if (opt.isEmpty()) {
            logger.warning("Cannot refresh tools: server not found: " + serverName);
            return;
        }

        ServerEntry entry = opt.get();
        String mcpUrl = entry.getUrl() + "/mcp";

        try {
            // Step 1: initialize
            String sessionId = initialize(mcpUrl, serverName);
            if (sessionId != null) {
                mcpSessions.put(serverName, sessionId);
            }

            // Step 2: tools/list
            List<ToolInfo> tools = listTools(mcpUrl, serverName, mcpSessions.get(serverName));
            toolsByServer.put(serverName, tools);
            logger.info("Refreshed " + tools.size() + " tools from server: " + serverName);

        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to refresh tools from " + serverName, e);
            toolsByServer.put(serverName, List.of());
        }
    }

    /**
     * Get all tools from all servers.
     */
    public List<ToolInfo> getAllTools() {
        List<ToolInfo> all = new ArrayList<>();
        for (List<ToolInfo> tools : toolsByServer.values()) {
            all.addAll(tools);
        }
        return all;
    }

    /**
     * Find a tool by name. Tries exact match first, then qualified name.
     * Returns empty if ambiguous (same name on multiple servers) and no qualified match.
     */
    public Optional<ToolInfo> findTool(String name) {
        // Try qualified name match first
        for (List<ToolInfo> tools : toolsByServer.values()) {
            for (ToolInfo tool : tools) {
                if (tool.qualifiedName().equals(name)) {
                    return Optional.of(tool);
                }
            }
        }

        // Try exact name match
        List<ToolInfo> matches = new ArrayList<>();
        for (List<ToolInfo> tools : toolsByServer.values()) {
            for (ToolInfo tool : tools) {
                if (tool.name().equals(name)) {
                    matches.add(tool);
                }
            }
        }

        if (matches.size() == 1) {
            return Optional.of(matches.get(0));
        }
        if (matches.size() > 1) {
            logger.warning("Ambiguous tool name '" + name + "' found on " + matches.size()
                    + " servers. Use qualified name (serverName__toolName).");
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * Call a tool on its backend MCP server.
     * If the session is stale, re-initializes and retries once.
     */
    public ToolCallResult callTool(String toolName, JsonNode arguments) {
        Optional<ToolInfo> opt = findTool(toolName);
        if (opt.isEmpty()) {
            return ToolCallResult.error("Tool not found: " + toolName);
        }

        ToolInfo tool = opt.get();
        Optional<ServerEntry> serverOpt = registry.lookup(tool.server());
        if (serverOpt.isEmpty()) {
            return ToolCallResult.error("Server not found for tool: " + toolName);
        }

        String mcpUrl = serverOpt.get().getUrl() + "/mcp";

        for (int attempt = 0; attempt < 2; attempt++) {
            String sessionId = mcpSessions.get(tool.server());
            try {
                ObjectNode params = mapper.createObjectNode();
                params.put("name", tool.name());
                params.set("arguments", arguments != null ? arguments : mapper.createObjectNode());

                String body = buildJsonRpc("tools/call", params);
                String response = sendMcpRequest(mcpUrl, body, sessionId, CALL_TIMEOUT);

                JsonNode root = mapper.readTree(response);

                // Session expired or error — re-initialize and retry
                if (root.has("error") || root.get("result") == null) {
                    if (attempt == 0) {
                        logger.info("Tool call got error/empty result, re-initializing session for: "
                                + tool.server());
                        refreshServer(tool.server());
                        continue;
                    }
                    if (root.has("error")) {
                        String errMsg = root.get("error").has("message")
                                ? root.get("error").get("message").asText()
                                : root.get("error").toString();
                        return ToolCallResult.error(errMsg);
                    }
                    return ToolCallResult.error("No result in response");
                }

                JsonNode result = root.get("result");
                boolean isError = result.has("isError") && result.get("isError").asBoolean();
                List<ToolCallResult.ContentItem> content = new ArrayList<>();
                if (result.has("content") && result.get("content").isArray()) {
                    for (JsonNode item : result.get("content")) {
                        String type = item.has("type") ? item.get("type").asText() : "text";
                        String text = item.has("text") ? item.get("text").asText() : "";
                        content.add(new ToolCallResult.ContentItem(type, text));
                    }
                }
                return new ToolCallResult(content, isError);

            } catch (Exception e) {
                if (attempt == 0) {
                    logger.info("Tool call exception, re-initializing session for: " + tool.server());
                    refreshServer(tool.server());
                    continue;
                }
                logger.log(Level.WARNING, "Tool call failed: " + toolName, e);
                return ToolCallResult.error("Tool call failed: " + e.getMessage());
            }
        }
        return ToolCallResult.error("Tool call failed after retry: " + toolName);
    }

    // --- MCP protocol helpers ---

    private String initialize(String mcpUrl, String serverName) throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", "2024-11-05");
        ObjectNode clientInfo = mapper.createObjectNode();
        clientInfo.put("name", "mcp-gateway");
        clientInfo.put("version", "1.0.0");
        params.set("clientInfo", clientInfo);
        ObjectNode capabilities = mapper.createObjectNode();
        params.set("capabilities", capabilities);

        String body = buildJsonRpc("initialize", params);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(mcpUrl))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .timeout(LIST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        String sessionId = response.headers().firstValue("Mcp-Session-Id").orElse(null);
        logger.fine("MCP initialize for " + serverName + ": status=" + response.statusCode()
                + ", session=" + sessionId);

        // Send initialized notification
        String notification = buildJsonRpcNotification("notifications/initialized");
        HttpRequest.Builder notifBuilder = HttpRequest.newBuilder()
                .uri(URI.create(mcpUrl))
                .header("Content-Type", "application/json")
                .timeout(LIST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(notification));
        if (sessionId != null) {
            notifBuilder.header("Mcp-Session-Id", sessionId);
        }
        client.send(notifBuilder.build(), HttpResponse.BodyHandlers.ofString());

        return sessionId;
    }

    private List<ToolInfo> listTools(String mcpUrl, String serverName, String sessionId) throws Exception {
        String body = buildJsonRpc("tools/list", mapper.createObjectNode());
        String response = sendMcpRequest(mcpUrl, body, sessionId, LIST_TIMEOUT);

        JsonNode root = mapper.readTree(response);
        if (root.has("error")) {
            logger.warning("tools/list error from " + serverName + ": " + root.get("error"));
            return List.of();
        }

        JsonNode result = root.get("result");
        if (result == null || !result.has("tools")) {
            return List.of();
        }

        List<ToolInfo> tools = new ArrayList<>();
        for (JsonNode toolNode : result.get("tools")) {
            String name = toolNode.get("name").asText();
            String qualifiedName = serverName + "__" + name;
            String description = toolNode.has("description") ? toolNode.get("description").asText() : "";
            JsonNode inputSchema = toolNode.has("inputSchema") ? toolNode.get("inputSchema") : mapper.createObjectNode();
            tools.add(new ToolInfo(name, qualifiedName, description, inputSchema, serverName));
        }
        return tools;
    }

    private String sendMcpRequest(String mcpUrl, String body, String sessionId, Duration timeout) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(mcpUrl))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(body));

        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId);
        }

        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    private String buildJsonRpc(String method, JsonNode params) {
        ObjectNode rpc = mapper.createObjectNode();
        rpc.put("jsonrpc", "2.0");
        rpc.put("id", rpcIdCounter.getAndIncrement());
        rpc.put("method", method);
        rpc.set("params", params);
        try {
            return mapper.writeValueAsString(rpc);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize JSON-RPC", e);
        }
    }

    private String buildJsonRpcNotification(String method) {
        ObjectNode rpc = mapper.createObjectNode();
        rpc.put("jsonrpc", "2.0");
        rpc.put("method", method);
        try {
            return mapper.writeValueAsString(rpc);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize JSON-RPC notification", e);
        }
    }
}
