package com.scivicslab.mcpgateway.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A tool definition aggregated from an MCP server.
 *
 * @param name          original tool name from the MCP server
 * @param qualifiedName server-prefixed name (serverName__toolName)
 * @param description   human-readable description
 * @param inputSchema   JSON Schema for the tool's parameters
 * @param server        name of the server that provides this tool
 */
public record ToolInfo(
        String name,
        String qualifiedName,
        String description,
        JsonNode inputSchema,
        String server
) {}
