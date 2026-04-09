package com.scivicslab.mcpgateway.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * Built-in MCP tool: fetch a URL and return its content as plain text.
 *
 * <p>Registered automatically via CDI — no changes to AggregatingBridge needed.</p>
 */
@ApplicationScoped
public class FetchTool implements BuiltinTool {

    public static final String NAME = "fetch";
    public static final String DESCRIPTION =
            "Fetch the content of a URL and return it as readable text. "
            + "HTML pages are converted to plain text with their main content extracted. "
            + "Use this to read web pages, documentation, or any publicly accessible URL.";
    public static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "url": {
                  "type": "string",
                  "description": "URL to fetch"
                },
                "max_length": {
                  "type": "integer",
                  "description": "Maximum characters to return (default 5000)"
                },
                "raw": {
                  "type": "boolean",
                  "description": "Return raw HTML instead of extracted text (default false)"
                }
              },
              "required": ["url"]
            }
            """;

    private static final Logger logger = Logger.getLogger(FetchTool.class.getName());
    private static final int DEFAULT_MAX_LENGTH = 5000;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override public String name()        { return NAME; }
    @Override public String description() { return DESCRIPTION; }
    @Override public String inputSchema() { return INPUT_SCHEMA; }

    @Override
    public String call(JsonNode arguments) {
        String url = arguments.path("url").asText("");
        if (url.isBlank()) return "Error: 'url' argument is required";

        int maxLength = arguments.has("max_length")
                ? arguments.get("max_length").asInt(DEFAULT_MAX_LENGTH)
                : DEFAULT_MAX_LENGTH;
        boolean raw = arguments.path("raw").asBoolean(false);

        try {
            logger.info("Fetching URL: " + url);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("User-Agent", "mcp-gateway/1.0 (fetch tool)")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                return "HTTP " + response.statusCode() + ": " + truncate(response.body(), 500);
            }

            String contentType = response.headers()
                    .firstValue("content-type").orElse("").toLowerCase();
            String text = (raw || !contentType.contains("html"))
                    ? response.body()
                    : extractText(response.body(), url);

            return truncate(text, maxLength);

        } catch (Exception e) {
            logger.warning("fetch failed for " + url + ": " + e.getMessage());
            return "Error fetching " + url + ": " + e.getMessage();
        }
    }

    private static String extractText(String html, String baseUrl) {
        Document doc = Jsoup.parse(html, baseUrl);
        doc.select("script, style, nav, footer, header, aside, [role=navigation]").remove();

        Element main = doc.selectFirst("main, article, [role=main], #content, .content, #main");
        Element root = main != null ? main : doc.body();
        if (root == null) return doc.text();

        StringBuilder sb = new StringBuilder();
        for (Element block : root.select("h1,h2,h3,h4,h5,h6,p,li,pre,blockquote,td,th")) {
            String tag = block.tagName();
            String t = block.text().trim();
            if (t.isEmpty()) continue;
            if (tag.startsWith("h")) {
                sb.append("#".repeat(tag.charAt(1) - '0')).append(" ").append(t).append("\n\n");
            } else if ("pre".equals(tag)) {
                sb.append("```\n").append(block.wholeText().trim()).append("\n```\n\n");
            } else if ("li".equals(tag)) {
                sb.append("- ").append(t).append("\n");
            } else {
                sb.append(t).append("\n\n");
            }
        }
        return sb.isEmpty() ? root.text() : sb.toString().stripTrailing();
    }

    private static String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "\n[truncated — " + text.length() + " chars total]";
    }
}
