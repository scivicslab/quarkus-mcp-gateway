package com.scivicslab.mcpgateway.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Built-in MCP tool that searches available tools by natural language query.
 *
 * <p>Enables a two-phase tool-calling pattern: the agent loop initially presents
 * only this tool to the LLM. The LLM calls find_tools with a description of its
 * intent, receives matching tool definitions, and the agent loop then makes those
 * tools available for subsequent iterations.</p>
 *
 * <p>Search uses simple keyword matching against tool name and description.
 * Returns up to 8 matching tools as a JSON array that the agent can parse.</p>
 */
@ApplicationScoped
public class FindToolsTool implements BuiltinTool {

    private static final int MAX_RESULTS = 8;

    @Inject
    AllToolsCache cache;

    @Override
    public String name() {
        return "find_tools";
    }

    @Override
    public String description() {
        return "Search for available tools by describing what you want to do. " +
               "Returns matching tool definitions (name, description, inputSchema). " +
               "Call this first to discover which tools are available for your task.";
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{" +
               "\"query\":{\"type\":\"string\"," +
               "\"description\":\"Natural language description of what you want to accomplish\"}" +
               "},\"required\":[\"query\"]}";
    }

    @Override
    public String call(JsonNode arguments) {
        String query = arguments.path("query").asText("").toLowerCase();
        String[] terms = query.split("\\s+");

        List<AllToolsCache.Entry> matches = cache.getAll().stream()
                .filter(e -> matchesAny(e, terms))
                .limit(MAX_RESULTS)
                .collect(Collectors.toList());

        if (matches.isEmpty()) {
            // Fallback: return first MAX_RESULTS tools regardless
            matches = cache.getAll().stream().limit(MAX_RESULTS).collect(Collectors.toList());
        }

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < matches.size(); i++) {
            if (i > 0) sb.append(",");
            AllToolsCache.Entry e = matches.get(i);
            sb.append("{\"name\":\"").append(escapeJson(e.name())).append("\",");
            sb.append("\"description\":\"").append(escapeJson(e.description())).append("\",");
            sb.append("\"inputSchema\":").append(e.inputSchema()).append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private boolean matchesAny(AllToolsCache.Entry e, String[] terms) {
        String haystack = (e.name() + " " + e.description()).toLowerCase();
        for (String term : terms) {
            if (!term.isBlank() && haystack.contains(term)) return true;
        }
        return false;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
