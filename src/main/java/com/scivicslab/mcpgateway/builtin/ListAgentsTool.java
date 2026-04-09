package com.scivicslab.mcpgateway.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.scivicslab.mcpgateway.registry.ServerEntry;
import com.scivicslab.mcpgateway.registry.ServerRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Built-in MCP tool: list all registered agents in the gateway.
 *
 * <p>Returns the name, URL, description, and health status of every
 * MCP server that has registered with this gateway.  Chat-UI instances
 * register automatically on startup (via AutoRegistrar / servers.yaml),
 * so this tool gives one Claude instance a live view of what other
 * agents are reachable by name.</p>
 */
@ApplicationScoped
public class ListAgentsTool implements BuiltinTool {

    @Inject
    ServerRegistry registry;

    @Override public String name() { return "list_agents"; }
    @Override public String description() {
        return "List all MCP agents registered with this gateway. "
             + "Returns name, URL, description, and health status for each registered agent. "
             + "Use the name with submit_to_agent to send a prompt to a specific agent.";
    }
    @Override public String inputSchema() { return """
            {
              "type": "object",
              "properties": {},
              "required": []
            }
            """; }

    @Override
    public String call(JsonNode arguments) {
        var agents = registry.listAll();
        if (agents.isEmpty()) {
            return "No agents are currently registered with this gateway.";
        }

        var sb = new StringBuilder();
        sb.append("Registered agents (").append(agents.size()).append("):\n\n");
        for (ServerEntry entry : agents) {
            sb.append("name:    ").append(entry.getName()).append("\n");
            sb.append("url:     ").append(entry.getUrl()).append("\n");
            if (entry.getDescription() != null && !entry.getDescription().isBlank()) {
                sb.append("desc:    ").append(entry.getDescription()).append("\n");
            }
            sb.append("healthy: ").append(entry.isHealthy()).append("\n");
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }
}
