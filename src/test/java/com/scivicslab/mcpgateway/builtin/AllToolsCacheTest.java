package com.scivicslab.mcpgateway.builtin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("006")
class AllToolsCacheTest {

    private AllToolsCache cache;

    @BeforeEach
    void setUp() {
        cache = new AllToolsCache();
    }

    private static AllToolsCache.Entry entry(String name, String description, String server) {
        return new AllToolsCache.Entry(name, description, "{}", server);
    }

    // --- updateServer / getAll ---

    @Test
    void getAll_empty_returnsEmptyList() {
        assertTrue(cache.getAll().isEmpty());
    }

    @Test
    void updateServer_addsEntries_getAllReturnsThem() {
        cache.updateServer("srv", List.of(entry("read_file", "Read a file", "srv")));

        var all = cache.getAll();
        assertEquals(1, all.size());
        assertEquals("read_file", all.get(0).name());
    }

    @Test
    void updateServer_calledTwice_replacesOldEntries() {
        cache.updateServer("srv", List.of(entry("tool_a", "Tool A", "srv")));
        cache.updateServer("srv", List.of(entry("tool_b", "Tool B", "srv")));

        var all = cache.getAll();
        assertEquals(1, all.size());
        assertEquals("tool_b", all.get(0).name());
    }

    @Test
    void updateServer_multipleServers_bothSlicesPresent() {
        cache.updateServer("srv1", List.of(entry("tool_a", "Tool A", "srv1")));
        cache.updateServer("srv2", List.of(entry("tool_b", "Tool B", "srv2")));

        var all = cache.getAll();
        assertEquals(2, all.size());
        assertTrue(all.stream().anyMatch(e -> e.name().equals("tool_a")));
        assertTrue(all.stream().anyMatch(e -> e.name().equals("tool_b")));
    }

    @Test
    void updateServer_updatesOneServer_doesNotAffectOther() {
        cache.updateServer("srv1", List.of(entry("tool_a", "Tool A", "srv1")));
        cache.updateServer("srv2", List.of(entry("tool_b", "Tool B", "srv2")));
        cache.updateServer("srv1", List.of(entry("tool_c", "Tool C", "srv1")));

        var all = cache.getAll();
        assertEquals(2, all.size());
        assertTrue(all.stream().anyMatch(e -> e.name().equals("tool_c")));
        assertTrue(all.stream().anyMatch(e -> e.name().equals("tool_b")));
        assertFalse(all.stream().anyMatch(e -> e.name().equals("tool_a")));
    }

    // --- search: blank / null query ---

    @Test
    void search_blankQuery_returnsUpToMaxResults() {
        cache.updateServer("srv", List.of(
            entry("tool_a", "Tool A", "srv"),
            entry("tool_b", "Tool B", "srv"),
            entry("tool_c", "Tool C", "srv")
        ));

        var results = cache.search("", 2);
        assertEquals(2, results.size());
    }

    @Test
    void search_nullQuery_returnsUpToMaxResults() {
        cache.updateServer("srv", List.of(entry("tool_a", "Tool A", "srv")));

        var results = cache.search(null, 5);
        assertEquals(1, results.size());
    }

    // --- search: Lucene BM25 ---

    @Test
    void search_exactNameMatch_returnsMatchingEntry() {
        cache.updateServer("srv", List.of(
            entry("read_file", "Read file contents from disk", "srv"),
            entry("web_search", "Search the web with DuckDuckGo", "srv")
        ));

        var results = cache.search("read_file", 5);

        assertFalse(results.isEmpty());
        assertEquals("read_file", results.get(0).name());
    }

    @Test
    void search_descriptionKeyword_returnsRelevantEntry() {
        cache.updateServer("srv", List.of(
            entry("read_file", "Read file contents from disk", "srv"),
            entry("web_search", "Search the internet using DuckDuckGo web search engine", "srv")
        ));

        var results = cache.search("internet search", 5);

        assertFalse(results.isEmpty());
        assertEquals("web_search", results.get(0).name());
    }

    @Test
    void search_noMatch_fallsBackToFirstNEntries() {
        cache.updateServer("srv", List.of(
            entry("tool_a", "Tool A does something useful", "srv"),
            entry("tool_b", "Tool B does something useful", "srv")
        ));

        // "zzzzz" won't match anything — should fall back to all entries
        var results = cache.search("zzzzz", 5);

        assertEquals(2, results.size());
    }

    @Test
    void search_maxResults_limitsReturnedCount() {
        cache.updateServer("srv", List.of(
            entry("tool_a", "useful tool", "srv"),
            entry("tool_b", "useful tool", "srv"),
            entry("tool_c", "useful tool", "srv"),
            entry("tool_d", "useful tool", "srv")
        ));

        var results = cache.search("useful", 2);

        assertEquals(2, results.size());
    }

    @Test
    void search_preservesServerName() {
        cache.updateServer("my-server", List.of(entry("my_tool", "My tool description", "my-server")));

        var results = cache.search("my_tool", 5);

        assertEquals("my-server", results.get(0).serverName());
    }

    @Test
    void search_preservesInputSchema() {
        var schema = "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}";
        cache.updateServer("srv", List.of(new AllToolsCache.Entry("read_file", "reads a file", schema, "srv")));

        var results = cache.search("read_file", 5);

        assertEquals(schema, results.get(0).inputSchema());
    }
}
