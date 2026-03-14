package com.scivicslab.mcpgateway.rest;

import com.scivicslab.mcpgateway.registry.ServerEntry;
import com.scivicslab.mcpgateway.registry.ServerRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Simple HTML dashboard showing registered MCP servers.
 */
@Path("/")
public class DashboardResource {

    @Inject
    ServerRegistry registry;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public String dashboard() {
        var sb = new StringBuilder();
        sb.append("""
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <title>MCP Gateway</title>
                    <style>
                        body { font-family: system-ui, sans-serif; max-width: 900px; margin: 40px auto; padding: 0 20px;
                               background: #1a1b26; color: #c0caf5; }
                        h1 { color: #7aa2f7; }
                        table { width: 100%%; border-collapse: collapse; margin-top: 20px; }
                        th, td { padding: 10px 14px; text-align: left; border-bottom: 1px solid #3b4261; }
                        th { color: #7aa2f7; }
                        .healthy { color: #9ece6a; }
                        .unhealthy { color: #f7768e; }
                        code { background: #24283b; padding: 2px 6px; border-radius: 4px; }
                        .empty { color: #565f89; font-style: italic; }
                        form { margin-top: 30px; background: #24283b; padding: 20px; border-radius: 8px; }
                        input, button { padding: 8px 12px; margin: 4px; border: 1px solid #3b4261;
                                        border-radius: 4px; background: #1a1b26; color: #c0caf5; }
                        button { background: #7aa2f7; color: #1a1b26; cursor: pointer; border: none; }
                        button:hover { opacity: 0.85; }
                    </style>
                </head>
                <body>
                    <h1>MCP Gateway</h1>
                    <p>Proxy endpoint: <code>POST /mcp/{serverName}</code></p>
                    <h2>Registered Servers</h2>
                """);

        var servers = registry.listAll();
        if (servers.isEmpty()) {
            sb.append("<p class='empty'>No servers registered yet.</p>");
        } else {
            sb.append("<table><tr><th>Name</th><th>URL</th><th>Description</th><th>Proxy Endpoint</th><th>Status</th></tr>");
            for (ServerEntry e : servers) {
                sb.append("<tr>")
                        .append("<td><strong>").append(esc(e.getName())).append("</strong></td>")
                        .append("<td><code>").append(esc(e.getUrl())).append("</code></td>")
                        .append("<td>").append(esc(e.getDescription() != null ? e.getDescription() : "")).append("</td>")
                        .append("<td><code>POST /mcp/").append(esc(e.getName())).append("</code></td>")
                        .append("<td class='").append(e.isHealthy() ? "healthy" : "unhealthy").append("'>")
                        .append(e.isHealthy() ? "healthy" : "down").append("</td>")
                        .append("</tr>");
            }
            sb.append("</table>");
        }

        sb.append("""
                    <form id="regForm" onsubmit="return registerServer()">
                        <h3>Register Server</h3>
                        <input id="sName" placeholder="name (e.g. coder-agent)" required>
                        <input id="sUrl" placeholder="URL (e.g. http://localhost:8090)" required>
                        <input id="sDesc" placeholder="description (optional)">
                        <button type="submit">Register</button>
                    </form>
                    <script>
                    function registerServer() {
                        fetch('/api/servers', {
                            method: 'POST',
                            headers: {'Content-Type': 'application/json'},
                            body: JSON.stringify({
                                name: document.getElementById('sName').value,
                                url: document.getElementById('sUrl').value,
                                description: document.getElementById('sDesc').value
                            })
                        }).then(() => location.reload());
                        return false;
                    }
                    </script>
                </body>
                </html>
                """);

        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
