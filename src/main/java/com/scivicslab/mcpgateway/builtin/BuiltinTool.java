package com.scivicslab.mcpgateway.builtin;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Extension point for built-in MCP tools provided directly by the gateway JVM.
 *
 * <p>Implement this interface and annotate with {@code @ApplicationScoped} to register
 * a new tool. It will automatically appear in {@code tools/list} at {@code /mcp/_all}
 * without any changes to {@code AggregatingBridge}.</p>
 *
 * <pre>
 * {@literal @}ApplicationScoped
 * public class MyTool implements BuiltinTool {
 *     public String name()        { return "my_tool"; }
 *     public String description() { return "Does something useful"; }
 *     public String inputSchema() { return "{\"type\":\"object\",\"properties\":{...}}"; }
 *     public String call(JsonNode arguments) { ... }
 * }
 * </pre>
 */
public interface BuiltinTool {
    /** MCP tool name (must be unique across all tools). */
    String name();

    /** Human-readable description shown to the LLM. */
    String description();

    /** JSON Schema string for the tool's input parameters. */
    String inputSchema();

    /** Execute the tool and return the result as plain text. */
    String call(JsonNode arguments);
}
