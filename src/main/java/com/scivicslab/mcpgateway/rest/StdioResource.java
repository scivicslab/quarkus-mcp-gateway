package com.scivicslab.mcpgateway.rest;

import com.scivicslab.mcpgateway.stdio.StdioProcess;
import com.scivicslab.mcpgateway.stdio.StdioRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * REST API for stdio-backed MCP server processes.
 *
 * GET /api/stdio/servers  - list all stdio servers with alive status
 */
@Path("/api/stdio/servers")
@Produces(MediaType.APPLICATION_JSON)
public class StdioResource {

    @Inject
    StdioRegistry stdioRegistry;

    public record StdioServerInfo(String name, boolean alive, String endpoint) {}

    @GET
    public List<StdioServerInfo> listAll() {
        Collection<StdioProcess> processes = stdioRegistry.all();
        return processes.stream()
                .map(p -> new StdioServerInfo(p.getName(), p.isAlive(), "/mcp/" + p.getName()))
                .sorted((a, b) -> a.name().compareTo(b.name()))
                .collect(Collectors.toList());
    }
}
