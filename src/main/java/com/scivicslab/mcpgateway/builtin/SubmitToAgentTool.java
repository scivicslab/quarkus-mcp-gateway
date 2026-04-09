package com.scivicslab.mcpgateway.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.mcpgateway.registry.ServerRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Built-in MCP tool: submit a prompt to a named agent via the gateway.
 *
 * <p>Looks up the target agent by name in the {@link ServerRegistry},
 * opens a fresh MCP session to its {@code /mcp} endpoint, and calls
 * the {@code submitPrompt} tool that quarkus-chat-ui exposes.  The
 * caller's identifier is forwarded in the {@code _caller} argument so
 * the target can display or reply to the correct sender.</p>
 *
 * <p>This lets two Claude instances running in different chat-UI windows
 * exchange messages by name, without either side hard-coding ports.</p>
 */
@ApplicationScoped
public class SubmitToAgentTool implements BuiltinTool {

    private static final Logger logger = Logger.getLogger(SubmitToAgentTool.class.getName());
    private static final Duration TIMEOUT = Duration.ofMinutes(2);
    private static final String PROTOCOL_VERSION = "2024-11-05";

    @Inject
    ServerRegistry registry;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override public String name() { return "submit_to_agent"; }
    @Override public String description() {
        return "Submit a prompt to a named agent registered with this gateway. "
             + "The agent must expose the submitPrompt MCP tool (quarkus-chat-ui does). "
             + "Use list_agents to see available agents.";
    }
    @Override public String inputSchema() { return """
            {
              "type": "object",
              "properties": {
                "agent": {
                  "type": "string",
                  "description": "Name of the target agent (from list_agents)"
                },
                "prompt": {
                  "type": "string",
                  "description": "The prompt text to send to the agent"
                },
                "model": {
                  "type": "string",
                  "description": "Model override for the target agent (optional, leave empty for its current model)"
                },
                "caller": {
                  "type": "string",
                  "description": "Your identifier (name or URL) so the target knows who sent this"
                }
              },
              "required": ["agent", "prompt"]
            }
            """; }

    @Override
    public String call(JsonNode arguments) {
        String agentName = arguments.path("agent").asText("").strip();
        String prompt    = arguments.path("prompt").asText("").strip();
        String model     = arguments.path("model").asText("").strip();
        String caller    = arguments.path("caller").asText("mcp-gateway").strip();

        if (agentName.isBlank()) return "Error: 'agent' is required";
        if (prompt.isBlank())    return "Error: 'prompt' is required";

        var entry = registry.lookup(agentName);
        if (entry.isEmpty()) {
            return "Error: unknown agent '" + agentName + "'. Use list_agents to see registered agents.";
        }

        String mcpUrl = entry.get().getUrl() + "/mcp";

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            // Step 1: initialize — get an Mcp-Session-Id
            String sessionId = initialize(client, mcpUrl);
            if (sessionId == null) {
                return "Error: failed to establish MCP session with agent '" + agentName + "'";
            }

            // Step 2: notifications/initialized (fire-and-forget, ignore errors)
            sendInitialized(client, mcpUrl, sessionId);

            // Step 3: tools/call submitPrompt
            return callSubmitPrompt(client, mcpUrl, sessionId, prompt, model, caller);

        } catch (Exception e) {
            logger.log(Level.WARNING, "submit_to_agent failed for '" + agentName + "'", e);
            return "Error: " + e.getMessage();
        }
    }

    // -------------------------------------------------------------------------

    private String initialize(HttpClient client, String mcpUrl) throws Exception {
        String body = mapper.writeValueAsString(mapper.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "initialize")
                .set("params", mapper.createObjectNode()
                        .put("protocolVersion", PROTOCOL_VERSION)
                        .set("clientInfo", mapper.createObjectNode()
                                .put("name", "mcp-gateway")
                                .put("version", "1.0"))
                ));

        HttpResponse<String> resp = post(client, mcpUrl, body, null);
        if (resp.statusCode() != 200) {
            logger.warning("MCP initialize returned HTTP " + resp.statusCode());
            return null;
        }
        return resp.headers().firstValue("Mcp-Session-Id").orElse(null);
    }

    private void sendInitialized(HttpClient client, String mcpUrl, String sessionId) {
        try {
            String body = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
            post(client, mcpUrl, body, sessionId);
        } catch (Exception e) {
            logger.fine("notifications/initialized failed (ignored): " + e.getMessage());
        }
    }

    private String callSubmitPrompt(HttpClient client, String mcpUrl, String sessionId,
                                    String prompt, String model, String caller) throws Exception {
        var args = mapper.createObjectNode()
                .put("prompt", prompt)
                .put("model", model)
                .put("_caller", caller);

        String body = mapper.writeValueAsString(mapper.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", 2)
                .put("method", "tools/call")
                .set("params", mapper.createObjectNode()
                        .put("name", "submitPrompt")
                        .set("arguments", args)
                ));

        HttpResponse<String> resp = post(client, mcpUrl, body, sessionId);
        return parseToolResult(resp.body(), resp.statusCode());
    }

    private HttpResponse<String> post(HttpClient client, String url, String body, String sessionId)
            throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String parseToolResult(String responseBody, int statusCode) {
        if (statusCode != 200) {
            return "Error: target returned HTTP " + statusCode + ": " + responseBody;
        }
        try {
            // Handle SSE stream: look for data: {...} line
            String json = responseBody;
            if (responseBody.startsWith("data:")) {
                for (String line : responseBody.split("\n")) {
                    if (line.startsWith("data:")) {
                        json = line.substring(5).strip();
                        break;
                    }
                }
            }

            JsonNode root = mapper.readTree(json);

            // JSON-RPC error
            if (root.has("error")) {
                JsonNode err = root.get("error");
                return "Error from agent: " + err.path("message").asText(err.toString());
            }

            // JSON-RPC result → MCP content array
            JsonNode result = root.path("result");
            if (result.has("content")) {
                var sb = new StringBuilder();
                for (JsonNode item : result.get("content")) {
                    if ("text".equals(item.path("type").asText())) {
                        sb.append(item.path("text").asText());
                    }
                }
                return sb.length() > 0 ? sb.toString() : result.toString();
            }

            // Fallback: return raw result
            return result.isMissingNode() ? responseBody : result.toString();

        } catch (Exception e) {
            return responseBody; // return raw if unparseable
        }
    }
}
