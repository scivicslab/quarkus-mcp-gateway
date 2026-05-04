package com.scivicslab.mcpgateway.registry;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * In-memory registry of MCP servers.
 * Servers register themselves by name and URL.
 * Clients look up servers by name.
 */
@ApplicationScoped
public class ServerRegistry {

    private static final Logger logger = Logger.getLogger(ServerRegistry.class.getName());

    private final Map<String, ServerEntry> servers = new ConcurrentHashMap<>();

    @Inject
    Event<ServerRegistryEvent> registryEvent;

    /**
     * Register or update an MCP server.
     * If another entry already exists with the same URL, skip and return the existing entry.
     */
    public ServerEntry register(String name, String url, String description) {
        String normalizedUrl = normalizeUrl(url);
        for (ServerEntry existing : servers.values()) {
            if (existing.getUrl().equals(normalizedUrl) && !existing.getName().equals(name)) {
                logger.info("Skipping duplicate URL registration: " + name + " -> " + url
                        + " (already registered as " + existing.getName() + ")");
                return existing;
            }
        }
        var entry = new ServerEntry(name, normalizedUrl, description);
        servers.put(name, entry);
        logger.info("Registered MCP server: " + name + " -> " + url);
        registryEvent.fire(new ServerRegistryEvent(name, "registered"));
        return entry;
    }

    /**
     * Unregister an MCP server.
     */
    public boolean unregister(String name) {
        var removed = servers.remove(name);
        if (removed != null) {
            logger.info("Unregistered MCP server: " + name);
            registryEvent.fire(new ServerRegistryEvent(name, "unregistered"));
        }
        return removed != null;
    }

    /**
     * Look up a server by name.
     */
    public Optional<ServerEntry> lookup(String name) {
        return Optional.ofNullable(servers.get(name));
    }

    /**
     * List all registered servers.
     */
    public Collection<ServerEntry> listAll() {
        return servers.values();
    }

    private String normalizeUrl(String url) {
        // Remove trailing slash
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
