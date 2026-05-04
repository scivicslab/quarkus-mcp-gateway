package com.scivicslab.mcpgateway.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Logger;

@ApplicationScoped
public class WebSearchTool implements BuiltinTool {

    private static final Logger logger = Logger.getLogger(WebSearchTool.class.getName());
    private static final int DEFAULT_MAX_RESULTS = 10;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return "Search the web using DuckDuckGo. Returns titles, URLs, and snippets. No API key required.";
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{" +
               "\"query\":{\"type\":\"string\",\"description\":\"Search query\"}," +
               "\"max_results\":{\"type\":\"integer\",\"description\":\"Maximum number of results (default 10)\"}" +
               "},\"required\":[\"query\"]}";
    }

    @Override
    public String call(JsonNode arguments) {
        String query = arguments.path("query").asText("").trim();
        if (query.isBlank()) return "Error: 'query' argument is required";

        int limit = arguments.hasNonNull("max_results")
                ? arguments.path("max_results").asInt(DEFAULT_MAX_RESULTS)
                : DEFAULT_MAX_RESULTS;

        try {
            logger.info("Web search: " + query);
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://html.duckduckgo.com/html/?q=" + encoded))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0 (compatible; mcp-gateway/1.0)")
                    .header("Accept-Language", "ja,en;q=0.9")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) return "Search failed: HTTP " + response.statusCode();

            return parseResults(response.body(), limit);
        } catch (Exception e) {
            logger.warning("web_search failed for '" + query + "': " + e.getMessage());
            return "Error searching for '" + query + "': " + e.getMessage();
        }
    }

    private static String parseResults(String html, int maxResults) {
        Document doc = Jsoup.parse(html);
        Elements results = doc.select(".result");
        if (results.isEmpty()) results = doc.select(".web-result");
        if (results.isEmpty()) return "No results found.";

        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Element result : results) {
            if (count >= maxResults) break;
            String title = text(result, ".result__title, .result__a");
            String href = extractUrl(result, ".result__url, .result__a");
            String snippet = text(result, ".result__snippet");
            if (title.isBlank() && href.isBlank()) continue;
            sb.append(count + 1).append(". ").append(title).append("\n");
            if (!href.isBlank()) sb.append("   URL: ").append(href).append("\n");
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

    private static String extractUrl(Element parent, String selector) {
        Element el = parent.selectFirst(selector);
        if (el == null) return "";
        String val = el.attr("href");
        if (val.contains("uddg=")) {
            int start = val.indexOf("uddg=") + 5;
            int end = val.indexOf('&', start);
            String enc = end < 0 ? val.substring(start) : val.substring(start, end);
            try { return URLDecoder.decode(enc, StandardCharsets.UTF_8); } catch (Exception ignored) {}
        }
        return val.startsWith("http") ? val : "";
    }
}
