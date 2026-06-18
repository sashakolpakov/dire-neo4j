package org.dire.neo4j.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class DiReLayout {
    private static final int PARALLEL_NODE_THRESHOLD = 64;
    private static final ExecutorService SHARED_EXECUTOR = createSharedExecutor();

    private final ExecutorService executor;

    public DiReLayout() {
        this(SHARED_EXECUTOR);
    }

    /**
     * Creates a layout runner using an externally owned executor.
     *
     * <p>The executor is never shut down by {@link #run}; its lifecycle remains
     * with the caller.</p>
     */
    public DiReLayout(ExecutorService executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public LayoutResult run(CsrGraph graph, LayoutConfig config) {
        return run(graph, config, null);
    }

    public LayoutResult run(CsrGraph graph, LayoutConfig config, float[] warmStart) {
        long startNanos = System.nanoTime();
        if (graph.nodeCount() == 0) {
            throw new IllegalArgumentException("graph must contain at least one node");
        }
        if (graph.storedEdgeCount() == 0) {
            throw new IllegalArgumentException("graph must contain at least one relationship");
        }

        int n = graph.nodeCount();
        int dimensions = config.dimensions();
        float[] positions = initialize(graph, config, warmStart);
        float[] initialPositions = Arrays.copyOf(positions, positions.length);
        float[] forces = new float[n * dimensions];
        KernelParameters kernel = KernelParameters.fit(config.minDist(), config.spread());
        boolean fastKernel = config.fastKernel() && kernel.isNearLinearExponent();
        int workers = workerCount(config, n);

        for (int iteration = 0; iteration < config.iterations(); iteration++) {
            Arrays.fill(forces, 0.0f);
            if (workers == 1) {
                accumulateAttraction(graph, positions, forces, dimensions, kernel, config, fastKernel);
                accumulateRepulsion(positions, forces, n, dimensions, iteration, kernel, config, fastKernel);
            } else {
                accumulateAttractionParallel(executor, workers, graph, positions, forces, dimensions, kernel, config, fastKernel);
                accumulateRepulsionParallel(executor, workers, positions, forces, n, dimensions, iteration, kernel, config, fastKernel);
            }
            clampForces(forces, config.cutoff());
            float alpha = config.learningRate() * (1.0f - (iteration / (float) Math.max(1, config.iterations())));
            for (int i = 0; i < forces.length; i++) {
                positions[i] += alpha * forces[i];
            }
            Recenter.apply(positions, n, dimensions);
        }

        SpectralInitializer.normalize(positions, n, dimensions);
        LayoutMetrics metrics = LayoutMetrics.compute(graph, positions, dimensions);
        long elapsed = (System.nanoTime() - startNanos) / 1_000_000L;
        return new LayoutResult(graph.nodeIdsCopy(), initialPositions, positions, dimensions, config.iterations(), elapsed, metrics);
    }

    private static float[] initialize(CsrGraph graph, LayoutConfig config, float[] warmStart) {
        int n = graph.nodeCount();
        int dimensions = config.dimensions();
        if (config.initializationMode() == InitializationMode.WARM_START) {
            if (warmStart == null) {
                throw new IllegalArgumentException("warm start initialization requires initial coordinates");
            }
            if (warmStart.length != n * dimensions) {
                throw new IllegalArgumentException("warm start coordinate length does not match graph dimensions");
            }
            float[] copy = Arrays.copyOf(warmStart, warmStart.length);
            for (float value : copy) {
                if (!Float.isFinite(value)) {
                    throw new IllegalArgumentException("warm start coordinates must be finite");
                }
            }
            SpectralInitializer.normalize(copy, n, dimensions);
            return copy;
        }
        if (config.initializationMode() == InitializationMode.RANDOM) {
            float[] positions = new float[n * dimensions];
            for (int i = 0; i < positions.length; i++) {
                positions[i] = SplitMix64.signedUnit(config.randomSeed() + i * 0x9E3779B97F4A7C15L);
            }
            SpectralInitializer.normalize(positions, n, dimensions);
            return positions;
        }
        return SpectralInitializer.initialize(graph, dimensions, config.randomSeed());
    }

    private static void accumulateAttraction(
            CsrGraph graph,
            float[] positions,
            float[] forces,
            int dimensions,
            KernelParameters kernel,
            LayoutConfig config,
            boolean fastKernel) {
        if (fastKernel) {
            accumulateAttractionRangeFast(graph, positions, forces, dimensions, kernel, config, 0, graph.nodeCount());
        } else {
            accumulateAttractionRange(graph, positions, forces, dimensions, kernel, config, 0, graph.nodeCount());
        }
    }

    private static void accumulateAttractionParallel(
            ExecutorService executor,
            int workers,
            CsrGraph graph,
            float[] positions,
            float[] forces,
            int dimensions,
            KernelParameters kernel,
            LayoutConfig config,
            boolean fastKernel) {
        if (fastKernel) {
            invokeRanges(executor, workers, graph.nodeCount(),
                    (startInclusive, endExclusive) -> accumulateAttractionRangeFast(
                            graph,
                            positions,
                            forces,
                            dimensions,
                            kernel,
                            config,
                            startInclusive,
                            endExclusive));
        } else {
            invokeRanges(executor, workers, graph.nodeCount(),
                    (startInclusive, endExclusive) -> accumulateAttractionRange(
                            graph,
                            positions,
                            forces,
                            dimensions,
                            kernel,
                            config,
                            startInclusive,
                            endExclusive));
        }
    }

    private static void accumulateAttractionRange(
            CsrGraph graph,
            float[] positions,
            float[] forces,
            int dimensions,
            KernelParameters kernel,
            LayoutConfig config,
            int startInclusive,
            int endExclusive) {
        int[] offsets = graph.offsets();
        int[] targets = graph.targets();
        float[] weights = graph.weights();
        for (int i = startInclusive; i < endExclusive; i++) {
            int sourceBase = i * dimensions;
            for (int p = offsets[i]; p < offsets[i + 1]; p++) {
                int j = targets[p];
                if (i == j) {
                    continue;
                }
                int targetBase = j * dimensions;
                float dx = positions[targetBase] - positions[sourceBase];
                float dy = positions[targetBase + 1] - positions[sourceBase + 1];
                float dz = dimensions == 3 ? positions[targetBase + 2] - positions[sourceBase + 2] : 0.0f;
                double distSq = 1.0e-10;
                distSq += dx * dx;
                distSq += dy * dy;
                if (dimensions == 3) {
                    distSq += dz * dz;
                }
                double dist = Math.sqrt(distSq);
                double distSqB = Math.pow(distSq, kernel.b);
                double coefficient = config.attractionStrength()
                        * weights[p]
                        * distSqB
                        / (distSqB + kernel.a)
                        / dist;
                forces[sourceBase] += (float) (coefficient * dx);
                forces[sourceBase + 1] += (float) (coefficient * dy);
                if (dimensions == 3) {
                    forces[sourceBase + 2] += (float) (coefficient * dz);
                }
            }
        }
    }

    private static void accumulateAttractionRangeFast(
            CsrGraph graph,
            float[] positions,
            float[] forces,
            int dimensions,
            KernelParameters kernel,
            LayoutConfig config,
            int startInclusive,
            int endExclusive) {
        int[] offsets = graph.offsets();
        int[] targets = graph.targets();
        float[] weights = graph.weights();
        for (int i = startInclusive; i < endExclusive; i++) {
            int sourceBase = i * dimensions;
            for (int p = offsets[i]; p < offsets[i + 1]; p++) {
                int j = targets[p];
                if (i == j) {
                    continue;
                }
                int targetBase = j * dimensions;
                float dx = positions[targetBase] - positions[sourceBase];
                float dy = positions[targetBase + 1] - positions[sourceBase + 1];
                float dz = dimensions == 3 ? positions[targetBase + 2] - positions[sourceBase + 2] : 0.0f;
                double distSq = 1.0e-10;
                distSq += dx * dx;
                distSq += dy * dy;
                if (dimensions == 3) {
                    distSq += dz * dz;
                }
                double dist = Math.sqrt(distSq);
                double distSqB = distSq;
                double coefficient = config.attractionStrength()
                        * weights[p]
                        * distSqB
                        / (distSqB + kernel.a)
                        / dist;
                forces[sourceBase] += (float) (coefficient * dx);
                forces[sourceBase + 1] += (float) (coefficient * dy);
                if (dimensions == 3) {
                    forces[sourceBase + 2] += (float) (coefficient * dz);
                }
            }
        }
    }

    private static void accumulateRepulsion(
            float[] positions,
            float[] forces,
            int nodeCount,
            int dimensions,
            int iteration,
            KernelParameters kernel,
            LayoutConfig config,
            boolean fastKernel) {
        if (nodeCount <= 1 || config.negativeSamples() == 0 || config.repulsionStrength() == 0.0f) {
            return;
        }
        if (fastKernel) {
            accumulateRepulsionRangeFast(positions, forces, nodeCount, dimensions, iteration, kernel, config, 0, nodeCount);
        } else {
            accumulateRepulsionRange(positions, forces, nodeCount, dimensions, iteration, kernel, config, 0, nodeCount);
        }
    }

    private static void accumulateRepulsionParallel(
            ExecutorService executor,
            int workers,
            float[] positions,
            float[] forces,
            int nodeCount,
            int dimensions,
            int iteration,
            KernelParameters kernel,
            LayoutConfig config,
            boolean fastKernel) {
        if (nodeCount <= 1 || config.negativeSamples() == 0 || config.repulsionStrength() == 0.0f) {
            return;
        }
        if (fastKernel) {
            invokeRanges(executor, workers, nodeCount,
                    (startInclusive, endExclusive) -> accumulateRepulsionRangeFast(
                            positions,
                            forces,
                            nodeCount,
                            dimensions,
                            iteration,
                            kernel,
                            config,
                            startInclusive,
                            endExclusive));
        } else {
            invokeRanges(executor, workers, nodeCount,
                    (startInclusive, endExclusive) -> accumulateRepulsionRange(
                            positions,
                            forces,
                            nodeCount,
                            dimensions,
                            iteration,
                            kernel,
                            config,
                            startInclusive,
                            endExclusive));
        }
    }

    private static void accumulateRepulsionRange(
            float[] positions,
            float[] forces,
            int nodeCount,
            int dimensions,
            int iteration,
            KernelParameters kernel,
            LayoutConfig config,
            int startInclusive,
            int endExclusive) {
        int samples = Math.min(config.negativeSamples(), nodeCount - 1);
        for (int i = startInclusive; i < endExclusive; i++) {
            int sourceBase = i * dimensions;
            for (int sample = 0; sample < samples; sample++) {
                long key = config.randomSeed()
                        ^ (0x9E3779B97F4A7C15L * (iteration + 1L))
                        ^ (0xBF58476D1CE4E5B9L * (i + 1L))
                        ^ (0x94D049BB133111EBL * (sample + 1L));
                int j = SplitMix64.bounded(key, nodeCount - 1);
                if (j >= i) {
                    j++;
                }
                int targetBase = j * dimensions;
                float dx = positions[targetBase] - positions[sourceBase];
                float dy = positions[targetBase + 1] - positions[sourceBase + 1];
                float dz = dimensions == 3 ? positions[targetBase + 2] - positions[sourceBase + 2] : 0.0f;
                double distSq = 1.0e-10;
                distSq += dx * dx;
                distSq += dy * dy;
                if (dimensions == 3) {
                    distSq += dz * dz;
                }
                double dist = Math.sqrt(distSq);
                double distSqB = Math.pow(distSq, kernel.b);
                double coefficient = -config.repulsionStrength()
                        / (1.0 + kernel.a * distSqB)
                        * Math.exp(-dist / config.cutoff())
                        / dist;
                forces[sourceBase] += (float) (coefficient * dx);
                forces[sourceBase + 1] += (float) (coefficient * dy);
                if (dimensions == 3) {
                    forces[sourceBase + 2] += (float) (coefficient * dz);
                }
            }
        }
    }

    private static void accumulateRepulsionRangeFast(
            float[] positions,
            float[] forces,
            int nodeCount,
            int dimensions,
            int iteration,
            KernelParameters kernel,
            LayoutConfig config,
            int startInclusive,
            int endExclusive) {
        int samples = Math.min(config.negativeSamples(), nodeCount - 1);
        for (int i = startInclusive; i < endExclusive; i++) {
            int sourceBase = i * dimensions;
            for (int sample = 0; sample < samples; sample++) {
                long key = config.randomSeed()
                        ^ (0x9E3779B97F4A7C15L * (iteration + 1L))
                        ^ (0xBF58476D1CE4E5B9L * (i + 1L))
                        ^ (0x94D049BB133111EBL * (sample + 1L));
                int j = SplitMix64.bounded(key, nodeCount - 1);
                if (j >= i) {
                    j++;
                }
                int targetBase = j * dimensions;
                float dx = positions[targetBase] - positions[sourceBase];
                float dy = positions[targetBase + 1] - positions[sourceBase + 1];
                float dz = dimensions == 3 ? positions[targetBase + 2] - positions[sourceBase + 2] : 0.0f;
                double distSq = 1.0e-10;
                distSq += dx * dx;
                distSq += dy * dy;
                if (dimensions == 3) {
                    distSq += dz * dz;
                }
                double dist = Math.sqrt(distSq);
                double distSqB = distSq;
                double coefficient = -config.repulsionStrength()
                        / (1.0 + kernel.a * distSqB)
                        * Math.exp(-dist / config.cutoff())
                        / dist;
                forces[sourceBase] += (float) (coefficient * dx);
                forces[sourceBase + 1] += (float) (coefficient * dy);
                if (dimensions == 3) {
                    forces[sourceBase + 2] += (float) (coefficient * dz);
                }
            }
        }
    }

    private static int workerCount(LayoutConfig config, int nodeCount) {
        if (nodeCount < PARALLEL_NODE_THRESHOLD) {
            return 1;
        }
        return Math.max(1, Math.min(config.concurrency(), nodeCount));
    }

    private static ExecutorService createSharedExecutor() {
        int parallelism = Math.max(1, Runtime.getRuntime().availableProcessors());
        AtomicInteger threadId = new AtomicInteger();
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, "dire-layout-" + threadId.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(
                parallelism,
                parallelism,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, parallelism * 4)),
                threadFactory,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    private static void invokeRanges(ExecutorService executor, int workers, int itemCount, RangeTask task) {
        List<Future<?>> futures = new ArrayList<>(workers);
        int chunk = Math.max(1, (itemCount + workers - 1) / workers);
        try {
            for (int start = 0; start < itemCount; start += chunk) {
                int rangeStart = start;
                int rangeEnd = Math.min(itemCount, start + chunk);
                futures.add(executor.submit(() -> task.run(rangeStart, rangeEnd)));
            }
        } catch (RuntimeException e) {
            cancelAll(futures);
            throw e;
        }
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                cancelAll(futures);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("layout worker interrupted", e);
            } catch (ExecutionException e) {
                cancelAll(futures);
                throw new IllegalStateException("layout worker failed", e.getCause());
            }
        }
    }

    private static void cancelAll(List<Future<?>> futures) {
        for (Future<?> future : futures) {
            future.cancel(true);
        }
    }

    @FunctionalInterface
    private interface RangeTask {
        void run(int startInclusive, int endExclusive);
    }

    private static void clampForces(float[] forces, float cutoff) {
        for (int i = 0; i < forces.length; i++) {
            if (forces[i] > cutoff) {
                forces[i] = cutoff;
            } else if (forces[i] < -cutoff) {
                forces[i] = -cutoff;
            }
        }
    }
}
