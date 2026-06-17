package org.dire.neo4j.plugin;

import org.dire.neo4j.core.CsrGraph;
import org.dire.neo4j.core.CsrGraphBuilder;
import org.dire.neo4j.core.MemoryEstimate;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class GraphProjectionLoader {
    private GraphProjectionLoader() {
    }

    static GraphProjection load(Transaction tx, DireConfig config, boolean includeWarmStart) {
        enforceProjectionMemoryLimit(tx, config, includeWarmStart);

        NodeIdentities nodeIdentities = loadNodeIdentities(tx, config);

        PrimitiveLongList sources = new PrimitiveLongList();
        PrimitiveLongList targets = new PrimitiveLongList();
        PrimitiveFloatList weights = new PrimitiveFloatList();
        try (Result result = tx.execute(config.relationshipQuery, config.parameters)) {
            while (result.hasNext()) {
                Map<String, Object> row = result.next();
                sources.add(requiredEndpoint(row, "source", nodeIdentities));
                targets.add(requiredEndpoint(row, "target", nodeIdentities));
                weights.add(optionalFloat(row, "weight", 1.0f));
            }
        }

        CsrGraph graph = CsrGraphBuilder.fromRelationships(
                nodeIdentities.nodeIds,
                nodeIdentities.nodeIds.length,
                sources.values(),
                targets.values(),
                weights.values(),
                sources.size(),
                config.layoutConfig.relationshipMode());
        float[] warmStart = includeWarmStart ? loadWarmStart(tx, nodeIdentities, config) : null;
        return new GraphProjection(graph, sources.size(), warmStart, nodeIdentities.elementIds);
    }

    private static NodeIdentities loadNodeIdentities(Transaction tx, DireConfig config) {
        PrimitiveLongList numericIds = new PrimitiveLongList();
        List<String> elementIds = new ArrayList<>();
        Map<String, Long> surrogateByElementId = new HashMap<>();
        IdentityMode mode = null;

        try (Result result = tx.execute(config.nodeQuery, config.parameters)) {
            while (result.hasNext()) {
                Map<String, Object> row = result.next();
                Object value = requiredValue(row, "id", "nodeQuery");
                IdentityMode rowMode = identityMode(value, "nodeQuery", "id");
                mode = requireConsistentMode(mode, rowMode, "nodeQuery");

                if (rowMode == IdentityMode.NUMERIC) {
                    numericIds.add(((Number) value).longValue());
                } else {
                    String elementId = (String) value;
                    long surrogateId = elementIds.size();
                    Long previous = surrogateByElementId.put(elementId, surrogateId);
                    if (previous != null) {
                        throw new IllegalArgumentException("nodeQuery returned duplicate elementId: " + elementId);
                    }
                    elementIds.add(elementId);
                }
            }
        }

        if (mode == IdentityMode.ELEMENT_ID) {
            long[] nodeIds = new long[elementIds.size()];
            for (int i = 0; i < nodeIds.length; i++) {
                nodeIds[i] = i;
            }
            return new NodeIdentities(nodeIds, elementIds.toArray(String[]::new), surrogateByElementId, IdentityMode.ELEMENT_ID);
        }
        return new NodeIdentities(numericIds.toArray(), null, null, IdentityMode.NUMERIC);
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

    private static void enforceProjectionMemoryLimit(Transaction tx, DireConfig config, boolean includeWarmStart) {
        if (config.maxProjectionBytes == null) {
            return;
        }

        long nodeCount = countRows(tx, config.nodeQuery, config.parameters);
        long relationshipCount = countRows(tx, config.relationshipQuery, config.parameters);
        MemoryEstimate estimate = MemoryEstimate.estimate(
                nodeCount,
                relationshipCount,
                config.layoutConfig.dimensions(),
                config.layoutConfig.relationshipMode(),
                includeWarmStart,
                config.includeEmbedding);
        long paddedBytes = (long) Math.ceil(estimate.bytes() * 1.20);
        long effectiveCap = Math.min(config.maxProjectionBytes, Runtime.getRuntime().maxMemory());
        if (paddedBytes > effectiveCap) {
            throw new IllegalArgumentException(
                    "projection memory estimate " + paddedBytes
                            + " bytes exceeds maxProjectionBytes/effective heap cap " + effectiveCap
                            + " bytes");
        }
    }

    private static float[] loadWarmStart(Transaction tx, NodeIdentities nodeIdentities, DireConfig config) {
        int dimensions = config.layoutConfig.dimensions();
        long[] nodeIds = nodeIdentities.nodeIds;
        float[] coordinates = new float[nodeIds.length * dimensions];
        for (int i = 0; i < nodeIds.length; i++) {
            Node node = nodeIdentities.mode == IdentityMode.ELEMENT_ID
                    ? tx.getNodeByElementId(nodeIdentities.elementIds[i])
                    : tx.getNodeById(nodeIds[i]);
            for (int dim = 0; dim < dimensions; dim++) {
                String property = config.warmStartProperties.get(dim);
                if (!node.hasProperty(property)) {
                    throw new IllegalArgumentException("warm start property missing on node " + nodeIdentityForMessage(nodeIdentities, i) + ": " + property);
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

    private static Object requiredValue(Map<String, Object> row, String key, String queryName) {
        Object value = row.get(key);
        if (value == null) {
            throw new IllegalArgumentException(queryName + " must return column `" + key + "`");
        }
        return value;
    }

    private static long requiredEndpoint(Map<String, Object> row, String key, NodeIdentities nodeIdentities) {
        Object value = requiredValue(row, key, "relationshipQuery");
        IdentityMode endpointMode = identityMode(value, "relationshipQuery", key);
        if (endpointMode != nodeIdentities.mode) {
            throw new IllegalArgumentException("relationshipQuery `" + key + "` identity type must match nodeQuery identity type");
        }
        if (endpointMode == IdentityMode.NUMERIC) {
            return ((Number) value).longValue();
        }

        Long surrogateId = nodeIdentities.surrogateByElementId.get((String) value);
        if (surrogateId == null) {
            throw new IllegalArgumentException("relationshipQuery returned " + key + " elementId not present in nodeQuery: " + value);
        }
        return surrogateId;
    }

    private static IdentityMode identityMode(Object value, String queryName, String key) {
        if (value instanceof Number) {
            return IdentityMode.NUMERIC;
        }
        if (value instanceof String text && !text.isBlank()) {
            return IdentityMode.ELEMENT_ID;
        }
        throw new IllegalArgumentException(queryName + " must return numeric id or string elementId column `" + key + "`");
    }

    private static IdentityMode requireConsistentMode(IdentityMode current, IdentityMode next, String queryName) {
        if (current == null || current == next) {
            return next;
        }
        throw new IllegalArgumentException(queryName + " must not mix numeric ids and string elementIds");
    }

    private static String nodeIdentityForMessage(NodeIdentities nodeIdentities, int index) {
        if (nodeIdentities.mode == IdentityMode.ELEMENT_ID) {
            return "elementId " + nodeIdentities.elementIds[index];
        }
        return Long.toString(nodeIdentities.nodeIds[index]);
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

    private enum IdentityMode {
        NUMERIC,
        ELEMENT_ID
    }

    private record NodeIdentities(
            long[] nodeIds,
            String[] elementIds,
            Map<String, Long> surrogateByElementId,
            IdentityMode mode) {
    }
}
