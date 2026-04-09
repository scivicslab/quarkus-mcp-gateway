package com.scivicslab.mcpgateway.builtin.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.scivicslab.mcpgateway.builtin.BuiltinTool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Optional;

@ApplicationScoped
public class GetFileInfoTool implements BuiltinTool {

    @Inject FilesystemGuard guard;

    @Override public String name() { return "get_file_info"; }
    @Override public String description() {
        return "Get metadata about a file or directory: size, type, and modification time.";
    }
    @Override public String inputSchema() { return """
            {
              "type": "object",
              "properties": {
                "path": { "type": "string", "description": "Absolute path to the file or directory" }
              },
              "required": ["path"]
            }
            """; }

    @Override
    public String call(JsonNode args) {
        String pathStr = args.path("path").asText("");
        if (pathStr.isBlank()) return "Error: 'path' is required";

        Optional<String> err = guard.check(pathStr);
        if (err.isPresent()) return err.get();

        try {
            Path path = Path.of(pathStr);
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            return "path: " + pathStr + "\n"
                    + "type: " + (attrs.isDirectory() ? "directory" : "file") + "\n"
                    + "size: " + attrs.size() + " bytes\n"
                    + "created: " + Instant.ofEpochMilli(attrs.creationTime().toMillis()) + "\n"
                    + "modified: " + Instant.ofEpochMilli(attrs.lastModifiedTime().toMillis());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
