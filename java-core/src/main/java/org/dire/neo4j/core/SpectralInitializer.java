package org.dire.neo4j.core;

import java.util.Arrays;

final class SpectralInitializer {
    private static final int CONVERGENCE_CHECK_INTERVAL = 8;

    private SpectralInitializer() {
    }

    static float[] initialize(
            CsrGraph graph,
            int dimensions,
            long seed,
            float tolerance,
            int minIterations,
            int maxIterations) {
        int n = graph.nodeCount();
        float[] positions = new float[n * dimensions];
        if (n == 1) {
            return positions;
        }

        int columns = dimensions + 1;
        float[] degree = weightedDegrees(graph);
        float[] q = initialBlock(n, columns, seed, degree);
        orthonormalize(q, n, columns);

        float[] y = new float[n * columns];
        for (int iter = 0; iter < maxIterations; iter++) {
            Arrays.fill(y, 0.0f);
            multiplyNormalizedAdjacency(graph, degree, q, y, columns);
            orthonormalize(y, n, columns);
            int completedIterations = iter + 1;
            boolean checkConvergence = completedIterations == minIterations
                    || (completedIterations > minIterations
                        && (completedIterations - minIterations) % CONVERGENCE_CHECK_INTERVAL == 0);
            boolean converged = tolerance > 0.0f
                    && checkConvergence
                    && subspaceDistance(q, y, n, columns) <= tolerance;
            float[] swap = q;
            q = y;
            y = swap;
            if (converged) {
                break;
            }
        }

        double[][] rayleigh = rayleigh(graph, degree, q, n, columns);
        SmallEigen eigen = SmallEigen.decompose(rayleigh);
        int[] order = eigen.descendingOrder();
        for (int dim = 0; dim < dimensions; dim++) {
            int eigenColumn = order[Math.min(dim + 1, order.length - 1)];
            for (int i = 0; i < n; i++) {
                double value = 0.0;
                for (int c = 0; c < columns; c++) {
                    value += q[i * columns + c] * eigen.vectors[c][eigenColumn];
                }
                positions[i * dimensions + dim] = (float) value;
            }
        }

        separateComponents(graph, positions, dimensions);
        normalize(positions, n, dimensions);
        return positions;
    }

    private static double subspaceDistance(float[] previous, float[] next, int rows, int columns) {
        double overlapSquared = 0.0;
        for (int a = 0; a < columns; a++) {
            for (int b = 0; b < columns; b++) {
                double dot = 0.0;
                for (int row = 0; row < rows; row++) {
                    dot += previous[row * columns + a] * next[row * columns + b];
                }
                overlapSquared += dot * dot;
            }
        }
        double residual = Math.max(0.0, columns - overlapSquared);
        return Math.sqrt(residual / columns);
    }

    static void normalize(float[] positions, int nodeCount, int dimensions) {
        if (nodeCount == 0) {
            return;
        }
        for (int dim = 0; dim < dimensions; dim++) {
            double mean = 0.0;
            for (int i = 0; i < nodeCount; i++) {
                mean += positions[i * dimensions + dim];
            }
            mean /= nodeCount;

            double variance = 0.0;
            for (int i = 0; i < nodeCount; i++) {
                double centered = positions[i * dimensions + dim] - mean;
                variance += centered * centered;
            }
            double std = Math.sqrt(Math.max(variance / nodeCount, 1.0e-12));
            for (int i = 0; i < nodeCount; i++) {
                positions[i * dimensions + dim] = (float) ((positions[i * dimensions + dim] - mean) / std);
            }
        }
    }

    private static float[] weightedDegrees(CsrGraph graph) {
        int n = graph.nodeCount();
        int[] offsets = graph.offsets();
        int[] targets = graph.targets();
        float[] weights = graph.weights();
        float[] degree = new float[n];
        for (int i = 0; i < n; i++) {
            float sum = 0.0f;
            for (int p = offsets[i]; p < offsets[i + 1]; p++) {
                if (targets[p] != i) {
                    sum += weights[p];
                }
            }
            degree[i] = sum;
        }
        return degree;
    }

    private static float[] initialBlock(int n, int columns, long seed, float[] degree) {
        float[] q = new float[n * columns];
        for (int i = 0; i < n; i++) {
            q[i * columns] = degree[i] > 0.0f ? (float) Math.sqrt(degree[i]) : 0.0f;
            for (int c = 1; c < columns; c++) {
                long key = seed ^ (0x9E3779B97F4A7C15L * (i + 1L)) ^ (0xBF58476D1CE4E5B9L * (c + 1L));
                q[i * columns + c] = SplitMix64.signedUnit(key);
            }
        }
        return q;
    }

    private static void multiplyNormalizedAdjacency(
            CsrGraph graph,
            float[] degree,
            float[] input,
            float[] output,
            int columns) {
        int n = graph.nodeCount();
        int[] offsets = graph.offsets();
        int[] targets = graph.targets();
        float[] weights = graph.weights();
        for (int i = 0; i < n; i++) {
            if (degree[i] <= 0.0f) {
                continue;
            }
            double sourceScale = 1.0 / Math.sqrt(degree[i]);
            for (int p = offsets[i]; p < offsets[i + 1]; p++) {
                int j = targets[p];
                if (degree[j] <= 0.0f || i == j) {
                    continue;
                }
                float scale = (float) (weights[p] * sourceScale / Math.sqrt(degree[j]));
                int outBase = i * columns;
                int inBase = j * columns;
                for (int c = 0; c < columns; c++) {
                    output[outBase + c] += scale * input[inBase + c];
                }
            }
        }
    }

    private static void orthonormalize(float[] block, int rows, int columns) {
        for (int c = 0; c < columns; c++) {
            for (int previous = 0; previous < c; previous++) {
                double dot = 0.0;
                for (int r = 0; r < rows; r++) {
                    dot += block[r * columns + c] * block[r * columns + previous];
                }
                for (int r = 0; r < rows; r++) {
                    block[r * columns + c] -= (float) (dot * block[r * columns + previous]);
                }
            }

            double norm = 0.0;
            for (int r = 0; r < rows; r++) {
                float value = block[r * columns + c];
                norm += value * value;
            }
            norm = Math.sqrt(norm);
            if (norm < 1.0e-8) {
                for (int r = 0; r < rows; r++) {
                    long key = 0xD1B54A32D192ED03L ^ (31L * r) ^ (911L * c);
                    block[r * columns + c] = SplitMix64.signedUnit(key);
                }
                c--;
                continue;
            }
            for (int r = 0; r < rows; r++) {
                block[r * columns + c] /= (float) norm;
            }
        }
    }

    private static double[][] rayleigh(CsrGraph graph, float[] degree, float[] q, int n, int columns) {
        float[] aq = new float[n * columns];
        multiplyNormalizedAdjacency(graph, degree, q, aq, columns);
        double[][] matrix = new double[columns][columns];
        for (int a = 0; a < columns; a++) {
            for (int b = 0; b < columns; b++) {
                double dot = 0.0;
                for (int i = 0; i < n; i++) {
                    dot += q[i * columns + a] * aq[i * columns + b];
                }
                matrix[a][b] = dot;
            }
        }
        return matrix;
    }

    private static void separateComponents(CsrGraph graph, float[] positions, int dimensions) {
        ComponentLabels labels = ComponentLabels.compute(graph);
        if (labels.componentCount <= 1) {
            return;
        }
        float radius = 2.5f * (float) Math.sqrt(labels.componentCount);
        for (int i = 0; i < graph.nodeCount(); i++) {
            int component = labels.labels[i];
            double angle = 2.0 * Math.PI * component / labels.componentCount;
            positions[i * dimensions] += radius * (float) Math.cos(angle);
            positions[i * dimensions + 1] += radius * (float) Math.sin(angle);
            if (dimensions == 3) {
                positions[i * dimensions + 2] += radius * (component - (labels.componentCount - 1) * 0.5f);
            }
        }
    }

    private static final class ComponentLabels {
        final int[] labels;
        final int componentCount;

        private ComponentLabels(int[] labels, int componentCount) {
            this.labels = labels;
            this.componentCount = componentCount;
        }

        static ComponentLabels compute(CsrGraph graph) {
            int n = graph.nodeCount();
            int[] labels = new int[n];
            Arrays.fill(labels, -1);
            int[] stack = new int[n];
            int component = 0;
            int[] offsets = graph.offsets();
            int[] targets = graph.targets();
            for (int start = 0; start < n; start++) {
                if (labels[start] != -1) {
                    continue;
                }
                int size = 0;
                stack[size++] = start;
                labels[start] = component;
                while (size > 0) {
                    int node = stack[--size];
                    for (int p = offsets[node]; p < offsets[node + 1]; p++) {
                        int target = targets[p];
                        if (labels[target] == -1) {
                            labels[target] = component;
                            stack[size++] = target;
                        }
                    }
                }
                component++;
            }
            return new ComponentLabels(labels, component);
        }
    }

    private static final class SmallEigen {
        final double[] values;
        final double[][] vectors;

        private SmallEigen(double[] values, double[][] vectors) {
            this.values = values;
            this.vectors = vectors;
        }

        static SmallEigen decompose(double[][] input) {
            int n = input.length;
            double[][] a = new double[n][n];
            double[][] v = new double[n][n];
            for (int i = 0; i < n; i++) {
                System.arraycopy(input[i], 0, a[i], 0, n);
                v[i][i] = 1.0;
            }

            for (int iter = 0; iter < 80; iter++) {
                int p = 0;
                int q = 1;
                double max = 0.0;
                for (int i = 0; i < n; i++) {
                    for (int j = i + 1; j < n; j++) {
                        double value = Math.abs(a[i][j]);
                        if (value > max) {
                            max = value;
                            p = i;
                            q = j;
                        }
                    }
                }
                if (max < 1.0e-12) {
                    break;
                }

                double theta = 0.5 * Math.atan2(2.0 * a[p][q], a[q][q] - a[p][p]);
                double c = Math.cos(theta);
                double s = Math.sin(theta);
                for (int k = 0; k < n; k++) {
                    double apk = a[p][k];
                    double aqk = a[q][k];
                    a[p][k] = c * apk - s * aqk;
                    a[q][k] = s * apk + c * aqk;
                }
                for (int k = 0; k < n; k++) {
                    double akp = a[k][p];
                    double akq = a[k][q];
                    a[k][p] = c * akp - s * akq;
                    a[k][q] = s * akp + c * akq;
                }
                for (int k = 0; k < n; k++) {
                    double vkp = v[k][p];
                    double vkq = v[k][q];
                    v[k][p] = c * vkp - s * vkq;
                    v[k][q] = s * vkp + c * vkq;
                }
            }

            double[] values = new double[n];
            for (int i = 0; i < n; i++) {
                values[i] = a[i][i];
            }
            return new SmallEigen(values, v);
        }

        int[] descendingOrder() {
            Integer[] boxed = new Integer[values.length];
            for (int i = 0; i < values.length; i++) {
                boxed[i] = i;
            }
            Arrays.sort(boxed, (a, b) -> Double.compare(values[b], values[a]));
            int[] order = new int[values.length];
            for (int i = 0; i < boxed.length; i++) {
                order[i] = boxed[i];
            }
            return order;
        }
    }
}
