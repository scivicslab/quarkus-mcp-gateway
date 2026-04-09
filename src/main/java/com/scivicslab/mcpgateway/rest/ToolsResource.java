package com.scivicslab.mcpgateway.rest;

import com.scivicslab.mcpgateway.tools.ToolAggregator;
import com.scivicslab.mcpgateway.tools.ToolCallRequest;
import com.scivicslab.mcpgateway.tools.ToolCallResult;
import com.scivicslab.mcpgateway.tools.ToolInfo;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;

/**
 * REST API for aggregated tool operations.
 *
 * GET  /api/tools          - list all tools from all servers
 * POST /api/tools/call     - call a tool by name (routes to correct server)
 * POST /api/tools/refresh  - refresh tool cache from all servers
 */
@Path("/api/tools")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ToolsResource {

    @Inject
    ToolAggregator aggregator;

    @GET
    public List<ToolInfo> listAll() {
        return aggregator.getAllTools();
    }

    @POST
    @Path("/call")
    public ToolCallResult call(ToolCallRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new BadRequestException("name is required");
        }
        return aggregator.callTool(request.name(), request.arguments());
    }

    @POST
    @Path("/refresh")
    public Map<String, String> refresh() {
        aggregator.refreshAll();
        return Map.of("status", "ok", "message", "Tool cache refreshed");
    }
}
