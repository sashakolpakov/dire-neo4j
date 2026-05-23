package org.dire.neo4j.core;

public final class LayoutMetrics {
    private final double meanEdgeLength;
    private final double stress;

    LayoutMetrics(double meanEdgeLength, double stress) {
        this.meanEdgeLength = meanEdgeLength;
        this.stress = stress;
    }

    public double meanEdgeLength() {
        return meanEdgeLength;
    }

    public double stress() {
        return stress;
    }

    static LayoutMetrics compute(CsrGraph graph, float[] positions, int dimensions) {
        int[] offsets = graph.offsets();
        int[] targets = graph.targets();
        float[] weights = graph.weights();
        double lengthSum = 0.0;
        double stressSum = 0.0;
        int count = 0;
        for (int i = 0; i < graph.nodeCount(); i++) {
            for (int p = offsets[i]; p < offsets[i + 1]; p++) {
                int j = targets[p];
                if (i == j) {
                    continue;
                }
                double distSq = 0.0;
                int ib = i * dimensions;
                int jb = j * dimensions;
                for (int d = 0; d < dimensions; d++) {
                    double delta = positions[jb + d] - positions[ib + d];
                    distSq += delta * delta;
                }
                double distance = Math.sqrt(Math.max(distSq, 1.0e-12));
                double ideal = 1.0 / Math.sqrt(Math.max(weights[p], 1.0e-6f));
                double error = distance - ideal;
                lengthSum += distance;
                stressSum += weights[p] * error * error;
                count++;
            }
        }
        if (count == 0) {
            return new LayoutMetrics(Double.NaN, Double.NaN);
        }
        return new LayoutMetrics(lengthSum / count, stressSum / count);
    }
}
