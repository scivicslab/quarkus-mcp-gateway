package com.scivicslab.mcpgateway.builtin.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.scivicslab.mcpgateway.builtin.BuiltinTool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ListAllowedDirsTool implements BuiltinTool {

    @Inject FilesystemGuard guard;

    @Override public String name() { return "list_allowed_directories"; }
    @Override public String description() {
        return "List the directories that the filesystem tools are allowed to access.";
    }
    @Override public String inputSchema() { return """
            { "type": "object", "properties": {} }
            """; }

    @Override
    public String call(JsonNode args) {
        return "Allowed directories:\n"
                + String.join("\n", guard.getAllowedDirs());
    }
}
