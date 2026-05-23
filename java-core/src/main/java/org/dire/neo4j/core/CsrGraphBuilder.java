package org.dire.neo4j.core;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public final class CsrGraphBuilder {
    private CsrGraphBuilder() {
    }

    public static CsrGraph fromRelationships(
            long[] nodeIds,
            long[] sources,
            long[] targets,
            float[] weights,
            RelationshipMode relationshipMode) {
        if (nodeIds == null || sources == null || targets == null) {
            throw new IllegalArgumentException("nodeIds, sources, and targets are required");
        }
        if (sources.length != targets.length) {
            throw new IllegalArgumentException("sources and targets length mismatch");
        }
        if (weights != null && weights.length != sources.length) {
            throw new IllegalArgumentException("weights length mismatch");
        }
        if (nodeIds.length == 0) {
            throw new IllegalArgumentException("nodeQuery returned no nodes");
        }

        Map<Long, Integer> indexByNodeId = new HashMap<>(Math.max(16, nodeIds.length * 2));
        for (int i = 0; i < nodeIds.length; i++) {
            Integer previous = indexByNodeId.put(nodeIds[i], i);
            if (previous != null) {
                throw new IllegalArgumentException("nodeQuery returned duplicate id: " + nodeIds[i]);
            }
        }

        int arcs = 0;
        for (int r = 0; r < sources.length; r++) {
            if (weightAt(weights, r) == 0.0f) {
                continue;
            }
            arcs++;
            if (relationshipMode == RelationshipMode.UNDIRECTED && sources[r] != targets[r]) {
                arcs++;
            }
        }
        if (arcs == 0) {
            throw new IllegalArgumentException("relationshipQuery returned no usable relationships");
        }

        int[] sourceIndex = new int[arcs];
        int[] targetIndex = new int[arcs];
        float[] arcWeights = new float[arcs];
        int cursor = 0;
        for (int r = 0; r < sources.length; r++) {
            float weight = weightAt(weights, r);
            if (!Float.isFinite(weight) || weight < 0.0f) {
                throw new IllegalArgumentException("relationship weight must be finite and non-negative");
            }
            if (weight == 0.0f) {
                continue;
            }

            int source = lookup(indexByNodeId, sources[r], "source");
            int target = lookup(indexByNodeId, targets[r], "target");
            sourceIndex[cursor] = source;
            targetIndex[cursor] = target;
            arcWeights[cursor] = weight;
            cursor++;

            if (relationshipMode == RelationshipMode.UNDIRECTED && source != target) {
                sourceIndex[cursor] = target;
                targetIndex[cursor] = source;
                arcWeights[cursor] = weight;
                cursor++;
            }
        }

        return buildCsr(Arrays.copyOf(nodeIds, nodeIds.length), sourceIndex, targetIndex, arcWeights, relationshipMode);
    }

    private static CsrGraph buildCsr(
            long[] nodeIds,
            int[] sourceIndex,
            int[] targetIndex,
            float[] weights,
            RelationshipMode relationshipMode) {
        int nodeCount = nodeIds.length;
        int[] counts = new int[nodeCount];
        for (int source : sourceIndex) {
            counts[source]++;
        }

        int[] offsets = new int[nodeCount + 1];
        for (int i = 0; i < nodeCount; i++) {
            offsets[i + 1] = offsets[i] + counts[i];
        }

        int[] next = Arrays.copyOf(offsets, offsets.length);
        int[] targets = new int[targetIndex.length];
        float[] csrWeights = new float[weights.length];
        for (int e = 0; e < sourceIndex.length; e++) {
            int source = sourceIndex[e];
            int position = next[source]++;
            targets[position] = targetIndex[e];
            csrWeights[position] = weights[e];
        }

        return new CsrGraph(nodeIds, offsets, targets, csrWeights, relationshipMode);
    }

    private static int lookup(Map<Long, Integer> indexByNodeId, long nodeId, String field) {
        Integer index = indexByNodeId.get(nodeId);
        if (index == null) {
            throw new IllegalArgumentException("relationshipQuery returned " + field + " id not present in nodeQuery: " + nodeId);
        }
        return index;
    }

    private static float weightAt(float[] weights, int index) {
        return weights == null ? 1.0f : weights[index];
    }
}
