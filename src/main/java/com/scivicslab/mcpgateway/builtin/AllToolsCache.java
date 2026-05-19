package com.scivicslab.mcpgateway.builtin;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.ByteBuffersDirectory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Shared cache of tool definitions populated by AggregatingBridge (stdio + builtin tools)
 * and ToolAggregator (HTTP backend tools) on each tools/list call.
 *
 * <p>Maintains a per-server view so that updates from one backend do not overwrite entries
 * from another. Rebuilds an in-memory Lucene index (ByteBuffersDirectory) on each update,
 * enabling BM25-scored full-text search across tool name and description fields.</p>
 *
 * <p>Tool descriptions may be arbitrarily long — longer descriptions improve search recall
 * because Lucene BM25 scores across the full description text.</p>
 */
@ApplicationScoped
public class AllToolsCache {

    private static final Logger logger = Logger.getLogger(AllToolsCache.class.getName());

    public record Entry(String name, String description, String inputSchema, String serverName) {}

    /** Per-server tool lists. Each updateServer() call replaces one server's slice. */
    private final ConcurrentHashMap<String, List<Entry>> entriesByServer = new ConcurrentHashMap<>();
    private volatile ByteBuffersDirectory directory = new ByteBuffersDirectory();

    /**
     * Replace all tool entries for the given server and rebuild the Lucene index.
     * Called by AggregatingBridge for stdio/builtin tools and by ToolAggregator for HTTP tools.
     */
    public synchronized void updateServer(String serverName, List<Entry> entries) {
        entriesByServer.put(serverName, List.copyOf(entries));
        rebuildIndex();
    }

    public List<Entry> getAll() {
        return entriesByServer.values().stream().flatMap(List::stream).toList();
    }

    /**
     * Search tool name and description fields using Lucene BM25.
     * Name field matches are boosted 3x over description matches.
     * Falls back to the first maxResults entries if the query cannot be parsed or yields no hits.
     */
    public List<Entry> search(String queryStr, int maxResults) {
        if (queryStr == null || queryStr.isBlank()) {
            return getAll().stream().limit(maxResults).toList();
        }
        try {
            List<Entry> results = searchLucene(queryStr, maxResults);
            if (!results.isEmpty()) return results;
        } catch (Exception e) {
            logger.warning("Lucene search failed: " + e.getMessage());
        }
        return getAll().stream().limit(maxResults).toList();
    }

    private void rebuildIndex() {
        try (var analyzer = new StandardAnalyzer()) {
            var config = new IndexWriterConfig(analyzer);
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
            var newDir = new ByteBuffersDirectory();
            try (var writer = new IndexWriter(newDir, config)) {
                for (var entry : getAll()) {
                    var doc = new Document();
                    doc.add(new TextField("name", entry.name(), Field.Store.YES));
                    doc.add(new TextField("description", entry.description(), Field.Store.YES));
                    doc.add(new StoredField("inputSchema", entry.inputSchema()));
                    doc.add(new StoredField("serverName", entry.serverName()));
                    writer.addDocument(doc);
                }
            }
            this.directory = newDir;
        } catch (IOException ex) {
            logger.warning("Failed to rebuild Lucene index: " + ex.getMessage());
        }
    }

    private synchronized List<Entry> searchLucene(String queryStr, int maxResults) throws Exception {
        try (var analyzer = new StandardAnalyzer();
             var reader = DirectoryReader.open(directory)) {

            var boosts = Map.of("name", 3.0f, "description", 1.0f);
            var parser = new MultiFieldQueryParser(new String[]{"name", "description"}, analyzer, boosts);
            parser.setDefaultOperator(QueryParser.Operator.OR);

            var query = parser.parse(queryStr);
            var searcher = new IndexSearcher(reader);
            var hits = searcher.search(query, maxResults);
            var stored = searcher.storedFields();

            List<Entry> results = new ArrayList<>();
            for (var hit : hits.scoreDocs) {
                var doc = stored.document(hit.doc);
                results.add(new Entry(
                    doc.get("name"),
                    doc.get("description"),
                    doc.get("inputSchema"),
                    doc.get("serverName")
                ));
            }
            return results;
        }
    }
}
