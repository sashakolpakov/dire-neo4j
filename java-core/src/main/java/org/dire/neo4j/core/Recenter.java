package org.dire.neo4j.core;

/**
 * Subtracts the per-dimension mean from a packed coordinate array so the layout
 * stays centred at the origin after each iteration.
 *
 * <p>The reduction order is fixed (ascending node index per dimension) so the
 * double-precision mean and the {@code float} subtraction are bit-reproducible.
 * Any future parallel variant must preserve this reduction order to keep
 * {@code DiReLayoutTest.parallelLayoutMatchesSingleWorkerLayout} and the
 * seed-determinism tests passing (see DEVELOP.md "Determinism Contract").
 */
public final class Recenter {
    private Recenter() {
    }

    public static void apply(float[] positions, int nodeCount, int dimensions) {
        for (int dim = 0; dim < dimensions; dim++) {
            double mean = 0.0;
            for (int i = 0; i < nodeCount; i++) {
                mean += positions[i * dimensions + dim];
            }
            mean /= nodeCount;
            for (int i = 0; i < nodeCount; i++) {
                positions[i * dimensions + dim] -= (float) mean;
            }
        }
    }
}
