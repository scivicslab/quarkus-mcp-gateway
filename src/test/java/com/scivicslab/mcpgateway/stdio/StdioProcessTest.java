package com.scivicslab.mcpgateway.stdio;

import org.junit.jupiter.api.Test;

import java.io.*;

import static org.junit.jupiter.api.Assertions.*;

class StdioProcessTest {

    /** Build a StdioProcess backed by string I/O (no real subprocess). */
    private StdioProcess makeProcess(String name, String serverOutput, StringWriter capturedInput) {
        Reader serverOut = new StringReader(serverOutput);
        return new StdioProcess(name, null, capturedInput, serverOut);
    }

    @Test
    void request_returnsMatchingResponse() throws Exception {
        String serverOutput = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"ok\":true}}\n";
        StringWriter captured = new StringWriter();
        StdioProcess proc = makeProcess("test", serverOutput, captured);

        String response = proc.request("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"tools/list\",\"params\":{}}");

        assertNotNull(response);
        assertTrue(response.contains("\"ok\":true"));
        assertTrue(captured.toString().contains("tools/list"));
    }

    @Test
    void request_skipsNotificationsBeforeMatchingResponse() throws Exception {
        // Server sends a notification first, then the real response
        String serverOutput =
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n" +
                "{\"jsonrpc\":\"2.0\",\"id\":\"5\",\"result\":{\"tools\":[]}}\n";
        StringWriter captured = new StringWriter();
        StdioProcess proc = makeProcess("test", serverOutput, captured);

        String response = proc.request("{\"jsonrpc\":\"2.0\",\"id\":\"5\",\"method\":\"tools/list\",\"params\":{}}");

        assertNotNull(response);
        assertTrue(response.contains("\"tools\":[]"));
    }

    @Test
    void request_noId_returnsNullWithoutReading() throws Exception {
        // If the server were to send something, the test would block — it should not read at all
        StringWriter captured = new StringWriter();
        StdioProcess proc = makeProcess("test", "", captured);

        String result = proc.request("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}");

        assertNull(result);
        assertTrue(captured.toString().contains("notifications/initialized"));
    }

    @Test
    void cachedCapabilities_setAndGet() throws Exception {
        StringWriter captured = new StringWriter();
        StdioProcess proc = makeProcess("test", "", captured);

        assertNull(proc.getCachedCapabilities());

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var caps = mapper.readTree("{\"tools\":{}}");
        proc.setCachedCapabilities(caps);

        assertEquals(caps, proc.getCachedCapabilities());
    }

    @Test
    void isAlive_returnsTrueWhenProcessIsNull() {
        StringWriter captured = new StringWriter();
        StdioProcess proc = makeProcess("test", "", captured);
        assertTrue(proc.isAlive()); // null process treated as alive
    }
}
