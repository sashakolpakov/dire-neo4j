package org.dire.neo4j.plugin;

import org.dire.neo4j.core.CsrGraph;
import org.dire.neo4j.core.CsrGraphBuilder;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;

import java.util.Map;

final class GraphProjectionLoader {
    private GraphProjectionLoader() {
    }

    static GraphProjection load(Transaction tx, DireConfig config, boolean includeWarmStart) {
        PrimitiveLongList nodeIds = new PrimitiveLongList();
        try (Result result = tx.execute(config.nodeQuery, config.parameters)) {
            while (result.hasNext()) {
                Map<String, Object> row = result.next();
                nodeIds.add(requiredLong(row, "id", "nodeQuery"));
            }
        }

        PrimitiveLongList sources = new PrimitiveLongList();
        PrimitiveLongList targets = new PrimitiveLongList();
        PrimitiveFloatList weights = new PrimitiveFloatList();
        try (Result result = tx.execute(config.relationshipQuery, config.parameters)) {
            while (result.hasNext()) {
                Map<String, Object> row = result.next();
                sources.add(requiredLong(row, "source", "relationshipQuery"));
                targets.add(requiredLong(row, "target", "relationshipQuery"));
                weights.add(optionalFloat(row, "weight", 1.0f));
            }
        }

        long[] nodeIdArray = nodeIds.toArray();
        long[] sourceArray = sources.toArray();
        long[] targetArray = targets.toArray();
        float[] weightArray = weights.toArray();
        CsrGraph graph = CsrGraphBuilder.fromRelationships(
                nodeIdArray,
                sourceArray,
                targetArray,
                weightArray,
                config.layoutConfig.relationshipMode());
        float[] warmStart = includeWarmStart ? loadWarmStart(tx, nodeIdArray, config) : null;
        return new GraphProjection(graph, sourceArray.length, warmStart);
    }

    static long countRows(Transaction tx, String query, Map<String, Object> parameters) {
        long rows = 0L;
        try (Result result = tx.execute(query, parameters)) {
            while (result.hasNext()) {
                result.next();
                rows++;
            }
        }
        return rows;
    }

    private static float[] loadWarmStart(Transaction tx, long[] nodeIds, DireConfig config) {
        int dimensions = config.layoutConfig.dimensions();
        float[] coordinates = new float[nodeIds.length * dimensions];
        for (int i = 0; i < nodeIds.length; i++) {
            Node node = tx.getNodeById(nodeIds[i]);
            for (int dim = 0; dim < dimensions; dim++) {
                String property = config.warmStartProperties.get(dim);
                if (!node.hasProperty(property)) {
                    throw new IllegalArgumentException("warm start property missing on node " + nodeIds[i] + ": " + property);
                }
                Object value = node.getProperty(property);
                if (!(value instanceof Number number)) {
                    throw new IllegalArgumentException("warm start property must be numeric: " + property);
                }
                coordinates[i * dimensions + dim] = number.floatValue();
            }
        }
        return coordinates;
    }

    private static long requiredLong(Map<String, Object> row, String key, String queryName) {
        Object value = row.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(queryName + " must return numeric column `" + key + "`");
        }
        return number.longValue();
    }

    private static float optionalFloat(Map<String, Object> row, String key, float defaultValue) {
        Object value = row.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("relationship weight must be numeric");
        }
        return number.floatValue();
    }
}
