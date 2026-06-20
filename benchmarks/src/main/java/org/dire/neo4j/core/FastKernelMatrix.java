package org.dire.neo4j.core;

import org.dire.neo4j.benchmarks.SyntheticGraphFactory;

import java.util.Locale;

public final class FastKernelMatrix {
    private FastKernelMatrix() {
    }

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        int[] nodeCounts = new int[]{1_000, 10_000};
        int[] longRangeEdgesPerNodeValues = new int[]{1, 4};
        int[] iterationsValues = new int[]{3, 10};
        int[] concurrencyValues = new int[]{1, 4};
        float[][] kernelShapes = new float[][]{
                {0.01f, 1.0f},
                {0.2f, 1.0f},
                {1.0f, 0.5f}
        };

        System.out.println("nodeCount,longRangeEdgesPerNode,iterations,concurrency,minDist,spread,"
                + "kernelB,fastExponent,exactMillis,fastMillis,speedupPct,"
                + "initialRmsDelta,finalRmsDelta,maxFinalDelta,stressExact,stressFast,stressDeltaPct");

        for (int nodeCount : nodeCounts) {
            for (int longRangeEdgesPerNode : longRangeEdgesPerNodeValues) {
                SyntheticGraphFactory.Fixture fixture = SyntheticGraphFactory.ringWithLongRangeEdges(
                        nodeCount,
                        longRangeEdgesPerNode,
                        0xD1E_4A5EL);
                CsrGraph graph = SyntheticGraphFactory.buildUndirected(fixture);
                for (int iterations : iterationsValues) {
                    for (int concurrency : concurrencyValues) {
                        for (float[] shape : kernelShapes) {
                            float minDist = shape[0];
                            float spread = shape[1];
                            LayoutConfig exactConfig = LayoutConfig.builder()
                                    .randomSeed(42L)
                                    .minDist(minDist)
                                    .spread(spread)
                                    .negativeSamples(4)
                                    .iterations(iterations)
                                    .concurrency(concurrency)
                                    .fastKernel(false)
                                    .build();
                            LayoutConfig fastConfig = LayoutConfig.builder()
                                    .randomSeed(42L)
                                    .minDist(minDist)
                                    .spread(spread)
                                    .negativeSamples(4)
                                    .iterations(iterations)
                                    .concurrency(concurrency)
                                    .fastKernel(true)
                                    .build();

                            KernelParameters kernel = KernelParameters.fit(minDist, spread);
                            FastPower fastPower = FastPower.forExponent(kernel.b);

                            LayoutResult exactWarmup = new DiReLayout().run(graph, exactConfig);
                            LayoutResult fastWarmup = new DiReLayout().run(graph, fastConfig);
                            consume(exactWarmup, fastWarmup);

                            long exactStart = System.nanoTime();
                            LayoutResult exact = new DiReLayout().run(graph, exactConfig);
                            long exactElapsedNanos = System.nanoTime() - exactStart;

                            long fastStart = System.nanoTime();
                            LayoutResult fast = new DiReLayout().run(graph, fastConfig);
                            long fastElapsedNanos = System.nanoTime() - fastStart;

                            double exactMillis = exactElapsedNanos / 1_000_000.0;
                            double fastMillis = fastElapsedNanos / 1_000_000.0;
                            double speedupPct = exactMillis == 0.0 ? 0.0 : ((exactMillis - fastMillis) / exactMillis) * 100.0;
                            double initialRmsDelta = rmsDelta(exact.initialPositionsCopy(), fast.initialPositionsCopy());
                            double finalRmsDelta = rmsDelta(exact.positionsCopy(), fast.positionsCopy());
                            double maxFinalDelta = maxDelta(exact.positionsCopy(), fast.positionsCopy());
                            double stressExact = exact.metrics().stress();
                            double stressFast = fast.metrics().stress();
                            double stressDeltaPct = stressExact == 0.0 ? 0.0 : ((stressFast - stressExact) / stressExact) * 100.0;

                            System.out.printf(Locale.ROOT,
                                    "%d,%d,%d,%d,%.4f,%.4f,%.6f,%.6f,%.3f,%.3f,%.2f,%.6f,%.6f,%.6f,%.6f,%.6f,%.2f%n",
                                    nodeCount,
                                    longRangeEdgesPerNode,
                                    iterations,
                                    concurrency,
                                    minDist,
                                    spread,
                                    kernel.b,
                                    fastPower.approximateExponent(),
                                    exactMillis,
                                    fastMillis,
                                    speedupPct,
                                    initialRmsDelta,
                                    finalRmsDelta,
                                    maxFinalDelta,
                                    stressExact,
                                    stressFast,
                                    stressDeltaPct);
                        }
                    }
                }
            }
        }
    }

    private static double rmsDelta(float[] left, float[] right) {
        double sum = 0.0;
        for (int i = 0; i < left.length; i++) {
            double delta = left[i] - right[i];
            sum += delta * delta;
        }
        return Math.sqrt(sum / Math.max(1, left.length));
    }

    private static double maxDelta(float[] left, float[] right) {
        double max = 0.0;
        for (int i = 0; i < left.length; i++) {
            max = Math.max(max, Math.abs(left[i] - right[i]));
        }
        return max;
    }

    private static void consume(LayoutResult exact, LayoutResult fast) {
        if (exact.nodeCount() != fast.nodeCount()) {
            throw new IllegalStateException("warmup mismatch");
        }
    }
}
