package com.scivicslab.mcpgateway.builtin;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * Shared cache of tool definitions populated by AggregatingBridge on each tools/list call.
 * FindToolsTool reads from this cache to search across all available tools.
 */
@ApplicationScoped
public class AllToolsCache {

    public record Entry(String name, String description, String inputSchema) {}

    private volatile List<Entry> tools = List.of();

    public void update(List<Entry> updated) {
        this.tools = List.copyOf(updated);
    }

    public List<Entry> getAll() {
        return tools;
    }
}
