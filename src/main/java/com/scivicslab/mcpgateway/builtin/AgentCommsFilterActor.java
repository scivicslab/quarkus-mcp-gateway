package com.scivicslab.mcpgateway.builtin;

import com.scivicslab.mcpgateway.registry.ServerEntry;
import com.scivicslab.mcpgateway.registry.ServerRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * ToolFilterActor for agent-communication tools (submitPrompt etc.).
 *
 * <p>Requires an explicit agent name in the query before returning tool schemas.
 * If no registered agent name is found in the query, returns a guidance message
 * listing available agents instead of tool definitions.</p>
 */
@ApplicationScoped
public class AgentCommsFilterActor implements ToolFilterActor {

    private static final Set<String> HANDLED = Set.of(
        "submitPrompt", "cancelRequest", "getPromptResult", "getPromptStatus",
        "getStatus", "callMcpServer", "listModels"
    );

    @Inject ServerRegistry registry;

    @Override
    public Set<String> handledToolNames() {
        return HANDLED;
    }

    @Override
    public ToolFilterResult filter(String query, List<AllToolsCache.Entry> candidates) {
        Collection<ServerEntry> agents = registry.listAll();
        String agentName = extractAgentName(query, agents);

        if (agentName == null) {
            return ToolFilterResult.guidance(buildGuidance(agents));
        }

        List<AllToolsCache.Entry> filtered = candidates.stream()
                .filter(e -> agentName.equals(e.serverName()))
                .toList();
        return ToolFilterResult.of(filtered);
    }

    private String extractAgentName(String query, Collection<ServerEntry> agents) {
        String lower = query.toLowerCase();
        for (ServerEntry agent : agents) {
            if (lower.contains(agent.getName().toLowerCase())) {
                return agent.getName();
            }
        }
        return null;
    }

    private String buildGuidance(Collection<ServerEntry> agents) {
        var sb = new StringBuilder(
            "To use agent-communication tools, specify an agent name in your query. ");
        sb.append("Available agents: ");
        List<String> names = agents.stream()
                .filter(ServerEntry::isHealthy)
                .map(ServerEntry::getName)
                .toList();
        if (names.isEmpty()) {
            sb.append("(none currently healthy)");
        } else {
            sb.append(String.join(", ", names));
            sb.append(". Example: find_tools(query=\"send to ").append(names.get(0)).append("\")");
        }
        return sb.toString();
    }
}
