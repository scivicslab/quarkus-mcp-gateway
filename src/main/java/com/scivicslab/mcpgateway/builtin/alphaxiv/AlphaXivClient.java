package com.scivicslab.mcpgateway.builtin.alphaxiv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Shared MCP client for the alphaXiv API (https://api.alphaxiv.org/mcp/v1).
 *
 * <p>alphaXiv exposes a single MCP-over-HTTP endpoint (Streamable HTTP Transport).
 * Every tool call is a JSON-RPC 2.0 POST with method {@code tools/call}.</p>
 *
 * <p>Authentication requires a Bearer token from alphaXiv (free account).
 * To obtain one: create an account at https://alphaxiv.org, log in via browser,
 * open DevTools &gt; Application &gt; Local Storage &gt; https://alphaxiv.org,
 * and copy the access token. Set it via the {@code mcp.alphaxiv.access-token}
 * config property in {@code application.properties}.</p>
 *
 * <p>Alternatively, if {@code feynman alpha login} has been run, the token is
 * read automatically from {@code ~/.ahub/auth.json}.</p>
 *
 * <p>If no token is configured the client is disabled and all tool calls return
 * a "not configured" message rather than throwing.</p>
 */
@ApplicationScoped
public class AlphaXivClient {

    private static final Logger LOG = Logger.getLogger(AlphaXivClient.class.getName());

    static final String ALPHAXIV_MCP_URL = "https://api.alphaxiv.org/mcp/v1";
    static final String CLERK_TOKEN_URL  = "https://clerk.alphaxiv.org/oauth/token";

    @ConfigProperty(name = "mcp.alphaxiv.access-token")
    java.util.Optional<String> configAccessToken;

    @ConfigProperty(name = "mcp.alphaxiv.refresh-token")
    java.util.Optional<String> configRefreshToken;

    @ConfigProperty(name = "mcp.alphaxiv.client-id")
    java.util.Optional<String> configClientId;

    private volatile String accessToken;
    private volatile String refreshToken;
    private volatile String clientId;
    private volatile String sessionId;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger idSeq = new AtomicInteger(1);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @PostConstruct
    void init() {
        // Try reading from ~/.ahub/auth.json first (set by feynman alpha login)
        try {
            Path ahubAuth = Path.of(System.getProperty("user.home"), ".ahub", "auth.json");
            if (Files.exists(ahubAuth)) {
                JsonNode auth = mapper.readTree(ahubAuth.toFile());
                // auth.json uses snake_case keys (written by feynman alpha login)
                accessToken  = auth.path("access_token").asText("");
                refreshToken = auth.path("refresh_token").asText("");
                clientId     = auth.path("client_id").asText("");
                if (!accessToken.isBlank()) {
                    LOG.info("alphaXiv: loaded token from ~/.ahub/auth.json");
                    return;
                }
            }
        } catch (Exception e) {
            LOG.fine("alphaXiv: ~/.ahub/auth.json not readable: " + e.getMessage());
        }

        // Fall back to config properties
        accessToken  = configAccessToken.orElse("");
        refreshToken = configRefreshToken.orElse("");
        clientId     = configClientId.orElse("");

        if (!accessToken.isBlank()) {
            LOG.info("alphaXiv: loaded token from config property");
        } else {
            LOG.info("alphaXiv: no token configured — tools will be disabled");
        }
    }

    /** Returns true if an access token is available. */
    public boolean isEnabled() {
        return accessToken != null && !accessToken.isBlank();
    }

    /**
     * Calls an alphaXiv MCP tool and returns the result text.
     *
     * @param toolName  alphaXiv tool name (e.g. "embedding_similarity_search")
     * @param arguments JSON arguments node
     * @return result text, or an error message string
     */
    public String callTool(String toolName, JsonNode arguments) {
        if (!isEnabled()) {
            return "alphaXiv is not configured. Run `feynman alpha login` and restart mcp-gateway, "
                 + "or set mcp.alphaxiv.access-token in application.properties.";
        }

        try {
            String result = doCallTool(toolName, arguments);
            // If we get an auth error, try refreshing once
            if (result.startsWith("AUTH_ERROR:")) {
                LOG.info("alphaXiv: auth error, attempting token refresh");
                if (tryRefresh()) {
                    sessionId = null; // re-initialize session with new token
                    result = doCallTool(toolName, arguments);
                }
            }
            return result;
        } catch (Exception e) {
            LOG.warning("alphaXiv callTool failed (" + toolName + "): " + e.getMessage());
            return "alphaXiv error: " + e.getMessage();
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private String doCallTool(String toolName, JsonNode arguments) throws Exception {
        ensureSession();

        ObjectNode params = mapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", arguments);

        ObjectNode rpc = mapper.createObjectNode();
        rpc.put("jsonrpc", "2.0");
        rpc.put("id", idSeq.getAndIncrement());
        rpc.put("method", "tools/call");
        rpc.set("params", params);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(ALPHAXIV_MCP_URL))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + accessToken);

        if (sessionId != null) {
            requestBuilder.header("Mcp-Session-Id", sessionId);
        }

        HttpRequest request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(rpc.toString()))
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 401 || response.statusCode() == 403) {
            return "AUTH_ERROR: HTTP " + response.statusCode();
        }
        if (response.statusCode() >= 400) {
            return "alphaXiv HTTP " + response.statusCode() + ": " + response.body();
        }

        String body = response.body();
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (contentType.contains("text/event-stream")) {
            body = extractJsonFromSse(body);
        }
        return parseToolResult(body);
    }

    /** Extract the first JSON payload from an SSE stream (lines starting with "data: "). */
    private String extractJsonFromSse(String sse) {
        for (String line : sse.split("\n")) {
            line = line.strip();
            if (line.startsWith("data:")) {
                String data = line.substring(5).strip();
                if (!data.isEmpty() && !data.equals("[DONE]")) {
                    return data;
                }
            }
        }
        return sse; // fall back to raw body if no data line found
    }

    private void ensureSession() throws Exception {
        if (sessionId != null) return;

        ObjectNode clientInfo = mapper.createObjectNode();
        clientInfo.put("name", "quarkus-mcp-gateway");
        clientInfo.put("version", "1.0");

        ObjectNode initParams = mapper.createObjectNode();
        initParams.put("protocolVersion", "2024-11-05");
        initParams.set("clientInfo", clientInfo);
        initParams.set("capabilities", mapper.createObjectNode());

        ObjectNode rpc = mapper.createObjectNode();
        rpc.put("jsonrpc", "2.0");
        rpc.put("id", idSeq.getAndIncrement());
        rpc.put("method", "initialize");
        rpc.set("params", initParams);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ALPHAXIV_MCP_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + accessToken)
                .POST(HttpRequest.BodyPublishers.ofString(rpc.toString()))
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        LOG.info("alphaXiv: initialize status=" + response.statusCode()
                + " body=" + response.body().substring(0, Math.min(200, response.body().length())));

        String newSession = response.headers()
                .firstValue("Mcp-Session-Id").orElse(null);
        if (newSession != null) {
            sessionId = newSession;
            LOG.info("alphaXiv: session established: " + sessionId);
        } else {
            LOG.warning("alphaXiv: no Mcp-Session-Id in initialize response");
        }
    }

    private String parseToolResult(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root.has("error")) {
                return "alphaXiv error: " + root.path("error").path("message").asText(json);
            }
            JsonNode content = root.path("result").path("content");
            if (content.isArray() && content.size() > 0) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode item : content) {
                    if ("text".equals(item.path("type").asText())) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(item.path("text").asText(""));
                    }
                }
                return sb.length() > 0 ? sb.toString() : json;
            }
            return json;
        } catch (Exception e) {
            return json;
        }
    }

    private boolean tryRefresh() {
        if (refreshToken.isBlank() || clientId.isBlank()) return false;
        try {
            String body = "grant_type=refresh_token"
                    + "&refresh_token=" + refreshToken
                    + "&client_id=" + clientId;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(CLERK_TOKEN_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode tokens = mapper.readTree(response.body());
                String newToken = tokens.path("access_token").asText("");
                if (!newToken.isBlank()) {
                    accessToken = newToken;
                    String newRefresh = tokens.path("refresh_token").asText("");
                    if (!newRefresh.isBlank()) refreshToken = newRefresh;
                    LOG.info("alphaXiv: token refreshed");
                    return true;
                }
            }
        } catch (Exception e) {
            LOG.warning("alphaXiv: token refresh failed: " + e.getMessage());
        }
        return false;
    }
}
