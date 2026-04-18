package com.scivicslab.mcpgateway.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scivicslab.mcpgateway.proxy.McpProxy;
import com.scivicslab.mcpgateway.proxy.ProxyResult;
import com.scivicslab.mcpgateway.stdio.AggregatingBridge;
import com.scivicslab.mcpgateway.stdio.StdioBridge;
import com.scivicslab.mcpgateway.stdio.StdioRegistry;
import com.scivicslab.mcpgateway.tools.ToolAggregator;
import com.scivicslab.mcpgateway.tools.ToolCallResult;
import com.scivicslab.mcpgateway.tools.ToolInfo;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * MCP proxy endpoint.
 *
 * Clients send MCP JSON-RPC to:  POST /mcp/{serverName}
 * The gateway forwards it to the registered backend server.
 *
 * Special virtual server name "_all":
 *   POST /mcp/_all  -> aggregates tools from all registered stdio servers
 *
 * HTTP-backed servers:
 *   POST /mcp/chat-ui   -> forwards to http://localhost:8090/mcp
 *
 * stdio-backed servers (launched from servers.yaml "stdio" section):
 *   POST /mcp/filesystem -> routed through StdioBridge to a subprocess
 */
@Path("/mcp")
public class ProxyResource {

    private static final Logger logger = Logger.getLogger(ProxyResource.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();

    // Fixed session ID for the aggregated gateway endpoint — stateless, so one ID is fine
    private static final String GATEWAY_SESSION_ID = "mcp-gateway-" + UUID.randomUUID().toString().substring(0, 8);

    @Inject
    McpProxy proxy;

    @Inject
    StdioRegistry stdioRegistry;

    @Inject
    StdioBridge stdioBridge;

    @Inject
    AggregatingBridge aggregatingBridge;

    @Inject
    ToolAggregator toolAggregator;

    /**
     * Aggregated MCP endpoint — no server name required.
     * Handles initialize / tools/list / tools/call for ALL registered servers.
     * This is the endpoint AgentLoopActor uses when mcp-urls=http://localhost:28081.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response aggregatedMcp(String body) {
        try {
            JsonNode root = mapper.readTree(body);
            String method = root.has("method") ? root.get("method").asText() : "";
            JsonNode idNode = root.get("id");

            switch (method) {
                case "initialize" -> {
                    ObjectNode result = mapper.createObjectNode();
                    result.put("protocolVersion", "2024-11-05");
                    result.set("capabilities", mapper.createObjectNode().set("tools", mapper.createObjectNode()));
                    ObjectNode serverInfo = mapper.createObjectNode();
                    serverInfo.put("name", "mcp-gateway");
                    serverInfo.put("version", "1.1.0");
                    result.set("serverInfo", serverInfo);
                    return Response.ok(buildResult(idNode, result))
                            .header("Mcp-Session-Id", GATEWAY_SESSION_ID)
                            .build();
                }
                case "notifications/initialized" -> {
                    return Response.accepted().build();
                }
                case "tools/list" -> {
                    List<ToolInfo> tools = toolAggregator.getAllTools();
                    ArrayNode toolsArray = mapper.createArrayNode();
                    for (ToolInfo t : tools) {
                        ObjectNode toolNode = mapper.createObjectNode();
                        toolNode.put("name", t.qualifiedName());
                        toolNode.put("description", "[" + t.server() + "] " + t.description());
                        toolNode.set("inputSchema", t.inputSchema());
                        toolsArray.add(toolNode);
                    }
                    ObjectNode result = mapper.createObjectNode();
                    result.set("tools", toolsArray);
                    return Response.ok(buildResult(idNode, result))
                            .header("Mcp-Session-Id", GATEWAY_SESSION_ID)
                            .build();
                }
                case "tools/call" -> {
                    JsonNode params = root.get("params");
                    String toolName = params != null && params.has("name") ? params.get("name").asText() : "";
                    JsonNode arguments = params != null ? params.get("arguments") : null;
                    ToolCallResult callResult = toolAggregator.callTool(toolName, arguments);
                    ObjectNode result = mapper.createObjectNode();
                    ArrayNode content = mapper.createArrayNode();
                    for (ToolCallResult.ContentItem item : callResult.content()) {
                        ObjectNode itemNode = mapper.createObjectNode();
                        itemNode.put("type", item.type());
                        itemNode.put("text", item.text());
                        content.add(itemNode);
                    }
                    result.set("content", content);
                    result.put("isError", callResult.isError());
                    return Response.ok(buildResult(idNode, result))
                            .header("Mcp-Session-Id", GATEWAY_SESSION_ID)
                            .build();
                }
                default -> {
                    logger.warning("Unknown MCP method on aggregated endpoint: " + method);
                    return Response.ok(buildError(idNode, -32601, "Method not found: " + method)).build();
                }
            }
        } catch (Exception e) {
            logger.warning("Aggregated MCP endpoint error: " + e.getMessage());
            return Response.serverError().entity(buildError(null, -32603, e.getMessage())).build();
        }
    }

    private static String buildResult(JsonNode id, ObjectNode result) {
        try {
            ObjectNode resp = mapper.createObjectNode();
            resp.put("jsonrpc", "2.0");
            if (id != null) resp.set("id", id);
            resp.set("result", result);
            return mapper.writeValueAsString(resp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String buildError(JsonNode id, int code, String message) {
        try {
            ObjectNode resp = mapper.createObjectNode();
            resp.put("jsonrpc", "2.0");
            if (id != null) resp.set("id", id);
            ObjectNode error = mapper.createObjectNode();
            error.put("code", code);
            error.put("message", message != null ? message : "Internal error");
            resp.set("error", error);
            return mapper.writeValueAsString(resp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @POST
    @Path("/{serverName}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response proxyRequest(
            @PathParam("serverName") String serverName,
            @HeaderParam("Mcp-Session-Id") String clientSessionId,
            @Context HttpServerRequest request,
            String body) {

        // Virtual aggregated server — merges all stdio backends into one
        if ("_all".equals(serverName)) {
            StdioBridge.StdioResult result = aggregatingBridge.handle(body);
            if (result.body() == null) return Response.accepted().build();
            Response.ResponseBuilder rb = Response.ok(result.body()).type(MediaType.APPLICATION_JSON);
            if (result.sessionId() != null) rb.header("Mcp-Session-Id", result.sessionId());
            return rb.build();
        }

        // Route to stdio bridge if this is a stdio-backed server
        if (stdioRegistry.contains(serverName)) {
            StdioBridge.StdioResult result = stdioBridge.handle(serverName, body);
            if (result.body() == null) {
                // Notification acknowledged — no response body
                return Response.accepted().build();
            }
            Response.ResponseBuilder rb = Response.ok(result.body())
                    .type(MediaType.APPLICATION_JSON);
            // Return session ID so MCP clients (e.g. agent loop) can establish sessions.
            // stdio processes are stateless across requests — the session ID is just the
            // server name, stable for the lifetime of the gateway.
            if (result.sessionId() != null) {
                rb.header("Mcp-Session-Id", result.sessionId());
            }
            return rb.build();
        }

        // Forward to HTTP-backed server
        String remoteAddress = request.remoteAddress().host() + ":" + request.remoteAddress().port();
        ProxyResult result = proxy.forward(serverName, body, clientSessionId, remoteAddress);

        Response.ResponseBuilder rb = Response.status(result.statusCode())
                .entity(result.body())
                .type(result.contentType());

        if (result.backendSessionId() != null) {
            rb.header("Mcp-Session-Id", result.backendSessionId());
        }

        return rb.build();
    }
}
