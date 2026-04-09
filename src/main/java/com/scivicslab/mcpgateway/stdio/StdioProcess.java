package com.scivicslab.mcpgateway.stdio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wraps a running stdio MCP server process.
 *
 * MCP over stdio uses newline-delimited JSON-RPC 2.0.
 * Each request is one JSON line written to stdin.
 * Each response is one JSON line read from stdout.
 * Server-initiated notifications (no matching id) are skipped.
 *
 * All I/O is synchronized to serialize concurrent callers.
 */
public class StdioProcess {

    private static final Logger logger = Logger.getLogger(StdioProcess.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String name;
    private final Process process;
    private final BufferedWriter stdin;
    private final BufferedReader stdout;
    private JsonNode cachedCapabilities;

    public StdioProcess(String name, Process process) {
        this.name = name;
        this.process = process;
        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
        this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));
    }

    /** For testing: inject custom streams without a real process. */
    StdioProcess(String name, Process process, Writer stdin, Reader stdout) {
        this.name = name;
        this.process = process;
        this.stdin = new BufferedWriter(stdin);
        this.stdout = new BufferedReader(stdout);
    }

    public String getName() { return name; }
    public boolean isAlive() { return process == null || process.isAlive(); }
    public JsonNode getCachedCapabilities() { return cachedCapabilities; }
    public void setCachedCapabilities(JsonNode capabilities) { this.cachedCapabilities = capabilities; }

    /**
     * Send a JSON-RPC message to the process.
     *
     * If the message has an "id" field, this is a request: reads stdout until
     * a response with the matching id is found. Server-initiated notifications
     * (lines without a matching id) are logged and skipped.
     *
     * If the message has no "id" (a notification), sends it and returns null
     * without reading stdout.
     */
    public synchronized String request(String json) throws IOException {
        JsonNode req = mapper.readTree(json);
        String requestId = extractId(req);

        stdin.write(json);
        stdin.newLine();
        stdin.flush();

        if (requestId == null) {
            // Notification — no response expected
            return null;
        }

        while (true) {
            String line = stdout.readLine();
            if (line == null) {
                throw new IOException("stdio process '" + name + "' closed stdout unexpectedly");
            }
            if (line.isBlank()) continue;

            try {
                JsonNode resp = mapper.readTree(line);
                String respId = extractId(resp);
                if (requestId.equals(respId)) {
                    return line;
                }
                // Server-initiated notification or id mismatch — skip
                logger.fine("[" + name + "] skipping line (not matching id=" + requestId + "): " + line);
            } catch (Exception e) {
                logger.log(Level.WARNING, "[" + name + "] failed to parse stdout line, skipping: " + line, e);
            }
        }
    }

    public void destroy() {
        try { stdin.close(); } catch (Exception ignored) {}
        if (process != null) process.destroyForcibly();
    }

    private static String extractId(JsonNode node) {
        if (!node.has("id") || node.get("id").isNull()) return null;
        return node.get("id").asText();
    }
}
