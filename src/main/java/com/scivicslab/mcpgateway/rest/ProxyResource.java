package com.scivicslab.mcpgateway.rest;

import com.scivicslab.mcpgateway.proxy.McpProxy;
import com.scivicslab.mcpgateway.proxy.ProxyResult;
import com.scivicslab.mcpgateway.stdio.AggregatingBridge;
import com.scivicslab.mcpgateway.stdio.StdioBridge;
import com.scivicslab.mcpgateway.stdio.StdioRegistry;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

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

    @Inject
    McpProxy proxy;

    @Inject
    StdioRegistry stdioRegistry;

    @Inject
    StdioBridge stdioBridge;

    @Inject
    AggregatingBridge aggregatingBridge;

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
