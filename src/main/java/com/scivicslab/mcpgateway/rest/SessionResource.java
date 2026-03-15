package com.scivicslab.mcpgateway.rest;

import com.scivicslab.mcpgateway.proxy.McpProxy;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * HATEOAS endpoint for MCP session metadata.
 * Receivers of _caller URLs can fetch caller info, trace context, etc.
 */
@Path("/api/sessions")
@Produces(MediaType.APPLICATION_JSON)
public class SessionResource {

    @Inject
    McpProxy proxy;

    @GET
    @Path("/{sessionId}")
    public Response getSession(@PathParam("sessionId") String sessionId) {
        Map<String, Object> meta = proxy.getSessionMeta(sessionId);
        if (meta == null) {
            return Response.status(404)
                    .entity(Map.of("error", "Session not found: " + sessionId))
                    .build();
        }
        return Response.ok(meta).build();
    }
}
