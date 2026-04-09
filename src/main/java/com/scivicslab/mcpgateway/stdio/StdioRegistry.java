package com.scivicslab.mcpgateway.stdio;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of stdio-backed MCP server processes.
 */
@ApplicationScoped
public class StdioRegistry {

    private final Map<String, StdioProcess> processes = new ConcurrentHashMap<>();

    public void register(StdioProcess process) {
        processes.put(process.getName(), process);
    }

    public Optional<StdioProcess> lookup(String name) {
        return Optional.ofNullable(processes.get(name));
    }

    public boolean contains(String name) {
        return "_all".equals(name) || processes.containsKey(name);
    }

    public Collection<StdioProcess> all() {
        return processes.values();
    }

    public void remove(String name) {
        processes.remove(name);
    }
}
