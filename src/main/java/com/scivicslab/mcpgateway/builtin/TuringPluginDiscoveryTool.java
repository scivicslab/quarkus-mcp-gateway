package com.scivicslab.mcpgateway.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Built-in MCP tool: discover Turing workflow plugins from the local Maven repository.
 *
 * <p>Reads {@code META-INF/turing-plugin.json} directly from each plugin JAR
 * without loading the plugin classes. Surfaces actor names, action names,
 * argument format (array/object/number/string), descriptions, and @param docs.</p>
 */
@ApplicationScoped
public class TuringPluginDiscoveryTool implements BuiltinTool {

    private static final Logger logger = Logger.getLogger(TuringPluginDiscoveryTool.class.getName());
    private static final String PLUGIN_REPO_PATH =
            System.getProperty("user.home") + "/.m2/repository/com/scivicslab/turingworkflow/plugins";
    private static final String MANIFEST_ENTRY = "META-INF/turing-plugin.json";

    @Override public String name() { return "turing_discover_plugins"; }

    @Override
    public String description() {
        return "Discover installed Turing workflow plugins and their available actions. "
             + "Returns actor class names, action names, argument formats (array/object/number/string), "
             + "descriptions, and parameter documentation. "
             + "No plugin loading required — reads metadata directly from JARs. "
             + "Use this to understand what actions are available before building a workflow.";
    }

    @Override
    public String inputSchema() { return """
            {
              "type": "object",
              "properties": {
                "filter": {
                  "type": "string",
                  "description": "Optional substring to filter plugin artifact IDs (e.g. 'shell', 'http')"
                }
              },
              "required": []
            }
            """; }

    @Override
    public String call(JsonNode arguments) {
        String filter = arguments.has("filter") ? arguments.get("filter").asText("").strip() : "";

        Path repoRoot = Path.of(PLUGIN_REPO_PATH);
        if (!Files.exists(repoRoot)) {
            return "No Turing plugins found. Expected repository at: " + PLUGIN_REPO_PATH;
        }

        List<PluginInfo> plugins = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(repoRoot)) {
            walk.filter(p -> p.toString().endsWith(".jar"))
                .filter(p -> !p.toString().endsWith("-sources.jar"))
                .filter(p -> !p.toString().endsWith("-javadoc.jar"))
                .filter(p -> filter.isBlank() || p.getFileName().toString().contains(filter))
                .forEach(jar -> {
                    PluginInfo info = readManifest(jar);
                    if (info != null) plugins.add(info);
                });
        } catch (IOException e) {
            return "Error scanning plugin repository: " + e.getMessage();
        }

        if (plugins.isEmpty()) {
            String msg = "No Turing plugins with manifests found";
            return filter.isBlank() ? msg : msg + " matching filter: " + filter;
        }

        return formatResult(plugins);
    }

    private PluginInfo readManifest(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry(MANIFEST_ENTRY);
            if (entry == null) return null;
            try (InputStream is = jar.getInputStream(entry)) {
                String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                return new PluginInfo(jarPath.getFileName().toString(), json);
            }
        } catch (IOException e) {
            logger.fine("Could not read manifest from " + jarPath + ": " + e.getMessage());
            return null;
        }
    }

    private String formatResult(List<PluginInfo> plugins) {
        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(plugins.size()).append(" Turing plugin(s):\n");

        for (PluginInfo plugin : plugins) {
            sb.append("\n=== ").append(plugin.jarName).append(" ===\n");
            try {
                appendParsedManifest(sb, plugin.manifestJson);
            } catch (Exception e) {
                sb.append("  (manifest parse error: ").append(e.getMessage()).append(")\n");
            }
        }

        return sb.toString().stripTrailing();
    }

    private void appendParsedManifest(StringBuilder sb, String json) {
        // Minimal JSON parsing without pulling in org.json — the gateway may not have it
        // We extract actors array entries using simple pattern matching on the known structure
        String[] actorBlocks = splitActors(json);
        for (String actorBlock : actorBlocks) {
            String className = extractString(actorBlock, "class");
            if (className != null) {
                sb.append("\nActor: ").append(className).append("\n");
            }
            String[] actionBlocks = splitActions(actorBlock);
            for (String actionBlock : actionBlocks) {
                String name = extractString(actionBlock, "name");
                String argsFormat = extractString(actionBlock, "argsFormat");
                String description = extractString(actionBlock, "description");

                sb.append("  Action: ").append(name != null ? name : "?");
                if (argsFormat != null && !"none".equals(argsFormat)) {
                    sb.append("  [args: ").append(argsFormat).append("]");
                }
                sb.append("\n");

                if (description != null) {
                    sb.append("    Description: ").append(description).append("\n");
                }

                appendParams(sb, actionBlock);
            }
        }
    }

    private void appendParams(StringBuilder sb, String actionBlock) {
        int paramsStart = actionBlock.indexOf("\"params\"");
        if (paramsStart < 0) return;
        int arrStart = actionBlock.indexOf('[', paramsStart);
        int arrEnd = findClosing(actionBlock, arrStart, '[', ']');
        if (arrStart < 0 || arrEnd < 0) return;

        String paramsArr = actionBlock.substring(arrStart + 1, arrEnd);
        String[] paramBlocks = splitObjects(paramsArr);
        for (String pb : paramBlocks) {
            String pName = extractString(pb, "name");
            String pDesc = extractString(pb, "description");
            if (pName != null) {
                sb.append("    @param ").append(pName);
                if (pDesc != null) sb.append(": ").append(pDesc);
                sb.append("\n");
            }
        }
    }

    // --- Minimal JSON structure helpers ---

    private String[] splitActors(String json) {
        int start = json.indexOf("\"actors\"");
        if (start < 0) return new String[0];
        int arrStart = json.indexOf('[', start);
        int arrEnd = findClosing(json, arrStart, '[', ']');
        if (arrStart < 0 || arrEnd < 0) return new String[0];
        return splitObjects(json.substring(arrStart + 1, arrEnd));
    }

    private String[] splitActions(String actorBlock) {
        int start = actorBlock.indexOf("\"actions\"");
        if (start < 0) return new String[0];
        int arrStart = actorBlock.indexOf('[', start);
        int arrEnd = findClosing(actorBlock, arrStart, '[', ']');
        if (arrStart < 0 || arrEnd < 0) return new String[0];
        return splitObjects(actorBlock.substring(arrStart + 1, arrEnd));
    }

    private String[] splitObjects(String content) {
        List<String> objects = new ArrayList<>();
        int i = 0;
        while (i < content.length()) {
            int objStart = content.indexOf('{', i);
            if (objStart < 0) break;
            int objEnd = findClosing(content, objStart, '{', '}');
            if (objEnd < 0) break;
            objects.add(content.substring(objStart, objEnd + 1));
            i = objEnd + 1;
        }
        return objects.toArray(new String[0]);
    }

    private int findClosing(String s, int openPos, char open, char close) {
        if (openPos < 0 || openPos >= s.length()) return -1;
        int depth = 0;
        boolean inString = false;
        for (int i = openPos; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && inString) { i++; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private String extractString(String json, String key) {
        String search = "\"" + key + "\"";
        int keyIdx = json.indexOf(search);
        if (keyIdx < 0) return null;
        int colon = json.indexOf(':', keyIdx + search.length());
        if (colon < 0) return null;
        int quoteStart = json.indexOf('"', colon + 1);
        if (quoteStart < 0) return null;
        StringBuilder value = new StringBuilder();
        for (int i = quoteStart + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == '"') { value.append('"'); i++; }
                else if (next == 'n') { value.append('\n'); i++; }
                else if (next == '\\') { value.append('\\'); i++; }
                else value.append(c);
            } else if (c == '"') {
                break;
            } else {
                value.append(c);
            }
        }
        return value.toString();
    }

    private record PluginInfo(String jarName, String manifestJson) {}
}
