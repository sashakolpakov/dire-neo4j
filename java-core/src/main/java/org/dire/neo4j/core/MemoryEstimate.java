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
        long storedEdges = relationshipMode == RelationshipMode.UNDIRECTED ? relationshipCount * 2L : relationshipCount;
        long nodeIds = nodeCount * Long.BYTES;
        long offsets = (nodeCount + 1L) * Integer.BYTES;
        long targets = storedEdges * Integer.BYTES;
        long weights = storedEdges * Float.BYTES;
        long positions = nodeCount * dimensions * Float.BYTES;
        long forces = positions;
        long spectralScratch = nodeCount * (dimensions + 1L) * Float.BYTES * 2L;
        long total = nodeIds + offsets + targets + weights + positions + forces + spectralScratch;
        return new MemoryEstimate(nodeCount, relationshipCount, storedEdges, total);
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
