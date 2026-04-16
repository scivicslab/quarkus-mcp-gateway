package com.scivicslab.mcpgateway.builtin.alphaxiv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.mcpgateway.builtin.BuiltinTool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Built-in MCP tool: ask questions about academic papers via alphaXiv.
 *
 * <p>Wraps the alphaXiv {@code answer_pdf_queries} tool.
 * Accepts one or more arXiv paper IDs/URLs and a query, then returns
 * an answer grounded in the paper content.</p>
 */
@ApplicationScoped
public class AlphaXivAskPaperTool implements BuiltinTool {

    public static final String NAME = "alphaxiv_ask_paper";
    public static final String DESCRIPTION =
            "Ask a question about one or more academic papers on alphaXiv (arXiv AI layer). "
            + "Provide arXiv paper IDs or URLs and a natural-language query. "
            + "Returns an answer grounded in the paper content — useful for extracting "
            + "methods, results, comparisons, and specific details from papers.";
    public static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "urls": {
                  "type": "array",
                  "items": { "type": "string" },
                  "description": "List of arXiv paper IDs or URLs to query"
                },
                "query": {
                  "type": "string",
                  "description": "Natural-language question to answer from the papers"
                }
              },
              "required": ["urls", "query"]
            }
            """;

    @Inject AlphaXivClient client;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override public String name()        { return NAME; }
    @Override public String description() { return DESCRIPTION; }
    @Override public String inputSchema() { return INPUT_SCHEMA; }

    @Override
    public String call(JsonNode arguments) {
        JsonNode urlsNode = arguments.path("urls");
        if (!urlsNode.isArray() || urlsNode.isEmpty()) {
            return "Error: 'urls' argument is required and must be a non-empty array";
        }
        String query = arguments.path("query").asText("").strip();
        if (query.isBlank()) return "Error: 'query' argument is required";

        try {
            var args = mapper.createObjectNode();
            args.set("urls", urlsNode);
            args.put("query", query);
            return client.callTool("answer_pdf_queries", args);
        } catch (Exception e) {
            return "alphaxiv_ask_paper error: " + e.getMessage();
        }
    }
}
