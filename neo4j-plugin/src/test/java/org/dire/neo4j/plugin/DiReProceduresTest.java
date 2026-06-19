package org.dire.neo4j.plugin;

import org.junit.jupiter.api.Test;
import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilders;

import javax.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiReProceduresTest {
    @Test
    void parsesSpectralConvergenceControls() {
        DiReConfig config = DiReConfig.parse(Map.of(
                "nodeQuery", "MATCH (n) RETURN id(n) AS id",
                "relationshipQuery", "MATCH (a)-->(b) RETURN id(a) AS source, id(b) AS target",
                "spectralTolerance", 0.001,
                "spectralMinIterations", 12,
                "spectralMaxIterations", 80));

        assertEquals(0.001f, config.layoutConfig.spectralTolerance());
        assertEquals(12, config.layoutConfig.spectralMinIterations());
        assertEquals(80, config.layoutConfig.spectralMaxIterations());
    }

    @Test
    void streamReturnsCoordinates() {
        try (Neo4j neo4j = database()) {
            GraphDatabaseService db = neo4j.defaultDatabaseService();
            seedGraph(db);

            try (Transaction tx = db.beginTx()) {
                Result result = tx.execute("""
                        CALL dire.layout.stream({
                          nodeQuery: 'MATCH (n:Paper) RETURN id(n) AS id ORDER BY id(n)',
                          relationshipQuery: 'MATCH (a:Paper)-[r:CITES]->(b:Paper) RETURN id(a) AS source, id(b) AS target, coalesce(r.weight, 1.0) AS weight',
                          iterations: 5,
                          randomSeed: 42,
                          includeEmbedding: true
                        })
                        YIELD nodeId, x, y, initialX, initialY, embedding, initialEmbedding
                        RETURN nodeId, x, y, initialX, initialY, embedding, initialEmbedding
                        ORDER BY nodeId
                        """);

                int rows = 0;
                while (result.hasNext()) {
                    Map<String, Object> row = result.next();
                    assertNotNull(row.get("nodeId"));
                    assertTrue(Double.isFinite(((Number) row.get("x")).doubleValue()));
                    assertTrue(Double.isFinite(((Number) row.get("y")).doubleValue()));
                    assertTrue(Double.isFinite(((Number) row.get("initialX")).doubleValue()));
                    assertTrue(Double.isFinite(((Number) row.get("initialY")).doubleValue()));
                    assertEquals(2, ((List<?>) row.get("embedding")).size());
                    assertEquals(2, ((List<?>) row.get("initialEmbedding")).size());
                    rows++;
                }
                assertEquals(4, rows);
                tx.commit();
            }
        }
    }

    @Test
    void streamOmitsEmbeddingListsByDefault() {
        try (Neo4j neo4j = database()) {
            GraphDatabaseService db = neo4j.defaultDatabaseService();
            seedGraph(db);

            try (Transaction tx = db.beginTx()) {
                Result result = tx.execute("""
                        CALL dire.layout.stream({
                          nodeQuery: 'MATCH (n:Paper) RETURN id(n) AS id ORDER BY id(n)',
                          relationshipQuery: 'MATCH (a:Paper)-[:CITES]->(b:Paper) RETURN id(a) AS source, id(b) AS target',
                          iterations: 1
                        })
                        YIELD nodeId, embedding, initialEmbedding
                        RETURN nodeId, embedding, initialEmbedding
                        ORDER BY nodeId
                        """);

                int rows = 0;
                while (result.hasNext()) {
                    Map<String, Object> row = result.next();
                    assertNotNull(row.get("nodeId"));
                    assertNull(row.get("embedding"));
                    assertNull(row.get("initialEmbedding"));
                    rows++;
                }
                assertEquals(4, rows);
                tx.commit();
            }
        }
    }

    @Test
    void writePersistsCoordinatesAndReportsCounts() {
        try (Neo4j neo4j = database()) {
            GraphDatabaseService db = neo4j.defaultDatabaseService();
            seedGraph(db);

            try (Transaction tx = db.beginTx()) {
                Result result = tx.execute("""
                        CALL dire.layout.write({
                          nodeQuery: 'MATCH (n:Paper) RETURN id(n) AS id',
                          relationshipQuery: 'MATCH (a:Paper)-[:CITES]->(b:Paper) RETURN id(a) AS source, id(b) AS target',
                          writeProperties: ['dire_x', 'dire_y'],
                          writeInitialProperties: ['dire_initial_x', 'dire_initial_y'],
                          iterations: 3,
                          randomSeed: 99
                        })
                        YIELD nodesWritten, relationshipsRead, iterations
                        RETURN nodesWritten, relationshipsRead, iterations
                        """);
                Map<String, Object> row = result.next();
                assertEquals(4L, row.get("nodesWritten"));
                assertEquals(4L, row.get("relationshipsRead"));
                assertEquals(3L, row.get("iterations"));
                tx.commit();
            }

            try (Transaction tx = db.beginTx()) {
                Result result = tx.execute("""
                        MATCH (n:Paper)
                        RETURN
                          count(n) AS nodes,
                          count(n.dire_x) AS xs,
                          count(n.dire_y) AS ys,
                          count(n.dire_initial_x) AS initialXs,
                          count(n.dire_initial_y) AS initialYs
                        """);
                Map<String, Object> row = result.next();
                assertEquals(4L, row.get("nodes"));
                assertEquals(4L, row.get("xs"));
                assertEquals(4L, row.get("ys"));
                assertEquals(4L, row.get("initialXs"));
                assertEquals(4L, row.get("initialYs"));
                tx.commit();
            }
        }
    }

    @Test
    void batchedWritePersistsFinalAndInitialCoordinates() {
        try (Neo4j neo4j = database()) {
            GraphDatabaseService db = neo4j.defaultDatabaseService();
            seedGraph(db);

            try (Transaction tx = db.beginTx()) {
                Result result = tx.execute("""
                        CALL dire.layout.write({
                          nodeQuery: 'MATCH (n:Paper) RETURN id(n) AS id ORDER BY id(n)',
                          relationshipQuery: 'MATCH (a:Paper)-[:CITES]->(b:Paper) RETURN id(a) AS source, id(b) AS target',
                          writeProperties: ['batch_x', 'batch_y'],
                          writeInitialProperties: ['batch_initial_x', 'batch_initial_y'],
                          writeBatchSize: 3,
                          iterations: 1
                        })
                        YIELD nodesWritten
                        RETURN nodesWritten
                        """);
                assertEquals(4L, result.next().get("nodesWritten"));
                tx.commit();
            }

            try (Transaction tx = db.beginTx()) {
                Map<String, Object> row = tx.execute("""
                        MATCH (n:Paper)
                        RETURN count(n.batch_x) AS xs,
                               count(n.batch_y) AS ys,
                               count(n.batch_initial_x) AS initialXs,
                               count(n.batch_initial_y) AS initialYs
                        """).next();
                assertEquals(4L, row.get("xs"));
                assertEquals(4L, row.get("ys"));
                assertEquals(4L, row.get("initialXs"));
                assertEquals(4L, row.get("initialYs"));
                tx.commit();
            }
        }
    }

    @Test
    void defaultWriteRollsBackWithCallerWhileBatchedWriteDoesNot() {
        try (Neo4j neo4j = database()) {
            GraphDatabaseService db = neo4j.defaultDatabaseService();
            seedGraph(db);

            try (Transaction tx = db.beginTx()) {
                tx.execute("""
                        CALL dire.layout.write({
                          nodeQuery: 'MATCH (n:Paper) RETURN id(n) AS id',
                          relationshipQuery: 'MATCH (a:Paper)-[:CITES]->(b:Paper) RETURN id(a) AS source, id(b) AS target',
                          writeProperties: ['atomic_x', 'atomic_y'],
                          writeInitialProperties: [],
                          iterations: 0
                        })
                        YIELD nodesWritten
                        RETURN nodesWritten
                        """).next();
            }
            assertEquals(0L, countNodesWithProperty(db, "atomic_x"));

            try (Transaction tx = db.beginTx()) {
                tx.execute("""
                        CALL dire.layout.write({
                          nodeQuery: 'MATCH (n:Paper) RETURN id(n) AS id',
                          relationshipQuery: 'MATCH (a:Paper)-[:CITES]->(b:Paper) RETURN id(a) AS source, id(b) AS target',
                          writeProperties: ['non_atomic_x', 'non_atomic_y'],
                          writeInitialProperties: [],
                          writeBatchSize: 2,
                          iterations: 0
                        })
                        YIELD nodesWritten
                        RETURN nodesWritten
                        """).next();
            }
            assertEquals(4L, countNodesWithProperty(db, "non_atomic_x"));
        }
    }

    @Test
    void batchedWriteUsesStableBoundariesAndStopsAfterFailure() {
        List<String> ranges = new java.util.ArrayList<>();

        int written = DiReProcedures.writeBatches(7, 3, (start, end) -> ranges.add(start + ":" + end));

        assertEquals(7, written);
        assertEquals(List.of("0:3", "3:6", "6:7"), ranges);

        AtomicInteger calls = new AtomicInteger();
        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                DiReProcedures.writeBatches(7, 3, (start, end) -> {
                    if (calls.incrementAndGet() == 2) {
                        throw new IllegalStateException("batch failed");
                    }
                }));
        assertEquals("batch failed", failure.getMessage());
        assertEquals(2, calls.get());
    }

    @Test
    void writeBatchSizeIsOptionalAndMustBePositive() {
        Map<String, Object> required = Map.of(
                "nodeQuery", "MATCH (n) RETURN id(n) AS id",
                "relationshipQuery", "MATCH (a)-->(b) RETURN id(a) AS source, id(b) AS target");

        assertNull(DiReConfig.parse(required).writeBatchSize);

        Map<String, Object> zero = new java.util.HashMap<>(required);
        zero.put("writeBatchSize", 0);
        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> DiReConfig.parse(zero));
        assertTrue(error.getMessage().contains("writeBatchSize"));
    }

    @Test
    void streamAcceptsElementIdsWithSurrogateNodeIds() {
        try (Neo4j neo4j = database()) {
            GraphDatabaseService db = neo4j.defaultDatabaseService();
            seedGraph(db);

            try (Transaction tx = db.beginTx()) {
                Result result = tx.execute("""
                        CALL dire.layout.stream({
                          nodeQuery: 'MATCH (n:Paper) RETURN elementId(n) AS id ORDER BY n.name',
                          relationshipQuery: 'MATCH (a:Paper)-[:CITES]->(b:Paper) RETURN elementId(a) AS source, elementId(b) AS target',
                          iterations: 2,
                          randomSeed: 42
                        })
                        YIELD nodeId, elementId, x, y
                        RETURN nodeId, elementId, x, y
                        ORDER BY nodeId
                        """);

                int rows = 0;
                while (result.hasNext()) {
                    Map<String, Object> row = result.next();
                    assertEquals((long) rows, row.get("nodeId"));
                    assertNotNull(row.get("elementId"));
                    assertTrue(((String) row.get("elementId")).length() > 0);
                    assertTrue(Double.isFinite(((Number) row.get("x")).doubleValue()));
                    assertTrue(Double.isFinite(((Number) row.get("y")).doubleValue()));
                    rows++;
                }
                assertEquals(4, rows);
                tx.commit();
            }
        }
    }

    @Test
    void writeWithElementIdsPersistsCoordinates() {
        try (Neo4j neo4j = database()) {
            GraphDatabaseService db = neo4j.defaultDatabaseService();
            seedGraph(db);

            try (Transaction tx = db.beginTx()) {
                Result result = tx.execute("""
                        CALL dire.layout.write({
                          nodeQuery: 'MATCH (n:Paper) RETURN elementId(n) AS id ORDER BY n.name',
                          relationshipQuery: 'MATCH (a:Paper)-[:CITES]->(b:Paper) RETURN elementId(a) AS source, elementId(b) AS target',
                          writeProperties: ['eid_x', 'eid_y'],
                          iterations: 2,
                          randomSeed: 42
                        })
                        YIELD nodesWritten, relationshipsRead
                        RETURN nodesWritten, relationshipsRead
                        """);
                Map<String, Object> row = result.next();
                assertEquals(4L, row.get("nodesWritten"));
                assertEquals(4L, row.get("relationshipsRead"));
                tx.commit();
            }

            try (Transaction tx = db.beginTx()) {
                Result result = tx.execute("""
                        MATCH (n:Paper)
                        RETURN count(n) AS nodes, count(n.eid_x) AS xs, count(n.eid_y) AS ys
                        """);
                Map<String, Object> row = result.next();
                assertEquals(4L, row.get("nodes"));
                assertEquals(4L, row.get("xs"));
                assertEquals(4L, row.get("ys"));
                tx.commit();
            }
        }
    }

    @Test
    void warmStartWithElementIdsReadsInitialCoordinates() {
        try (Neo4j neo4j = database()) {
            GraphDatabaseService db = neo4j.defaultDatabaseService();
            seedGraph(db);
            seedWarmStart(db);

            try (Transaction tx = db.beginTx()) {
                Result result = tx.execute("""
                        CALL dire.layout.stream({
                          nodeQuery: 'MATCH (n:Paper) RETURN elementId(n) AS id ORDER BY n.name',
                          relationshipQuery: 'MATCH (a:Paper)-[:CITES]->(b:Paper) RETURN elementId(a) AS source, elementId(b) AS target',
                          initialization: 'warm_start',
                          warmStartProperties: ['warm_x', 'warm_y'],
                          iterations: 0
                        })
                        YIELD nodeId, elementId, initialX, initialY
                        RETURN nodeId, elementId, initialX, initialY
                        ORDER BY nodeId
                        """);

                int rows = 0;
                double[] normalized = {-1.3416407864998738, -0.4472135954999579, 0.4472135954999579, 1.3416407864998738};
                while (result.hasNext()) {
                    Map<String, Object> row = result.next();
                    assertEquals((long) rows, row.get("nodeId"));
                    assertNotNull(row.get("elementId"));
                    assertEquals(normalized[rows], ((Number) row.get("initialX")).doubleValue(), 1.0e-6);
                    assertEquals(-normalized[rows], ((Number) row.get("initialY")).doubleValue(), 1.0e-6);
                    rows++;
                }
                assertEquals(4, rows);
                tx.commit();
            }
        }
    }

    @Test
    void statsAndEstimateReturnUsefulMetadata() {
        try (Neo4j neo4j = database()) {
            GraphDatabaseService db = neo4j.defaultDatabaseService();
            seedGraph(db);

            try (Transaction tx = db.beginTx()) {
                Result stats = tx.execute("""
                        CALL dire.layout.stats({
                          nodeQuery: 'MATCH (n:Paper) RETURN id(n) AS id',
                          relationshipQuery: 'MATCH (a:Paper)-[:CITES]->(b:Paper) RETURN id(a) AS source, id(b) AS target',
                          iterations: 1
                        })
                        YIELD nodeCount, relationshipsRead, storedRelationships, isolatedNodes
                        RETURN nodeCount, relationshipsRead, storedRelationships, isolatedNodes
                        """);
                Map<String, Object> statsRow = stats.next();
                assertEquals(4L, statsRow.get("nodeCount"));
                assertEquals(4L, statsRow.get("relationshipsRead"));
                assertEquals(8L, statsRow.get("storedRelationships"));
                assertEquals(0L, statsRow.get("isolatedNodes"));

                Result estimate = tx.execute("""
                        CALL dire.layout.estimate({
                          nodeQuery: 'MATCH (n:Paper) RETURN id(n) AS id',
                          relationshipQuery: 'MATCH (:Paper)-[:CITES]->(:Paper) RETURN 1 AS relationship'
                        })
                        YIELD nodeCount, relationshipCount, storedRelationshipCount, bytesMin, bytesMax
                        RETURN nodeCount, relationshipCount, storedRelationshipCount, bytesMin, bytesMax
                        """);
                Map<String, Object> estimateRow = estimate.next();
                assertEquals(4L, estimateRow.get("nodeCount"));
                assertEquals(4L, estimateRow.get("relationshipCount"));
                assertEquals(8L, estimateRow.get("storedRelationshipCount"));
                assertTrue(((Number) estimateRow.get("bytesMin")).longValue() > 0L);
                assertTrue(((Number) estimateRow.get("bytesMax")).longValue() >= ((Number) estimateRow.get("bytesMin")).longValue());
                tx.commit();
            }
        }
    }

    @Test
    void estimateReflectsWarmStartAndEmbeddingOptions() {
        try (Neo4j neo4j = database()) {
            GraphDatabaseService db = neo4j.defaultDatabaseService();

            try (Transaction tx = db.beginTx()) {
                long base = estimateBytes(tx, """
                        CALL dire.layout.estimate({
                          nodeCount: 100,
                          relationshipCount: 200
                        })
                        YIELD bytesMin
                        RETURN bytesMin
                        """);
                long warmStart = estimateBytes(tx, """
                        CALL dire.layout.estimate({
                          nodeCount: 100,
                          relationshipCount: 200,
                          initialization: 'warm_start'
                        })
                        YIELD bytesMin
                        RETURN bytesMin
                        """);
                long embeddings = estimateBytes(tx, """
                        CALL dire.layout.estimate({
                          nodeCount: 100,
                          relationshipCount: 200,
                          includeEmbedding: true
                        })
                        YIELD bytesMin
                        RETURN bytesMin
                        """);

                assertTrue(warmStart > base);
                assertTrue(embeddings > base);
                tx.commit();
            }
        }
    }

    @Test
    void fastKernelConfigDefaultsFalseAndParsesTrue() {
        DiReConfig defaults = DiReConfig.parse(Map.of(
                "nodeQuery", "MATCH (n) RETURN id(n) AS id",
                "relationshipQuery", "MATCH (a)-->(b) RETURN id(a) AS source, id(b) AS target"));
        DiReConfig enabled = DiReConfig.parse(Map.of(
                "nodeQuery", "MATCH (n) RETURN id(n) AS id",
                "relationshipQuery", "MATCH (a)-->(b) RETURN id(a) AS source, id(b) AS target",
                "fastKernel", true));

        assertFalse(defaults.layoutConfig.fastKernel());
        assertTrue(enabled.layoutConfig.fastKernel());
    }

    @Test
    void invalidConfigFailsClearly() {
        try (Neo4j neo4j = database()) {
            GraphDatabaseService db = neo4j.defaultDatabaseService();
            seedGraph(db);

            try (Transaction tx = db.beginTx()) {
                Result result = tx.execute("""
                        CALL dire.layout.stream({
                          nodeQuery: 'MATCH (n:Paper) RETURN id(n) AS wrong',
                          relationshipQuery: 'MATCH (a:Paper)-[:CITES]->(b:Paper) RETURN id(a) AS source, id(b) AS target'
                        })
                        YIELD nodeId
                        RETURN nodeId
                        """);
                assertFalse(result.hasNext());
            } catch (RuntimeException error) {
                assertTrue(error.getMessage().contains("nodeQuery must return column `id`"));
            }
        }
    }

    @Test
    void invalidIdentityValueFailsClearly() {
        try (Neo4j neo4j = database()) {
            GraphDatabaseService db = neo4j.defaultDatabaseService();
            seedGraph(db);

            try (Transaction tx = db.beginTx()) {
                Result result = tx.execute("""
                        CALL dire.layout.stream({
                          nodeQuery: 'MATCH (n:Paper) RETURN true AS id',
                          relationshipQuery: 'MATCH (a:Paper)-[:CITES]->(b:Paper) RETURN id(a) AS source, id(b) AS target'
                        })
                        YIELD nodeId
                        RETURN nodeId
                        """);
                assertFalse(result.hasNext());
            } catch (RuntimeException error) {
                assertTrue(error.getMessage().contains("numeric id or string elementId"), error.getMessage());
            }
        }
    }

    @Test
    void mixedIdentityModesFailClearly() {
        try (Neo4j neo4j = database()) {
            GraphDatabaseService db = neo4j.defaultDatabaseService();
            seedGraph(db);

            try (Transaction tx = db.beginTx()) {
                Result result = tx.execute("""
                        CALL dire.layout.stream({
                          nodeQuery: 'MATCH (n:Paper) RETURN elementId(n) AS id ORDER BY n.name',
                          relationshipQuery: 'MATCH (a:Paper)-[:CITES]->(b:Paper) RETURN id(a) AS source, elementId(b) AS target'
                        })
                        YIELD nodeId
                        RETURN nodeId
                        """);
                assertFalse(result.hasNext());
            } catch (RuntimeException error) {
                assertTrue(error.getMessage().contains("identity type must match"), error.getMessage());
            }
        }
    }

    @Test
    void maxProjectionBytesRejectsProjectionBeforeMaterialization() {
        try (Neo4j neo4j = database()) {
            GraphDatabaseService db = neo4j.defaultDatabaseService();
            seedGraph(db);

            try (Transaction tx = db.beginTx()) {
                RuntimeException error = assertThrows(RuntimeException.class, () -> {
                    Result result = tx.execute("""
                            CALL dire.layout.stream({
                              nodeQuery: 'MATCH (n:Paper) RETURN id(n) AS id',
                              relationshipQuery: 'MATCH (a:Paper)-[:CITES]->(b:Paper) RETURN id(a) AS source, id(b) AS target',
                              maxProjectionBytes: 1,
                              iterations: 1
                            })
                            YIELD nodeId
                            RETURN nodeId
                            """);
                    result.hasNext();
                });
                assertTrue(
                        error.getMessage().contains("maxProjectionBytes")
                                || error.getMessage().contains("projection memory"),
                        error.getMessage());
            }
        }
    }

    @Test
    void unmanagedViewerStreamsDefaultPayload() {
        try (Neo4j neo4j = database()) {
            GraphDatabaseService db = neo4j.defaultDatabaseService();
            seedViewerGraph(db);

            DiReViewResource resource = new DiReViewResource();
            resource.db = db;
            long nodesBefore = countAllNodes(db);
            long relationshipsBefore = countAllRelationships(db);
            Response response = resource.defaultData();
            String body = (String) response.getEntity();

            assertEquals(200, response.getStatus());
            assertTrue(body.contains("\"totalNodes\":2"));
            assertTrue(body.contains("\"totalEdges\":1"));
            assertTrue(body.contains("\"dire\""));
            assertTrue(body.contains("\"nodeQuery\""));
            assertEquals(nodesBefore, countAllNodes(db));
            assertEquals(relationshipsBefore, countAllRelationships(db));
        }
    }

    @Test
    void unmanagedViewerRejectsCustomQueryWithoutMutatingDatabase() {
        try (Neo4j neo4j = database()) {
            GraphDatabaseService db = neo4j.defaultDatabaseService();
            seedViewerGraph(db);

            DiReViewResource resource = new DiReViewResource();
            resource.db = db;
            Response response = resource.query(
                    "CREATE (:Pwned) RETURN 1 AS idx",
                    "MATCH (a)-[r]->(b) RETURN id(a) AS source, id(b) AS target");
            String body = (String) response.getEntity();

            assertEquals(403, response.getStatus());
            assertTrue(body.contains("CustomCypherDisabled"));
            assertEquals(0L, countPwnedNodes(db));
        }
    }

    @Test
    void unmanagedViewerServesScriptPayload() {
        DiReViewResource resource = new DiReViewResource();
        Response response = resource.script();
        String body = (String) response.getEntity();

        assertEquals(200, response.getStatus());
        assertTrue(body.contains("loadDefault"));
    }

    private static Neo4j database() {
        return Neo4jBuilders.newInProcessBuilder()
                .withDisabledServer()
                .withConfig(BoltConnector.enabled, false)
                .withProcedure(DiReProcedures.class)
                .build();
    }

    private static void seedGraph(GraphDatabaseService db) {
        try (Transaction tx = db.beginTx()) {
            tx.execute("""
                    CREATE (a:Paper {name: 'a'})
                    CREATE (b:Paper {name: 'b'})
                    CREATE (c:Paper {name: 'c'})
                    CREATE (d:Paper {name: 'd'})
                    CREATE (a)-[:CITES {weight: 2.0}]->(b)
                    CREATE (b)-[:CITES]->(c)
                    CREATE (c)-[:CITES]->(d)
                    CREATE (d)-[:CITES]->(a)
                    """);
            tx.commit();
        }
    }

    private static void seedViewerGraph(GraphDatabaseService db) {
        try (Transaction tx = db.beginTx()) {
            tx.execute("""
                    CREATE (a:Paper {idx: 0, name: 'A0', group: 'Algebra', dire_x: 0.0, dire_y: 0.0})
                    CREATE (b:Paper {idx: 1, name: 'T0', group: 'Topology', dire_x: 1.0, dire_y: 0.0})
                    CREATE (a)-[:CITES {weight: 1.0, kind: 'bridge'}]->(b)
                    CREATE (:EmbeddingRun {
                      key: 'dire',
                      name: 'DiRe',
                      description: 'test run',
                      rank: 0
                    })
                    CREATE (:DireView {name: 'Current DiRe View', run: 'dire'})
                    """);
            tx.commit();
        }
    }

    private static void seedWarmStart(GraphDatabaseService db) {
        try (Transaction tx = db.beginTx()) {
            tx.execute("""
                    MATCH (n:Paper)
                    SET n.warm_x = CASE n.name
                        WHEN 'a' THEN 1.0
                        WHEN 'b' THEN 2.0
                        WHEN 'c' THEN 3.0
                        ELSE 4.0
                    END,
                    n.warm_y = CASE n.name
                        WHEN 'a' THEN -1.0
                        WHEN 'b' THEN -2.0
                        WHEN 'c' THEN -3.0
                        ELSE -4.0
                    END
                    """);
            tx.commit();
        }
    }

    private static long estimateBytes(Transaction tx, String query) {
        Result result = tx.execute(query);
        return ((Number) result.next().get("bytesMin")).longValue();
    }

    private static long countPwnedNodes(GraphDatabaseService db) {
        try (Transaction tx = db.beginTx()) {
            Result result = tx.execute("MATCH (n:Pwned) RETURN count(n) AS count");
            long count = ((Number) result.next().get("count")).longValue();
            tx.commit();
            return count;
        }
    }

    private static long countNodesWithProperty(GraphDatabaseService db, String property) {
        try (Transaction tx = db.beginTx()) {
            Result result = tx.execute(
                    "MATCH (n:Paper) WHERE n[$property] IS NOT NULL RETURN count(n) AS count",
                    Map.of("property", property));
            long count = ((Number) result.next().get("count")).longValue();
            tx.commit();
            return count;
        }
    }

    private static long countAllNodes(GraphDatabaseService db) {
        return count(db, "MATCH (n) RETURN count(n) AS count");
    }

    private static long countAllRelationships(GraphDatabaseService db) {
        return count(db, "MATCH ()-[r]->() RETURN count(r) AS count");
    }

    private static long count(GraphDatabaseService db, String query) {
        try (Transaction tx = db.beginTx()) {
            long count = ((Number) tx.execute(query).next().get("count")).longValue();
            tx.commit();
            return count;
        }
    }
}
