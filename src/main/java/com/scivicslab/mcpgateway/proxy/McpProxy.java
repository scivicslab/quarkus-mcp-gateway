package com.scivicslab.mcpgateway.proxy;

import com.scivicslab.mcpgateway.registry.ServerEntry;
import com.scivicslab.mcpgateway.registry.ServerRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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

    // Maps: gateway session key (serverName + clientSessionId) -> backend Mcp-Session-Id
    private final Map<String, String> backendSessions = new ConcurrentHashMap<>();

    /**
     * Forward a JSON-RPC request to the named MCP server.
     *
     * @param serverName    registered server name
     * @param jsonRpcBody   the raw JSON-RPC request body
     * @param clientSessionId  optional client-side session ID for session affinity
     * @return proxy result containing response body, status code, and backend session ID
     */
    public ProxyResult forward(String serverName, String jsonRpcBody, String clientSessionId) {
        var entry = registry.lookup(serverName);
        if (entry.isEmpty()) {
            return ProxyResult.error(404, "Unknown MCP server: " + serverName);
        }

        ServerEntry server = entry.get();
        String targetUrl = server.getUrl() + "/mcp";

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            var requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .timeout(TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonRpcBody));

            // Forward backend session ID if we have one
            String sessionKey = serverName + ":" + (clientSessionId != null ? clientSessionId : "default");
            String backendSessionId = backendSessions.get(sessionKey);
            if (backendSessionId != null) {
                requestBuilder.header("Mcp-Session-Id", backendSessionId);
            }

            HttpResponse<String> response = client.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            // Capture backend session ID
            response.headers().firstValue("Mcp-Session-Id").ifPresent(sid -> {
                backendSessions.put(sessionKey, sid);
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
}
