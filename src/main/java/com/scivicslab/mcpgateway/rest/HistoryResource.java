package com.scivicslab.mcpgateway.rest;

import com.scivicslab.mcpgateway.registry.ServerEntry;
import com.scivicslab.mcpgateway.registry.ServerRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Aggregates /api/history from all registered servers.
 * Used by fcitx5-predict-ja daemon to collect conversation text
 * from all LLM Console instances in one call.
 */
@Path("/api/history")
@Produces(MediaType.APPLICATION_JSON)
public class HistoryResource {

    private static final Logger logger = Logger.getLogger(HistoryResource.class.getName());

    @Inject
    ServerRegistry registry;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public record HistoryEntry(String server, String role, String content) {}

    /**
     * GET /api/history?limit=50
     * Queries /api/history on every registered server and returns
     * a merged list tagged with the server name.
     */
    @GET
    public List<HistoryEntry> aggregateHistory(
            @QueryParam("limit") @DefaultValue("50") int limit) {

        List<HistoryEntry> result = new ArrayList<>();

        for (ServerEntry server : registry.listAll()) {
            if (!server.isHealthy()) continue;

            String url = server.getUrl() + "/api/history?limit=" + limit;
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    List<HistoryEntry> entries = parseHistoryJson(
                            server.getName(), response.body());
                    result.addAll(entries);
                } else {
                    logger.fine("History fetch from " + server.getName()
                            + " returned HTTP " + response.statusCode());
                }
            } catch (Exception e) {
                logger.fine("History fetch from " + server.getName()
                        + " failed: " + e.getMessage());
            }
        }

        return result;
    }

    /**
     * Parse [{"role":"user","content":"..."},...]
     * and tag each entry with the server name.
     */
    static List<HistoryEntry> parseHistoryJson(String serverName, String json) {
        List<HistoryEntry> result = new ArrayList<>();
        if (json == null || json.isBlank()) return result;

        int idx = 0;
        while (idx < json.length()) {
            int objStart = json.indexOf('{', idx);
            if (objStart < 0) break;

            // Find matching closing brace (handle nested braces simply by
            // looking for the next '}' — history entries are flat objects)
            int objEnd = json.indexOf('}', objStart);
            if (objEnd < 0) break;

            String obj = json.substring(objStart, objEnd + 1);
            String role = extractField(obj, "role");
            String content = extractField(obj, "content");

            if (role != null && content != null && !content.isBlank()) {
                result.add(new HistoryEntry(serverName, role, content));
            }

            idx = objEnd + 1;
        }

        return result;
    }

    /**
     * Extract a string field value from a simple flat JSON object.
     */
    static String extractField(String json, String field) {
        String key = "\"" + field + "\"";
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) return null;

        int colonIdx = json.indexOf(":", keyIdx + key.length());
        if (colonIdx < 0) return null;

        int startQuote = json.indexOf("\"", colonIdx + 1);
        if (startQuote < 0) return null;

        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = startQuote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                switch (c) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(c);
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return null;
    }
}
