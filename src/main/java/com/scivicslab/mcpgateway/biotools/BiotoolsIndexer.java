package com.scivicslab.mcpgateway.biotools;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Indexes the biotools Singularity container directory into a Lucene index.
 *
 * <p>Directory layout expected:
 * <pre>
 *   {scan-dir}/{initial}/{tool-name}:{version}--{build}
 * </pre>
 * e.g. /home/devteam/biotools/a/aardvark:0.8.1--h4349ce8_1
 * </p>
 *
 * <p>Run standalone to build/rebuild the index:
 * <pre>
 *   java -cp quarkus-mcp-gateway-*-runner.jar \
 *        com.scivicslab.mcpgateway.biotools.BiotoolsIndexer \
 *        /home/devteam/biotools \
 *        /mnt/stonefly520/MCP_data/biotools-index
 * </pre>
 * </p>
 */
public class BiotoolsIndexer {

    private static final Logger logger = Logger.getLogger(BiotoolsIndexer.class.getName());

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: BiotoolsIndexer <scan-dir> <index-dir>");
            System.exit(1);
        }
        Path scanDir  = Path.of(args[0]);
        Path indexDir = Path.of(args[1]);
        new BiotoolsIndexer().buildIndex(scanDir, indexDir);
    }

    /**
     * Walk scanDir and write a Lucene index to indexDir.
     *
     * @return number of documents indexed
     */
    public int buildIndex(Path scanDir, Path indexDir) throws IOException {
        logger.info("Indexing " + scanDir + " → " + indexDir);
        Files.createDirectories(indexDir);

        try (FSDirectory dir = FSDirectory.open(indexDir);
             IndexWriter writer = new IndexWriter(dir,
                     new IndexWriterConfig(new StandardAnalyzer()))) {

            writer.deleteAll();

            int[] count = {0};
            // Two-level walk: {initial}/{filename}
            try (Stream<Path> initials = Files.list(scanDir)) {
                initials.filter(Files::isDirectory).forEach(initial -> {
                    try (Stream<Path> entries = Files.list(initial)) {
                        entries.forEach(entry -> {
                            Document doc = toDocument(entry);
                            if (doc != null) {
                                try {
                                    writer.addDocument(doc);
                                    count[0]++;
                                } catch (IOException e) {
                                    logger.warning("Failed to index: " + entry + " — " + e.getMessage());
                                }
                            }
                        });
                    } catch (IOException e) {
                        logger.warning("Failed to list: " + initial + " — " + e.getMessage());
                    }
                });
            }

            writer.commit();
            logger.info("Indexed " + count[0] + " containers");
            return count[0];
        }
    }

    private static Document toDocument(Path entry) {
        String filename = entry.getFileName().toString();

        // Expected format: tool-name:version--build  (colon separator)
        int colonIdx = filename.indexOf(':');
        if (colonIdx < 0) return null;

        String name    = filename.substring(0, colonIdx);
        String rest    = filename.substring(colonIdx + 1);  // version--build

        // Split version from build hash (double-dash separator)
        int dashIdx = rest.indexOf("--");
        String version = dashIdx >= 0 ? rest.substring(0, dashIdx) : rest;
        String build   = dashIdx >= 0 ? rest.substring(dashIdx + 2) : "";

        Document doc = new Document();
        doc.add(new TextField("name",     name,              Field.Store.YES));
        doc.add(new TextField("version",  version,           Field.Store.YES));
        doc.add(new StringField("build",  build,             Field.Store.YES));
        doc.add(new StringField("path",   entry.toString(),  Field.Store.YES));
        doc.add(new TextField("filename", filename,          Field.Store.YES));
        return doc;
    }
}
