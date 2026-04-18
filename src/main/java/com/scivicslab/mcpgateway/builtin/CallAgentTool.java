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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Built-in MCP tool: send a prompt to a named agent and wait for the reply.
 *
 * <p>This is the synchronous counterpart to {@link SubmitToAgentTool}.
 * Internally it calls the target's {@code submitPrompt} tool, polls
 * {@code getPromptStatus} until the result is ready, then fetches it via
 * {@code getPromptResult} — all within a single MCP session.  The caller
 * gets back the agent's reply directly, with no polling required on their
 * side.</p>
 *
 * <p>Use this tool (rather than {@code submit_to_agent}) when you need the
 * reply inline in your conversation.  Use {@code submit_to_agent} only when
 * you want fire-and-forget behaviour or when you need to do other work while
 * the agent is thinking.</p>
 */
@ApplicationScoped
public class CallAgentTool implements BuiltinTool {

    private static final Logger  logger   = Logger.getLogger(CallAgentTool.class.getName());
    private static final Duration TIMEOUT  = Duration.ofMinutes(5);
    private static final String  PROTOCOL = "2024-11-05";
    private static final int     MAX_POLLS = 300;   // 300 × 1 s = 5 min

    @Inject
    ServerRegistry registry;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override public String name() { return "call_agent"; }

    @Override public String description() {
        return "Send a prompt to a named agent and return its reply. "
             + "Blocks until the agent finishes (up to 5 minutes). "
             + "Use list_agents to see available agents. "
             + "Prefer this over submit_to_agent when you need the reply inline.";
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
                  "description": "The prompt to send to the agent"
                },
                "model": {
                  "type": "string",
                  "description": "Model override for the target agent (optional)"
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

            // Open one MCP session and reuse it for all three calls.
            String sessionId = initialize(client, mcpUrl);
            if (sessionId == null) {
                return "Error: failed to establish MCP session with agent '" + agentName + "'";
            }
            sendInitialized(client, mcpUrl, sessionId);

            // Step 1: submitPrompt → UUID
            String uuid = callTool(client, mcpUrl, sessionId, "submitPrompt",
                    mapper.createObjectNode()
                            .put("prompt", prompt)
                            .put("model",  model)
                            .put("_caller", caller));
            if (uuid == null || uuid.startsWith("Error")) {
                return uuid != null ? uuid : "Error: submitPrompt returned no result";
            }
            uuid = uuid.strip();

            // Step 2: poll getPromptStatus until done
            for (int i = 0; i < MAX_POLLS; i++) {
                Thread.sleep(1000);
                String status = callTool(client, mcpUrl, sessionId, "getPromptStatus",
                        mapper.createObjectNode().put("sessionId", uuid));
                if (status != null && (status.contains("status=done") || status.contains("status=completed"))) {
                    break;
                }
                if (i == MAX_POLLS - 1) {
                    return "Error: agent '" + agentName + "' did not respond within 5 minutes";
                }
            }

            // Step 3: getPromptResult
            String result = callTool(client, mcpUrl, sessionId, "getPromptResult",
                    mapper.createObjectNode().put("sessionId", uuid));
            return result != null ? result : "Error: getPromptResult returned no result";

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: interrupted while waiting for agent '" + agentName + "'";
        } catch (Exception e) {
            logger.log(Level.WARNING, "call_agent failed for '" + agentName + "'", e);
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
                        .put("protocolVersion", PROTOCOL)
                        .set("clientInfo", mapper.createObjectNode()
                                .put("name", "mcp-gateway")
                                .put("version", "1.0"))));

        HttpResponse<String> resp = post(client, mcpUrl, body, null);
        if (resp.statusCode() != 200) {
            logger.warning("MCP initialize returned HTTP " + resp.statusCode());
            return null;
        }
        return resp.headers().firstValue("Mcp-Session-Id").orElse(null);
    }

    private void sendInitialized(HttpClient client, String mcpUrl, String sessionId) {
        try {
            post(client, mcpUrl,
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                    sessionId);
        } catch (Exception e) {
            logger.fine("notifications/initialized failed (ignored): " + e.getMessage());
        }
    }

    private String callTool(HttpClient client, String mcpUrl, String sessionId,
                             String toolName, JsonNode args) throws Exception {
        String body = mapper.writeValueAsString(mapper.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", 2)
                .put("method", "tools/call")
                .set("params", mapper.createObjectNode()
                        .put("name", toolName)
                        .set("arguments", args)));

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

            if (root.has("error")) {
                JsonNode err = root.get("error");
                return "Error from agent: " + err.path("message").asText(err.toString());
            }

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

            return result.isMissingNode() ? responseBody : result.toString();

        } catch (Exception e) {
            return responseBody;
        }
    }
}
