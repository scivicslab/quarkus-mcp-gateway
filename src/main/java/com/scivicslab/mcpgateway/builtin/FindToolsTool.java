package com.scivicslab.mcpgateway.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in MCP tool that searches available tools by natural language query.
 *
 * <p>The gateway exposes only this tool in tools/list. The LLM calls find_tools
 * with a natural language description of its intent; AllToolsCache returns matching
 * tool definitions scored by Lucene BM25. The agent loop adds those definitions to
 * the current turn's tool list so the LLM can call them immediately.</p>
 *
 * <p>After the cache search, each result entry is routed to the ToolFilterActor that
 * claims its tool name. Entries with no registered actor pass through unchanged.
 * If an actor returns a guidance message, that message is returned instead of a tool list.</p>
 */
@ApplicationScoped
public class FindToolsTool implements BuiltinTool {

    private static final int MAX_RESULTS = 8;

    @Inject AllToolsCache cache;
    @Inject Instance<ToolFilterActor> filterActors;

    /** Maps tool name → the ToolFilterActor responsible for it. Built at startup. */
    private Map<String, ToolFilterActor> toolActorMap;

    @PostConstruct
    void init() {
        toolActorMap = new HashMap<>();
        for (ToolFilterActor actor : filterActors) {
            for (String toolName : actor.handledToolNames()) {
                if (toolActorMap.containsKey(toolName)) {
                    throw new IllegalStateException(
                        "Tool name conflict in ToolFilterActor registrations: " + toolName);
                }
                toolActorMap.put(toolName, actor);
            }
        }
    }

    @Override
    public String name() {
        return "find_tools";
    }

    @Override
    public String description() {
        return "Search for available tools by describing what you want to do. " +
               "Returns matching tool definitions (name, description, inputSchema). " +
               "Call this first to discover which tools are available for your task. " +
               "To send a prompt to a specific agent, include the agent name in the query " +
               "(e.g. \"send to chat-ui-28011\"). Without an agent name, available agents are listed.";
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{" +
               "\"query\":{\"type\":\"string\"," +
               "\"description\":\"Natural language description of what you want to accomplish. " +
               "Include agent name to get agent-communication tools for that specific agent.\"}" +
               "},\"required\":[\"query\"]}";
    }

    @Override
    public String call(JsonNode arguments) {
        String query = arguments.path("query").asText("").trim();
        List<AllToolsCache.Entry> matches = cache.search(query, MAX_RESULTS);

        // Partition matches: entries handled by an actor vs. passthrough entries
        Map<ToolFilterActor, List<AllToolsCache.Entry>> actorEntries = new LinkedHashMap<>();
        List<AllToolsCache.Entry> passthrough = new ArrayList<>();

        for (AllToolsCache.Entry entry : matches) {
            ToolFilterActor actor = toolActorMap.get(entry.name());
            if (actor == null) {
                passthrough.add(entry);
            } else {
                actorEntries.computeIfAbsent(actor, k -> new ArrayList<>()).add(entry);
            }
        }

        // Dispatch to each actor; return guidance immediately if any actor requests it
        List<AllToolsCache.Entry> included = new ArrayList<>(passthrough);
        for (var entry : actorEntries.entrySet()) {
            ToolFilterResult result = entry.getKey().filter(query, entry.getValue());
            if (result.guidance() != null) {
                return result.guidance();
            }
            included.addAll(result.included());
        }

        return buildToolsJson(included);
    }

    private String buildToolsJson(List<AllToolsCache.Entry> entries) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(",");
            AllToolsCache.Entry e = entries.get(i);
            sb.append("{\"name\":\"").append(escapeJson(e.name())).append("\",");
            sb.append("\"description\":\"").append(escapeJson(e.description())).append("\",");
            sb.append("\"inputSchema\":").append(e.inputSchema()).append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
