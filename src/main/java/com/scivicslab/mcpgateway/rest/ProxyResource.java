package com.scivicslab.mcpgateway.rest;

import com.scivicslab.mcpgateway.proxy.McpProxy;
import com.scivicslab.mcpgateway.proxy.ProxyResult;
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
 * Example:
 *   POST /mcp/coder-agent   -> forwards to http://localhost:8090/mcp
 *   POST /mcp/workflow-editor -> forwards to http://localhost:8081/mcp
 */
@Path("/mcp")
public class ProxyResource {

    @Inject
    McpProxy proxy;

    @POST
    @Path("/{serverName}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response proxyRequest(
            @PathParam("serverName") String serverName,
            @HeaderParam("Mcp-Session-Id") String clientSessionId,
            @Context HttpServerRequest request,
            String body) {

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
