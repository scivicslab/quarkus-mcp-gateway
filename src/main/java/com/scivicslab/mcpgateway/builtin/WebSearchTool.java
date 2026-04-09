package com.scivicslab.mcpgateway.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * Built-in MCP tool: search the web via DuckDuckGo (no API key required).
 *
 * <p>Scrapes DuckDuckGo's HTML endpoint and returns result titles, URLs, and snippets.</p>
 */
@ApplicationScoped
public class WebSearchTool implements BuiltinTool {

    public static final String NAME = "web_search";
    public static final String DESCRIPTION =
            "Search the web using DuckDuckGo. Returns a list of results with titles, URLs, and snippets. "
            + "Use this to find information on the internet when you don't have a specific URL to fetch.";
    public static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "Search query"
                },
                "max_results": {
                  "type": "integer",
                  "description": "Maximum number of results to return (default 10)"
                }
              },
              "required": ["query"]
            }
            """;

    private static final Logger logger = Logger.getLogger(WebSearchTool.class.getName());
    private static final int DEFAULT_MAX_RESULTS = 10;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override public String name()        { return NAME; }
    @Override public String description() { return DESCRIPTION; }
    @Override public String inputSchema() { return INPUT_SCHEMA; }

    @Override
    public String call(JsonNode arguments) {
        String query = arguments.path("query").asText("").strip();
        if (query.isBlank()) return "Error: 'query' argument is required";

        int maxResults = arguments.has("max_results")
                ? arguments.get("max_results").asInt(DEFAULT_MAX_RESULTS)
                : DEFAULT_MAX_RESULTS;

        try {
            logger.info("Web search: " + query);
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://html.duckduckgo.com/html/?q=" + encoded;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("User-Agent", "Mozilla/5.0 (compatible; mcp-gateway/1.0)")
                    .header("Accept-Language", "ja,en;q=0.9")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                return "Search failed: HTTP " + response.statusCode();
            }

            return parseResults(response.body(), maxResults);

        } catch (Exception e) {
            logger.warning("web_search failed for '" + query + "': " + e.getMessage());
            return "Error searching for '" + query + "': " + e.getMessage();
        }
    }

    private static String parseResults(String html, int maxResults) {
        Document doc = Jsoup.parse(html);
        Elements results = doc.select(".result");

        if (results.isEmpty()) {
            // Fallback: try alternate selectors used by DuckDuckGo
            results = doc.select(".web-result");
        }

        if (results.isEmpty()) return "No results found.";

        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Element result : results) {
            if (count >= maxResults) break;

            String title = text(result, ".result__title, .result__a");
            String href  = attr(result, ".result__url, .result__a", "href");
            String snippet = text(result, ".result__snippet");

            if (title.isBlank() && href.isBlank()) continue;

            sb.append(count + 1).append(". ").append(title).append("\n");
            if (!href.isBlank())    sb.append("   URL: ").append(href).append("\n");
            if (!snippet.isBlank()) sb.append("   ").append(snippet).append("\n");
            sb.append("\n");
            count++;
        }

        return count == 0 ? "No results found." : sb.toString().stripTrailing();
    }

    private static String text(Element parent, String selector) {
        Element el = parent.selectFirst(selector);
        return el != null ? el.text().strip() : "";
    }

    private static String attr(Element parent, String selector, String attr) {
        Element el = parent.selectFirst(selector);
        if (el == null) return "";
        String val = el.attr("href");
        // DuckDuckGo wraps URLs in redirect links; extract uddg param if present
        if (val.contains("uddg=")) {
            int start = val.indexOf("uddg=") + 5;
            int end = val.indexOf('&', start);
            String encoded = end < 0 ? val.substring(start) : val.substring(start, end);
            try {
                return java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
        }
        return val.startsWith("http") ? val : "";
    }
}
