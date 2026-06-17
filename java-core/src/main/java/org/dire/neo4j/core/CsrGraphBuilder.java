package org.dire.neo4j.core;

import java.util.Arrays;

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
        return fromRelationships(
                nodeIds,
                nodeIds.length,
                sources,
                targets,
                weights,
                sources.length,
                relationshipMode);
    }

    public static CsrGraph fromRelationships(
            long[] nodeIds,
            int nodeCount,
            long[] sources,
            long[] targets,
            float[] weights,
            int relationshipCount,
            RelationshipMode relationshipMode) {
        if (nodeIds == null || sources == null || targets == null) {
            throw new IllegalArgumentException("nodeIds, sources, and targets are required");
        }
        if (nodeCount < 0 || nodeCount > nodeIds.length) {
            throw new IllegalArgumentException("nodeCount must be within nodeIds length");
        }
        if (relationshipCount < 0 || relationshipCount > sources.length || relationshipCount > targets.length) {
            throw new IllegalArgumentException("relationshipCount must be within sources and targets length");
        }
        if (weights != null && relationshipCount > weights.length) {
            throw new IllegalArgumentException("weights length mismatch");
        }
        if (nodeCount == 0) {
            throw new IllegalArgumentException("nodeQuery returned no nodes");
        }

        LongIntMap indexByNodeId = new LongIntMap(nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            int previous = indexByNodeId.putIfAbsent(nodeIds[i], i);
            if (previous != LongIntMap.MISSING) {
                throw new IllegalArgumentException("nodeQuery returned duplicate id: " + nodeIds[i]);
            }
        }

        int[] counts = new int[nodeCount];
        int arcs = 0;
        for (int r = 0; r < relationshipCount; r++) {
            float weight = checkedWeightAt(weights, r);
            if (weight == 0.0f) {
                continue;
            }
            int source = lookup(indexByNodeId, sources[r], "source");
            int target = lookup(indexByNodeId, targets[r], "target");
            counts[source]++;
            arcs++;
            if (relationshipMode == RelationshipMode.UNDIRECTED && source != target) {
                counts[target]++;
                arcs++;
            }
        }
        if (arcs == 0) {
            throw new IllegalArgumentException("relationshipQuery returned no usable relationships");
        }

        int[] offsets = new int[nodeCount + 1];
        for (int i = 0; i < nodeCount; i++) {
            offsets[i + 1] = offsets[i] + counts[i];
        }

        int[] next = Arrays.copyOf(offsets, offsets.length);
        int[] csrTargets = new int[arcs];
        float[] csrWeights = new float[arcs];
        for (int r = 0; r < relationshipCount; r++) {
            float weight = checkedWeightAt(weights, r);
            if (weight == 0.0f) {
                continue;
            }

            int source = lookup(indexByNodeId, sources[r], "source");
            int target = lookup(indexByNodeId, targets[r], "target");
            int position = next[source]++;
            csrTargets[position] = target;
            csrWeights[position] = weight;

            if (relationshipMode == RelationshipMode.UNDIRECTED && source != target) {
                position = next[target]++;
                csrTargets[position] = source;
                csrWeights[position] = weight;
            }
        }

        return new CsrGraph(Arrays.copyOf(nodeIds, nodeCount), offsets, csrTargets, csrWeights, relationshipMode);
    }

    private static int lookup(LongIntMap indexByNodeId, long nodeId, String field) {
        int index = indexByNodeId.get(nodeId);
        if (index == LongIntMap.MISSING) {
            throw new IllegalArgumentException("relationshipQuery returned " + field + " id not present in nodeQuery: " + nodeId);
        }
        return index;
    }

    private static float checkedWeightAt(float[] weights, int index) {
        float weight = weights == null ? 1.0f : weights[index];
        if (!Float.isFinite(weight) || weight < 0.0f) {
            throw new IllegalArgumentException("relationship weight must be finite and non-negative");
        }
        return weight;
    }

    private static final class LongIntMap {
        static final int MISSING = -1;

        private final long[] keys;
        private final int[] values;
        private final boolean[] used;
        private final int mask;

        LongIntMap(int expectedSize) {
            int capacity = 1;
            int minimum = Math.max(16, expectedSize * 4);
            while (capacity < minimum) {
                capacity <<= 1;
            }
            keys = new long[capacity];
            values = new int[capacity];
            used = new boolean[capacity];
            mask = capacity - 1;
        }

        int putIfAbsent(long key, int value) {
            int slot = slot(key);
            while (used[slot]) {
                if (keys[slot] == key) {
                    return values[slot];
                }
                slot = (slot + 1) & mask;
            }
            used[slot] = true;
            keys[slot] = key;
            values[slot] = value;
            return MISSING;
        }

        int get(long key) {
            int slot = slot(key);
            while (used[slot]) {
                if (keys[slot] == key) {
                    return values[slot];
                }
                slot = (slot + 1) & mask;
            }
            return MISSING;
        }

        private int slot(long key) {
            long mixed = key;
            mixed ^= mixed >>> 33;
            mixed *= 0xff51afd7ed558ccdL;
            mixed ^= mixed >>> 33;
            mixed *= 0xc4ceb9fe1a85ec53L;
            mixed ^= mixed >>> 33;
            return (int) mixed & mask;
        }
    }
}
