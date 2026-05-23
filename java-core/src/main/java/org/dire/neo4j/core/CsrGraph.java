package org.dire.neo4j.core;

import java.util.Arrays;

public final class CsrGraph {
    private final long[] nodeIds;
    private final int[] offsets;
    private final int[] targets;
    private final float[] weights;
    private final RelationshipMode relationshipMode;

    CsrGraph(
            long[] nodeIds,
            int[] offsets,
            int[] targets,
            float[] weights,
            RelationshipMode relationshipMode) {
        this.nodeIds = nodeIds;
        this.offsets = offsets;
        this.targets = targets;
        this.weights = weights;
        this.relationshipMode = relationshipMode;
        validate();
    }

    public int nodeCount() {
        return nodeIds.length;
    }

    public int storedEdgeCount() {
        return targets.length;
    }

    public long nodeId(int index) {
        return nodeIds[index];
    }

    public long[] nodeIdsCopy() {
        return Arrays.copyOf(nodeIds, nodeIds.length);
    }

    public int[] offsets() {
        return offsets;
    }

    public int[] targets() {
        return targets;
    }

    public float[] weights() {
        return weights;
    }

    public RelationshipMode relationshipMode() {
        return relationshipMode;
    }

    public int degree(int index) {
        return offsets[index + 1] - offsets[index];
    }

    public int isolatedNodeCount() {
        int count = 0;
        for (int i = 0; i < nodeIds.length; i++) {
            if (degree(i) == 0) {
                count++;
            }
        }
        return count;
    }

    private void validate() {
        if (offsets.length != nodeIds.length + 1) {
            throw new IllegalArgumentException("offsets length must be nodeCount + 1");
        }
        if (weights.length != targets.length) {
            throw new IllegalArgumentException("weights length must match targets length");
        }
        if (offsets[0] != 0 || offsets[offsets.length - 1] != targets.length) {
            throw new IllegalArgumentException("CSR offsets do not cover targets");
        }
        for (int i = 0; i < offsets.length - 1; i++) {
            if (offsets[i] > offsets[i + 1]) {
                throw new IllegalArgumentException("CSR offsets must be non-decreasing");
            }
        }
        for (int target : targets) {
            if (target < 0 || target >= nodeIds.length) {
                throw new IllegalArgumentException("CSR target out of bounds: " + target);
            }
        }
        for (float weight : weights) {
            if (!Float.isFinite(weight) || weight < 0.0f) {
                throw new IllegalArgumentException("CSR weights must be finite and non-negative");
            }
        }
    }
}
