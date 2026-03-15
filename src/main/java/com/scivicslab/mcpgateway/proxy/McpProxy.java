package com.scivicslab.mcpgateway.proxy;

import com.scivicslab.mcpgateway.registry.ServerEntry;
import com.scivicslab.mcpgateway.registry.ServerRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Proxies MCP JSON-RPC requests to backend servers.
 * Maintains per-server MCP session IDs.
 */
@ApplicationScoped
public class McpProxy {

    private static final Logger logger = Logger.getLogger(McpProxy.class.getName());
    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    @Inject
    ServerRegistry registry;

    private final ObjectMapper mapper = new ObjectMapper();

    // Maps: gateway session key (serverName + clientSessionId) -> backend Mcp-Session-Id
    private final Map<String, String> backendSessions = new ConcurrentHashMap<>();

    // Maps: gateway session key -> caller name (from initialize clientInfo)
    private final Map<String, String> callerNames = new ConcurrentHashMap<>();

    // Maps: session ID -> session metadata (for HATEOAS /api/sessions/{id} endpoint)
    private final Map<String, Map<String, Object>> sessionMeta = new ConcurrentHashMap<>();

    /**
     * Forward a JSON-RPC request to the named MCP server.
     *
     * @param serverName    registered server name
     * @param jsonRpcBody   the raw JSON-RPC request body
     * @param clientSessionId  optional client-side session ID for session affinity
     * @return proxy result containing response body, status code, and backend session ID
     */
    public ProxyResult forward(String serverName, String jsonRpcBody, String clientSessionId, String remoteAddress) {
        var entry = registry.lookup(serverName);
        if (entry.isEmpty()) {
            return ProxyResult.error(404, "Unknown MCP server: " + serverName);
        }

        ServerEntry server = entry.get();
        String targetUrl = server.getUrl() + "/mcp";
        String sessionKey = serverName + ":" + (clientSessionId != null ? clientSessionId : "default");

        try {
            // Parse JSON-RPC to inspect method and inject _caller
            String forwardBody = injectCaller(jsonRpcBody, sessionKey, serverName, remoteAddress);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            var requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .timeout(TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(forwardBody));

            // Forward backend session ID if we have one
            String backendSessionId = backendSessions.get(sessionKey);
            if (backendSessionId != null) {
                requestBuilder.header("Mcp-Session-Id", backendSessionId);
            }

            HttpResponse<String> response = client.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            // Capture backend session ID and map it for future lookups
            response.headers().firstValue("Mcp-Session-Id").ifPresent(sid -> {
                backendSessions.put(sessionKey, sid);
                // Also map by backend session ID so client can use it directly
                String sidKey = serverName + ":" + sid;
                backendSessions.put(sidKey, sid);
                // Copy caller name to session-based key
                String callerName = callerNames.get(sessionKey);
                if (callerName != null) {
                    callerNames.put(sidKey, callerName);
                }
            });

            return new ProxyResult(
                    response.statusCode(),
                    response.body(),
                    response.headers().firstValue("Content-Type").orElse("application/json"),
                    backendSessions.get(sessionKey));

        } catch (Exception e) {
            logger.log(Level.WARNING, "Proxy to " + serverName + " failed", e);
            return ProxyResult.error(502, "Proxy error: " + e.getMessage());
        }
    }

    /**
     * Inspects the JSON-RPC body. On "initialize", captures clientInfo.name.
     * On "tools/call", injects "_caller" into params.
     */
    private String injectCaller(String jsonRpcBody, String sessionKey, String serverName, String remoteAddress) {
        try {
            JsonNode root = mapper.readTree(jsonRpcBody);
            if (!root.isObject()) return jsonRpcBody;

            String method = root.has("method") ? root.get("method").asText() : "";

            if ("initialize".equals(method)) {
                // Capture caller name from clientInfo
                JsonNode params = root.get("params");
                if (params != null && params.has("clientInfo")) {
                    JsonNode clientInfo = params.get("clientInfo");
                    if (clientInfo.has("name")) {
                        String callerName = clientInfo.get("name").asText();
                        callerNames.put(sessionKey, callerName);
                        logger.info("Captured caller: " + callerName + " for session " + sessionKey);
                    }
                }
                return jsonRpcBody; // Don't modify initialize requests
            }

            if ("tools/call".equals(method) || "tools/list".equals(method)) {
                // Store session metadata and inject HATEOAS URL
                String callerName = callerNames.getOrDefault(sessionKey, "unknown");
                String sessionId = sessionKey.replace(":", "-");

                Map<String, Object> meta = new ConcurrentHashMap<>();
                meta.put("caller", callerName);
                meta.put("remoteAddress", remoteAddress != null ? remoteAddress : "unknown");
                meta.put("target", serverName);
                meta.put("via", "mcp-gateway");
                meta.put("timestamp", java.time.Instant.now().toString());

                // Enrich with registered server info if caller matches a known server
                var callerServer = registry.lookup(callerName);
                if (callerServer.isPresent()) {
                    ServerEntry entry = callerServer.get();
                    meta.put("callerUrl", entry.getUrl());
                    meta.put("callerDescription", entry.getDescription());
                    meta.put("registered", true);
                } else {
                    meta.put("registered", false);
                }

                sessionMeta.put(sessionId, meta);

                String callerUrl = "http://localhost:8888/api/sessions/" + sessionId;

                ObjectNode rootObj = (ObjectNode) root;
                ObjectNode params = root.has("params") ? (ObjectNode) root.get("params") : mapper.createObjectNode();
                params.put("_caller", callerUrl);

                // Also inject into arguments as string so @ToolArg(String) can access it
                if (params.has("arguments") && params.get("arguments").isObject()) {
                    ((ObjectNode) params.get("arguments")).put("_caller", callerUrl);
                }

                rootObj.set("params", params);

                String modified = mapper.writeValueAsString(rootObj);
                logger.info("Injected _caller URL into " + method + ": " + callerUrl);
                return modified;
            }

            return jsonRpcBody;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to parse/inject _caller, forwarding as-is", e);
            return jsonRpcBody;
        }
    }

    /**
     * Returns session metadata for the HATEOAS endpoint.
     */
    public Map<String, Object> getSessionMeta(String sessionId) {
        return sessionMeta.get(sessionId);
    }
}
