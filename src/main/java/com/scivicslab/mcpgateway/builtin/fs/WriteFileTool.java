package com.scivicslab.mcpgateway.builtin.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.scivicslab.mcpgateway.builtin.BuiltinTool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

@ApplicationScoped
public class WriteFileTool implements BuiltinTool {

    @Inject FilesystemGuard guard;

    @Override public String name() { return "write_file"; }
    @Override public String description() {
        return "Write text content to a file. Creates the file if it does not exist, overwrites if it does.";
    }
    @Override public String inputSchema() { return """
            {
              "type": "object",
              "properties": {
                "path": { "type": "string", "description": "Absolute path to the file" },
                "content": { "type": "string", "description": "Text content to write" }
              },
              "required": ["path", "content"]
            }
            """; }

    @Override
    public String call(JsonNode args) {
        String pathStr = args.path("path").asText("");
        String content = args.path("content").asText("");
        if (pathStr.isBlank()) return "Error: 'path' is required";

        // Validate parent directory (file may not exist yet)
        String parentStr = Path.of(pathStr).toAbsolutePath().getParent().toString();
        Optional<String> err = guard.check(parentStr);
        if (err.isPresent()) return err.get();

        try {
            Path path = Path.of(pathStr);
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
            return "Written " + content.length() + " chars to " + pathStr;
        } catch (Exception e) {
            return "Error writing " + pathStr + ": " + e.getMessage();
        }
    }
}
