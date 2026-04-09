package com.scivicslab.mcpgateway.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Request body for {@code POST /api/tools/call}.
 *
 * @param name      tool name (original or qualified with server prefix)
 * @param arguments tool arguments as a JSON object
 */
public record ToolCallRequest(
        String name,
        JsonNode arguments
) {}
