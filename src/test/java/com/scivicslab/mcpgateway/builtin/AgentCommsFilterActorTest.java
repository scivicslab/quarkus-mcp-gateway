package com.scivicslab.mcpgateway.builtin;

import com.scivicslab.mcpgateway.registry.ServerEntry;
import com.scivicslab.mcpgateway.registry.ServerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("006")
class AgentCommsFilterActorTest {

    private ServerRegistry registry;
    private AgentCommsFilterActor actor;

    @BeforeEach
    void setUp() throws Exception {
        registry = new ServerRegistry();
        actor = new AgentCommsFilterActor();
        Field f = AgentCommsFilterActor.class.getDeclaredField("registry");
        f.setAccessible(true);
        f.set(actor, registry);
    }

    private AllToolsCache.Entry entry(String name, String serverName) {
        return new AllToolsCache.Entry(name, "desc", "{}", serverName);
    }

    /** Add a healthy ServerEntry directly to the registry's map, bypassing CDI event firing. */
    @SuppressWarnings("unchecked")
    private void registerHealthy(String name) throws Exception {
        var entry = new ServerEntry(name, "http://localhost:1", "");
        entry.setHealthy(true);
        Field f = ServerRegistry.class.getDeclaredField("servers");
        f.setAccessible(true);
        ((Map<String, ServerEntry>) f.get(registry)).put(name, entry);
    }

    /** Add an unhealthy ServerEntry directly to the registry's map. */
    @SuppressWarnings("unchecked")
    private void registerUnhealthy(String name) throws Exception {
        var entry = new ServerEntry(name, "http://localhost:1", "");
        entry.setHealthy(false);
        Field f = ServerRegistry.class.getDeclaredField("servers");
        f.setAccessible(true);
        ((Map<String, ServerEntry>) f.get(registry)).put(name, entry);
    }

    // --- no agent name in query → guidance ---

    @Test
    void filter_noAgentNameInQuery_returnsGuidance() throws Exception {
        registerHealthy("chat-ui-28011");
        var candidates = List.of(entry("submitPrompt", "chat-ui-28011"));

        ToolFilterResult result = actor.filter("help me send something", candidates);

        assertNotNull(result.guidance());
        assertTrue(result.included().isEmpty());
    }

    @Test
    void filter_noAgentNameInQuery_guidanceMentionsAvailableAgents() throws Exception {
        registerHealthy("chat-ui-28011");
        registerHealthy("chat-ui-28003");
        var candidates = List.of(entry("submitPrompt", "chat-ui-28011"));

        ToolFilterResult result = actor.filter("ask another agent", candidates);

        assertTrue(result.guidance().contains("chat-ui-28011"));
        assertTrue(result.guidance().contains("chat-ui-28003"));
    }

    @Test
    void filter_noHealthyAgents_guidanceSaysNoneHealthy() throws Exception {
        registerUnhealthy("chat-ui-28011");
        var candidates = List.of(entry("submitPrompt", "chat-ui-28011"));

        ToolFilterResult result = actor.filter("send to agent", candidates);

        assertTrue(result.guidance().contains("none currently healthy"));
    }

    // --- agent name present → filter by server ---

    @Test
    void filter_queryContainsAgentName_returnsOnlyThatAgentsEntries() throws Exception {
        registerHealthy("chat-ui-28011");
        registerHealthy("chat-ui-28003");
        var candidates = List.of(
            entry("submitPrompt", "chat-ui-28011"),
            entry("submitPrompt", "chat-ui-28003")
        );

        ToolFilterResult result = actor.filter("send to chat-ui-28011", candidates);

        assertNull(result.guidance());
        assertEquals(1, result.included().size());
        assertEquals("chat-ui-28011", result.included().get(0).serverName());
    }

    @Test
    void filter_agentNameMatchIsCaseInsensitive() throws Exception {
        registerHealthy("chat-ui-28011");
        var candidates = List.of(entry("submitPrompt", "chat-ui-28011"));

        ToolFilterResult result = actor.filter("send to CHAT-UI-28011", candidates);

        assertNull(result.guidance());
        assertEquals(1, result.included().size());
    }
}
