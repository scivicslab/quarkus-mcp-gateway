package com.scivicslab.mcpgateway.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Tag("006")
class FindToolsToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // --- helpers ---

    private static ObjectNode query(String q) {
        return MAPPER.createObjectNode().put("query", q);
    }

    private static AllToolsCache.Entry entry(String name, String serverName) {
        return new AllToolsCache.Entry(name, name + " description", "{}", serverName);
    }

    /** Minimal Instance<ToolFilterActor> backed by a fixed list. */
    @SafeVarargs
    private static Instance<ToolFilterActor> instanceOf(ToolFilterActor... actors) {
        var list = List.of(actors);
        return new Instance<>() {
            @Override public Iterator<ToolFilterActor> iterator() { return list.iterator(); }
            @Override public ToolFilterActor get() { throw new UnsupportedOperationException(); }
            @Override public Instance<ToolFilterActor> select(Annotation... a) { throw new UnsupportedOperationException(); }
            @Override public <U extends ToolFilterActor> Instance<U> select(Class<U> c, Annotation... a) { throw new UnsupportedOperationException(); }
            @Override public <U extends ToolFilterActor> Instance<U> select(TypeLiteral<U> t, Annotation... a) { throw new UnsupportedOperationException(); }
            @Override public boolean isUnsatisfied() { return list.isEmpty(); }
            @Override public boolean isAmbiguous() { return list.size() > 1; }
            @Override public boolean isResolvable() { return list.size() == 1; }
            @Override public void destroy(ToolFilterActor a) {}
            @Override public Handle<ToolFilterActor> getHandle() { throw new UnsupportedOperationException(); }
            @Override public Iterable<Handle<ToolFilterActor>> handles() { throw new UnsupportedOperationException(); }
        };
    }

    private FindToolsTool build(AllToolsCache cache, ToolFilterActor... actors) throws Exception {
        var tool = new FindToolsTool();
        setField(tool, "cache", cache);
        setField(tool, "filterActors", instanceOf(actors));
        tool.init();
        return tool;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = FindToolsTool.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    // --- tests: passthrough (no actors) ---

    @Test
    void call_noActors_returnsAllMatchesAsJson() throws Exception {
        var cache = new AllToolsCache();
        cache.updateServer("srv", List.of(entry("read_file", "srv")));
        var tool = build(cache);

        String result = tool.call(query("read file"));

        assertTrue(result.startsWith("["));
        assertTrue(result.contains("\"read_file\""));
    }

    @Test
    void call_noActors_emptyQuery_returnsFirstEntries() throws Exception {
        var cache = new AllToolsCache();
        cache.updateServer("srv", List.of(entry("tool_a", "srv"), entry("tool_b", "srv")));
        var tool = build(cache);

        String result = tool.call(query(""));

        assertTrue(result.contains("tool_a") || result.contains("tool_b"));
    }

    // --- tests: actor returns guidance ---

    @Test
    void call_actorReturnsGuidance_returnsGuidanceStringNotJson() throws Exception {
        var cache = new AllToolsCache();
        cache.updateServer("srv", List.of(entry("submitPrompt", "srv")));

        ToolFilterActor guidanceActor = new ToolFilterActor() {
            @Override public Set<String> handledToolNames() { return Set.of("submitPrompt"); }
            @Override public ToolFilterResult filter(String q, List<AllToolsCache.Entry> c) {
                return ToolFilterResult.guidance("Please specify an agent name.");
            }
        };

        var tool = build(cache, guidanceActor);
        String result = tool.call(query("submitPrompt"));

        assertEquals("Please specify an agent name.", result);
    }

    // --- tests: actor filters entries ---

    @Test
    void call_actorFiltersEntries_mergesWithPassthrough() throws Exception {
        var cache = new AllToolsCache();
        cache.updateServer("srv", List.of(
            entry("read_file", "srv"),      // passthrough
            entry("submitPrompt", "srv")    // handled by actor
        ));

        ToolFilterActor filteringActor = new ToolFilterActor() {
            @Override public Set<String> handledToolNames() { return Set.of("submitPrompt"); }
            @Override public ToolFilterResult filter(String q, List<AllToolsCache.Entry> c) {
                return ToolFilterResult.of(c); // include all
            }
        };

        var tool = build(cache, filteringActor);
        String result = tool.call(query("submit read"));

        assertTrue(result.contains("read_file"));
        assertTrue(result.contains("submitPrompt"));
    }

    @Test
    void call_actorReturnsEmptyIncluded_removesHandledToolsFromResult() throws Exception {
        var cache = new AllToolsCache();
        cache.updateServer("srv", List.of(
            entry("read_file", "srv"),
            entry("submitPrompt", "srv")
        ));

        ToolFilterActor rejectActor = new ToolFilterActor() {
            @Override public Set<String> handledToolNames() { return Set.of("submitPrompt"); }
            @Override public ToolFilterResult filter(String q, List<AllToolsCache.Entry> c) {
                return ToolFilterResult.of(List.of()); // reject all
            }
        };

        var tool = build(cache, rejectActor);
        String result = tool.call(query("submit read"));

        assertTrue(result.contains("read_file"));
        assertFalse(result.contains("submitPrompt"));
    }

    // --- tests: startup validation ---

    @Test
    void init_duplicateToolName_throwsIllegalStateException() {
        var cache = new AllToolsCache();
        ToolFilterActor a1 = new ToolFilterActor() {
            @Override public Set<String> handledToolNames() { return Set.of("sharedTool"); }
            @Override public ToolFilterResult filter(String q, List<AllToolsCache.Entry> c) { return ToolFilterResult.of(c); }
        };
        ToolFilterActor a2 = new ToolFilterActor() {
            @Override public Set<String> handledToolNames() { return Set.of("sharedTool"); }
            @Override public ToolFilterResult filter(String q, List<AllToolsCache.Entry> c) { return ToolFilterResult.of(c); }
        };

        var tool = new FindToolsTool();
        assertThrows(IllegalStateException.class, () -> {
            setField(tool, "cache", cache);
            setField(tool, "filterActors", instanceOf(a1, a2));
            tool.init();
        });
    }
}
