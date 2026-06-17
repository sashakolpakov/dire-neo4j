package org.dire.neo4j.core;

public final class MemoryEstimate {
    private final long nodeCount;
    private final long relationshipCount;
    private final long storedEdgeCount;
    private final long bytes;

    private MemoryEstimate(long nodeCount, long relationshipCount, long storedEdgeCount, long bytes) {
        this.nodeCount = nodeCount;
        this.relationshipCount = relationshipCount;
        this.storedEdgeCount = storedEdgeCount;
        this.bytes = bytes;
    }

    public static MemoryEstimate estimate(long nodeCount, long relationshipCount, int dimensions, RelationshipMode relationshipMode) {
        return estimate(nodeCount, relationshipCount, dimensions, relationshipMode, false, false);
    }

    public static MemoryEstimate estimate(
            long nodeCount,
            long relationshipCount,
            int dimensions,
            RelationshipMode relationshipMode,
            boolean includeWarmStart,
            boolean includeEmbedding) {
        long storedEdges = relationshipMode == RelationshipMode.UNDIRECTED ? relationshipCount * 2L : relationshipCount;
        long loaderNodeIds = listCapacity(nodeCount) * Long.BYTES;
        long loaderSources = listCapacity(relationshipCount) * Long.BYTES;
        long loaderTargets = loaderSources;
        long loaderWeights = listCapacity(relationshipCount) * Float.BYTES;
        long nodeIds = nodeCount * Long.BYTES;
        long nodeIndexCapacity = primitiveMapCapacity(nodeCount);
        long nodeIndex = nodeIndexCapacity * (Long.BYTES + Integer.BYTES + 1L);
        long offsets = (nodeCount + 1L) * Integer.BYTES;
        long targets = storedEdges * Integer.BYTES;
        long weights = storedEdges * Float.BYTES;
        long positions = nodeCount * dimensions * Float.BYTES;
        long forces = positions;
        long spectralScratch = nodeCount * (dimensions + 1L) * Float.BYTES * 2L;
        long warmStart = includeWarmStart ? positions : 0L;
        long streamEmbeddings = includeEmbedding
                ? nodeCount * dimensions * 2L * (Double.BYTES + Long.BYTES)
                : 0L;
        long total = loaderNodeIds
                + loaderSources
                + loaderTargets
                + loaderWeights
                + nodeIds
                + nodeIndex
                + offsets
                + targets
                + weights
                + positions
                + forces
                + spectralScratch
                + warmStart
                + streamEmbeddings;
        return new MemoryEstimate(nodeCount, relationshipCount, storedEdges, total);
    }

    private static long listCapacity(long size) {
        long capacity = 1024L;
        while (capacity < size && capacity < (Long.MAX_VALUE / 2L)) {
            capacity *= 2L;
        }
        return Math.max(capacity, size);
    }

    private static long primitiveMapCapacity(long expectedSize) {
        long capacity = 1L;
        long minimum = expectedSize > Long.MAX_VALUE / 4L
                ? Long.MAX_VALUE
                : Math.max(16L, expectedSize * 4L);
        while (capacity < minimum && capacity < (Long.MAX_VALUE / 2L)) {
            capacity *= 2L;
        }
        return Math.max(capacity, minimum);
    }

    public long nodeCount() {
        return nodeCount;
    }

    public long relationshipCount() {
        return relationshipCount;
    }

    public long storedEdgeCount() {
        return storedEdgeCount;
    }

    public long bytes() {
        return bytes;
    }
}
