package com.scivicslab.mcpgateway.tools;

import java.util.List;

/**
 * Response for {@code POST /api/tools/call}, matching MCP's result structure.
 *
 * @param content list of content items (typically text)
 * @param isError whether the tool execution resulted in an error
 */
public record ToolCallResult(
        List<ContentItem> content,
        boolean isError
) {
    public record ContentItem(String type, String text) {}

    public static ToolCallResult success(String text) {
        return new ToolCallResult(List.of(new ContentItem("text", text)), false);
    }

    public static ToolCallResult error(String message) {
        return new ToolCallResult(List.of(new ContentItem("text", message)), true);
    }
}
