package org.dire.neo4j.core;

import java.util.Arrays;

public final class LayoutResult {
    private final long[] nodeIds;
    private final float[] initialPositions;
    private final float[] positions;
    private final int dimensions;
    private final int iterations;
    private final long milliseconds;
    private final LayoutMetrics metrics;

    LayoutResult(
            long[] nodeIds,
            float[] initialPositions,
            float[] positions,
            int dimensions,
            int iterations,
            long milliseconds,
            LayoutMetrics metrics) {
        this.nodeIds = nodeIds;
        this.initialPositions = initialPositions;
        this.positions = positions;
        this.dimensions = dimensions;
        this.iterations = iterations;
        this.milliseconds = milliseconds;
        this.metrics = metrics;
    }

    public int nodeCount() {
        return nodeIds.length;
    }

    public long nodeId(int index) {
        return nodeIds[index];
    }

    public float coordinate(int nodeIndex, int dimension) {
        return positions[nodeIndex * dimensions + dimension];
    }

    public float initialCoordinate(int nodeIndex, int dimension) {
        return initialPositions[nodeIndex * dimensions + dimension];
    }

    public float[] embeddingCopy(int nodeIndex) {
        float[] embedding = new float[dimensions];
        System.arraycopy(positions, nodeIndex * dimensions, embedding, 0, dimensions);
        return embedding;
    }

    public float[] initialEmbeddingCopy(int nodeIndex) {
        float[] embedding = new float[dimensions];
        System.arraycopy(initialPositions, nodeIndex * dimensions, embedding, 0, dimensions);
        return embedding;
    }

    public float[] positionsCopy() {
        return Arrays.copyOf(positions, positions.length);
    }

    public float[] initialPositionsCopy() {
        return Arrays.copyOf(initialPositions, initialPositions.length);
    }

    public int dimensions() {
        return dimensions;
    }

    public int iterations() {
        return iterations;
    }

    public long milliseconds() {
        return milliseconds;
    }

    public LayoutMetrics metrics() {
        return metrics;
    }
}
