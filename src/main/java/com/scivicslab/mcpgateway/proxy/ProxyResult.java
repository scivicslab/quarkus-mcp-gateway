package com.scivicslab.mcpgateway.proxy;

/**
 * Result of proxying an MCP request to a backend server.
 */
public record ProxyResult(
        int statusCode,
        String body,
        String contentType,
        String backendSessionId
) {
    public static ProxyResult error(int statusCode, String message) {
        String errorJson = """
                {"jsonrpc":"2.0","id":null,"error":{"code":%d,"message":"%s"}}
                """.formatted(statusCode == 404 ? -32001 : -32000,
                message.replace("\"", "'"));
        return new ProxyResult(statusCode, errorJson, "application/json", null);
    }
}
