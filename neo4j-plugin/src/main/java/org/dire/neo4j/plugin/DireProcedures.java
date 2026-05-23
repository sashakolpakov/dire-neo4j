package org.dire.neo4j.plugin;

import org.dire.neo4j.core.DiReLayout;
import org.dire.neo4j.core.InitializationMode;
import org.dire.neo4j.core.LayoutResult;
import org.dire.neo4j.core.MemoryEstimate;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class DireProcedures {
    @Context
    public Transaction tx;

    @Procedure(name = "dire.layout.stream", mode = Mode.READ)
    @Description("Run a DiRe graph layout and stream node coordinates without writing them.")
    public Stream<StreamResult> stream(@Name("config") Map<String, Object> rawConfig) {
        DireConfig config = DireConfig.parse(rawConfig);
        GraphProjection projection = GraphProjectionLoader.load(tx, config, needsWarmStart(config));
        LayoutResult layout = new DiReLayout().run(projection.graph, config.layoutConfig, projection.warmStart);
        List<StreamResult> rows = new ArrayList<>(layout.nodeCount());
        for (int i = 0; i < layout.nodeCount(); i++) {
            rows.add(StreamResult.from(layout, i));
        }
        return rows.stream();
    }

    @Procedure(name = "dire.layout.write", mode = Mode.WRITE)
    @Description("Run a DiRe graph layout and write coordinates back to Neo4j node properties.")
    public Stream<WriteResult> write(@Name("config") Map<String, Object> rawConfig) {
        DireConfig config = DireConfig.parse(rawConfig);
        GraphProjection projection = GraphProjectionLoader.load(tx, config, needsWarmStart(config));
        LayoutResult layout = new DiReLayout().run(projection.graph, config.layoutConfig, projection.warmStart);

        int nodesWritten = 0;
        int dimensions = layout.dimensions();
        for (int i = 0; i < layout.nodeCount(); i++) {
            Node node = tx.getNodeById(layout.nodeId(i));
            for (int dim = 0; dim < dimensions; dim++) {
                node.setProperty(config.writeProperties.get(dim), (double) layout.coordinate(i, dim));
            }
            if (!config.writeInitialProperties.isEmpty()) {
                for (int dim = 0; dim < dimensions; dim++) {
                    node.setProperty(config.writeInitialProperties.get(dim), (double) layout.initialCoordinate(i, dim));
                }
            }
            nodesWritten++;
        }

        WriteResult result = new WriteResult();
        result.nodesWritten = nodesWritten;
        result.relationshipsRead = projection.relationshipsRead;
        result.iterations = layout.iterations();
        result.milliseconds = layout.milliseconds();
        result.meanEdgeLength = layout.metrics().meanEdgeLength();
        result.stress = layout.metrics().stress();
        return Stream.of(result);
    }

    @Procedure(name = "dire.layout.stats", mode = Mode.READ)
    @Description("Run a DiRe graph layout and return runtime and quality statistics without writing.")
    public Stream<StatsResult> stats(@Name("config") Map<String, Object> rawConfig) {
        DireConfig config = DireConfig.parse(rawConfig);
        GraphProjection projection = GraphProjectionLoader.load(tx, config, needsWarmStart(config));
        LayoutResult layout = new DiReLayout().run(projection.graph, config.layoutConfig, projection.warmStart);

        StatsResult result = new StatsResult();
        result.nodeCount = layout.nodeCount();
        result.relationshipsRead = projection.relationshipsRead;
        result.storedRelationships = projection.graph.storedEdgeCount();
        result.isolatedNodes = projection.graph.isolatedNodeCount();
        result.iterations = layout.iterations();
        result.milliseconds = layout.milliseconds();
        result.meanEdgeLength = layout.metrics().meanEdgeLength();
        result.stress = layout.metrics().stress();
        return Stream.of(result);
    }

    @Procedure(name = "dire.layout.estimate", mode = Mode.READ)
    @Description("Estimate heap memory for a DiRe graph layout projection and layout run.")
    public Stream<EstimateResult> estimate(@Name("config") Map<String, Object> rawConfig) {
        DireConfig.EstimateInput input = DireConfig.parseEstimate(rawConfig);
        long nodeCount = input.nodeCount() != null
                ? input.nodeCount()
                : countRequiredQuery(input.nodeQuery(), input.parameters(), "nodeQuery");
        long relationshipCount = input.relationshipCount() != null
                ? input.relationshipCount()
                : countRequiredQuery(input.relationshipQuery(), input.parameters(), "relationshipQuery");
        MemoryEstimate estimate = MemoryEstimate.estimate(
                nodeCount,
                relationshipCount,
                input.dimensions(),
                input.relationshipMode());

        EstimateResult result = new EstimateResult();
        result.nodeCount = estimate.nodeCount();
        result.relationshipCount = estimate.relationshipCount();
        result.storedRelationshipCount = estimate.storedEdgeCount();
        result.bytesMin = estimate.bytes();
        result.bytesMax = (long) Math.ceil(estimate.bytes() * 1.20);
        return Stream.of(result);
    }

    private long countRequiredQuery(String query, Map<String, Object> parameters, String key) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException(key + " or explicit count is required");
        }
        return GraphProjectionLoader.countRows(tx, query, parameters);
    }

    private static boolean needsWarmStart(DireConfig config) {
        return config.layoutConfig.initializationMode() == InitializationMode.WARM_START;
    }

    public static final class StreamResult {
        public long nodeId;
        public double x;
        public double y;
        public Double z;
        public double initialX;
        public double initialY;
        public Double initialZ;
        public List<Double> embedding;
        public List<Double> initialEmbedding;

        static StreamResult from(LayoutResult layout, int index) {
            StreamResult result = new StreamResult();
            result.nodeId = layout.nodeId(index);
            result.x = layout.coordinate(index, 0);
            result.y = layout.coordinate(index, 1);
            result.z = layout.dimensions() == 3 ? (double) layout.coordinate(index, 2) : null;
            result.initialX = layout.initialCoordinate(index, 0);
            result.initialY = layout.initialCoordinate(index, 1);
            result.initialZ = layout.dimensions() == 3 ? (double) layout.initialCoordinate(index, 2) : null;
            float[] embedding = layout.embeddingCopy(index);
            result.embedding = new ArrayList<>(embedding.length);
            for (float value : embedding) {
                result.embedding.add((double) value);
            }
            float[] initialEmbedding = layout.initialEmbeddingCopy(index);
            result.initialEmbedding = new ArrayList<>(initialEmbedding.length);
            for (float value : initialEmbedding) {
                result.initialEmbedding.add((double) value);
            }
            return result;
        }
    }

    public static final class WriteResult {
        public long nodesWritten;
        public long relationshipsRead;
        public long iterations;
        public long milliseconds;
        public double meanEdgeLength;
        public double stress;
    }

    public static final class StatsResult {
        public long nodeCount;
        public long relationshipsRead;
        public long storedRelationships;
        public long isolatedNodes;
        public long iterations;
        public long milliseconds;
        public double meanEdgeLength;
        public double stress;
    }

    public static final class EstimateResult {
        public long nodeCount;
        public long relationshipCount;
        public long storedRelationshipCount;
        public long bytesMin;
        public long bytesMax;
    }

}
