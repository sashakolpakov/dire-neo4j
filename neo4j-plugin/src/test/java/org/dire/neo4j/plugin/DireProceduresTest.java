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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DireProceduresTest {
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
                          randomSeed: 42
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
                assertTrue(error.getMessage().contains("nodeQuery must return numeric column `id`"));
            }
        }
    }

    @Test
    void unmanagedViewerStreamsDefaultPayload() {
        try (Neo4j neo4j = database()) {
            GraphDatabaseService db = neo4j.defaultDatabaseService();
            seedViewerGraph(db);

            DireViewResource resource = new DireViewResource();
            resource.db = db;
            Response response = resource.defaultData();
            String body = (String) response.getEntity();

            assertEquals(200, response.getStatus());
            assertTrue(body.contains("\"totalNodes\":2"));
            assertTrue(body.contains("\"totalEdges\":1"));
            assertTrue(body.contains("\"dire\""));
            assertTrue(body.contains("\"nodeQuery\""));
        }
    }

    @Test
    void unmanagedViewerServesScriptPayload() {
        DireViewResource resource = new DireViewResource();
        Response response = resource.script();
        String body = (String) response.getEntity();

        assertEquals(200, response.getStatus());
        assertTrue(body.contains("loadDefault"));
    }

    private static Neo4j database() {
        return Neo4jBuilders.newInProcessBuilder()
                .withDisabledServer()
                .withConfig(BoltConnector.enabled, false)
                .withProcedure(DireProcedures.class)
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
}
