package com.scivicslab.mcpgateway.stdio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

class StdioBridgeTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    private StdioRegistry registry;
    private StdioBridge bridge;

    @BeforeEach
    void setUp() throws Exception {
        registry = new StdioRegistry();
        bridge = new StdioBridge();
        Field f = StdioBridge.class.getDeclaredField("registry");
        f.setAccessible(true);
        f.set(bridge, registry);
    }

    private StdioProcess makeProc(String name, String serverOutput) throws Exception {
        var c = StdioProcess.class.getDeclaredConstructor(
                String.class, Process.class, java.io.Writer.class, java.io.Reader.class);
        c.setAccessible(true);
        return c.newInstance(name, null, new StringWriter(), new java.io.StringReader(serverOutput));
    }

    @Test
    void handle_initialize_returnsCachedCapabilitiesAndSessionId() throws Exception {
        var caps = mapper.readTree("{\"tools\":{\"listChanged\":false}}");
        StdioProcess proc = makeProc("fs", "");
        proc.setCachedCapabilities(caps);
        registry.register(proc);

        String req = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"initialize\",\"params\":{}}";
        var result = bridge.handle("fs", req);

        assertNotNull(result.body());
        assertEquals("fs", result.sessionId()); // session ID = server name
        var node = mapper.readTree(result.body());
        assertEquals("1", node.get("id").asText());
        assertTrue(node.path("result").path("tools").path("listChanged").isBoolean());
    }

    @Test
    void handle_initializedNotification_returnsNullBody() throws Exception {
        StdioProcess proc = makeProc("fs", "");
        registry.register(proc);

        String notif = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}";
        var result = bridge.handle("fs", notif);

        assertNull(result.body());
        assertNull(result.sessionId());
    }

    @Test
    void handle_toolsList_forwardsToProcess() throws Exception {
        String serverResponse = "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{\"tools\":[{\"name\":\"read_file\"}]}}\n";
        StdioProcess proc = makeProc("fs", serverResponse);
        registry.register(proc);

        String req = "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"method\":\"tools/list\",\"params\":{}}";
        var result = bridge.handle("fs", req);

        assertNotNull(result.body());
        assertNull(result.sessionId()); // no session ID for non-initialize requests
        var node = mapper.readTree(result.body());
        assertEquals("read_file", node.path("result").path("tools").get(0).path("name").asText());
    }

    @Test
    void handle_unknownServer_returnsErrorBody() {
        String req = "{\"jsonrpc\":\"2.0\",\"id\":\"3\",\"method\":\"tools/list\",\"params\":{}}";
        var result = bridge.handle("no-such-server", req);

        assertNotNull(result.body());
        assertTrue(result.body().contains("\"error\""));
        assertTrue(result.body().contains("no-such-server"));
    }

    @Test
    void handle_toolsCall_forwardsAndReturnsResult() throws Exception {
        String serverResponse = "{\"jsonrpc\":\"2.0\",\"id\":\"9\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}}\n";
        StdioProcess proc = makeProc("fs", serverResponse);
        registry.register(proc);

        String req = "{\"jsonrpc\":\"2.0\",\"id\":\"9\",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"read_file\",\"arguments\":{\"path\":\"/tmp/x\"}}}";
        var result = bridge.handle("fs", req);

        assertNotNull(result.body());
        var node = mapper.readTree(result.body());
        assertEquals("hello", node.path("result").path("content").get(0).path("text").asText());
    }
}
