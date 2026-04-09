package com.scivicslab.mcpgateway.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Built-in MCP tool: extract text from a local document file using Apache Tika.
 *
 * <p>Supports PDF, Word (.docx/.doc), Excel (.xlsx/.xls), PowerPoint (.pptx/.ppt),
 * plain text, HTML, and hundreds of other formats.</p>
 *
 * <p>Registered automatically via CDI — no changes to AggregatingBridge needed.</p>
 */
@ApplicationScoped
public class TikaTool implements BuiltinTool {

    public static final String NAME = "read_document";
    public static final String DESCRIPTION =
            "Extract text content from a local document file. "
            + "Supports PDF, Word (.docx/.doc), Excel (.xlsx/.xls), PowerPoint (.pptx/.ppt), "
            + "plain text, HTML, and many other formats via Apache Tika. "
            + "Use this to read the contents of documents on the filesystem.";
    public static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "Absolute path to the document file"
                },
                "max_length": {
                  "type": "integer",
                  "description": "Maximum characters to return (default 10000)"
                }
              },
              "required": ["path"]
            }
            """;

    private static final Logger logger = Logger.getLogger(TikaTool.class.getName());
    private static final int DEFAULT_MAX_LENGTH = 10000;
    private static final Tika tika = new Tika();

    @Override public String name()        { return NAME; }
    @Override public String description() { return DESCRIPTION; }
    @Override public String inputSchema() { return INPUT_SCHEMA; }

    @Override
    public String call(JsonNode arguments) {
        String pathStr = arguments.path("path").asText("");
        if (pathStr.isBlank()) return "Error: 'path' argument is required";

        int maxLength = arguments.has("max_length")
                ? arguments.get("max_length").asInt(DEFAULT_MAX_LENGTH)
                : DEFAULT_MAX_LENGTH;

        Path path = Path.of(pathStr);
        if (!Files.exists(path)) return "Error: file not found: " + pathStr;
        if (!Files.isReadable(path)) return "Error: file not readable: " + pathStr;

        try {
            logger.info("Extracting text from: " + pathStr);
            Metadata metadata = new Metadata();
            String text;
            try (InputStream is = new FileInputStream(path.toFile())) {
                text = tika.parseToString(is, metadata, maxLength);
            }

            StringBuilder sb = new StringBuilder();
            String title = metadata.get(TikaCoreProperties.TITLE);
            String contentType = metadata.get(Metadata.CONTENT_TYPE);
            if (title != null && !title.isBlank()) sb.append("Title: ").append(title).append("\n");
            if (contentType != null) sb.append("Type: ").append(contentType).append("\n");
            if (!sb.isEmpty()) sb.append("\n");
            sb.append(text);

            return sb.toString();
        } catch (Exception e) {
            logger.warning("Tika extraction failed for " + pathStr + ": " + e.getMessage());
            return "Error reading " + pathStr + ": " + e.getMessage();
        }
    }
}
