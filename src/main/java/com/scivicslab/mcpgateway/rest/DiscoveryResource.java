package com.scivicslab.mcpgateway.rest;

import com.scivicslab.mcpgateway.registry.ServerEntry;
import com.scivicslab.mcpgateway.registry.ServiceDiscovery;
import com.scivicslab.mcpgateway.registry.ServiceDiscovery.DiscoveredServer;
import com.scivicslab.mcpgateway.registry.ServiceDiscovery.DiscoveryResult;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * REST API for MCP service discovery.
 *
 * POST /api/discover           - scan for MCP servers
 * POST /api/discover/register  - register discovered servers
 */
@Path("/api/discover")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DiscoveryResource {

    @Inject
    ServiceDiscovery discovery;

    /**
     * Scan for MCP servers.
     * If host/ports are provided in the body, use those.
     * Otherwise fall back to discovery.yaml / defaults.
     */
    @POST
    public DiscoveryResult scan(ServiceDiscovery.ScanRequest request) {
        if (request != null && request.host() != null && request.ports() != null) {
            return discovery.scan(request.host(), request.ports());
        }
        return discovery.scan();
    }

    /**
     * Register selected discovered servers.
     * Accepts the list of DiscoveredServer objects from a prior scan.
     */
    @POST
    @Path("/register")
    public List<ServerEntry> registerDiscovered(List<DiscoveredServer> servers) {
        return discovery.registerAll(servers);
    }
}
