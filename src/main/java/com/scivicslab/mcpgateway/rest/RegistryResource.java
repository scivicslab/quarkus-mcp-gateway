package com.scivicslab.mcpgateway.rest;

import com.scivicslab.mcpgateway.registry.ServerEntry;
import com.scivicslab.mcpgateway.registry.ServerRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.Collection;
import java.util.Map;

/**
 * REST API for managing the MCP server registry.
 *
 * POST   /api/servers         - register a server
 * GET    /api/servers         - list all servers
 * GET    /api/servers/{name}  - lookup a server
 * DELETE /api/servers/{name}  - unregister a server
 */
@Path("/api/servers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RegistryResource {

    @Inject
    ServerRegistry registry;

    public record RegisterRequest(String name, String url, String description) {}

    @POST
    public ServerEntry register(RegisterRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new BadRequestException("name is required");
        }
        if (req.url() == null || req.url().isBlank()) {
            throw new BadRequestException("url is required");
        }
        return registry.register(req.name(), req.url(), req.description());
    }

    @GET
    public Collection<ServerEntry> listAll() {
        return registry.listAll();
    }

    @GET
    @Path("/{name}")
    public ServerEntry lookup(@PathParam("name") String name) {
        return registry.lookup(name)
                .orElseThrow(() -> new NotFoundException("Server not found: " + name));
    }

    @DELETE
    @Path("/{name}")
    public Map<String, String> unregister(@PathParam("name") String name) {
        if (registry.unregister(name)) {
            return Map.of("status", "ok", "message", "Unregistered: " + name);
        }
        throw new NotFoundException("Server not found: " + name);
    }
}
