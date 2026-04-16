package com.scivicslab.mcpgateway.builtin.alphaxiv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.mcpgateway.builtin.BuiltinTool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Built-in MCP tool: retrieve the full content of an academic paper via alphaXiv.
 *
 * <p>Wraps the alphaXiv {@code get_paper_content} tool.
 * Accepts an arXiv paper ID or URL and returns the paper content.</p>
 */
@ApplicationScoped
public class AlphaXivGetPaperTool implements BuiltinTool {

    public static final String NAME = "alphaxiv_get_paper";
    public static final String DESCRIPTION =
            "Retrieve the full content of an academic paper from alphaXiv (arXiv AI layer). "
            + "Accepts an arXiv paper ID (e.g. '2301.07041') or arXiv URL. "
            + "Returns the paper text, which can be used for detailed reading or analysis.";
    public static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "url": {
                  "type": "string",
                  "description": "arXiv paper ID (e.g. '2301.07041') or full arXiv URL"
                },
                "full_text": {
                  "type": "boolean",
                  "description": "Whether to return the full paper text (default: false — returns abstract and metadata)"
                }
              },
              "required": ["url"]
            }
            """;

    @Inject AlphaXivClient client;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override public String name()        { return NAME; }
    @Override public String description() { return DESCRIPTION; }
    @Override public String inputSchema() { return INPUT_SCHEMA; }

    @Override
    public String call(JsonNode arguments) {
        String url = arguments.path("url").asText("").strip();
        if (url.isBlank()) return "Error: 'url' argument is required";

        try {
            var args = mapper.createObjectNode();
            args.put("url", url);
            if (arguments.has("full_text")) {
                args.put("full_text", arguments.path("full_text").asBoolean(false));
            }
            return client.callTool("get_paper_content", args);
        } catch (Exception e) {
            return "alphaxiv_get_paper error: " + e.getMessage();
        }
    }
}
