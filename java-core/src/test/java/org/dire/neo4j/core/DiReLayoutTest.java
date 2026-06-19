package org.dire.neo4j.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiReLayoutTest {
    @Test
    void spectralLayoutIsDeterministicAndFinite() {
        CsrGraph graph = cycle(8);
        LayoutConfig config = LayoutConfig.builder()
                .iterations(20)
                .randomSeed(7L)
                .negativeSamples(4)
                .build();

        LayoutResult first = new DiReLayout().run(graph, config);
        LayoutResult second = new DiReLayout().run(graph, config);

        assertEquals(8, first.nodeCount());
        assertArrayEquals(first.positionsCopy(), second.positionsCopy(), 1.0e-6f);
        assertArrayEquals(first.initialPositionsCopy(), second.initialPositionsCopy(), 1.0e-6f);
        assertFinite(first);
        assertSpread(first);
        assertTrue(maxDelta(first.initialPositionsCopy(), first.positionsCopy()) > 1.0e-3f);
    }

    @Test
    void fixedConfigurationMatchesGoldenVectorsExactly() {
        LayoutConfig config = LayoutConfig.builder()
                .iterations(20)
                .randomSeed(7L)
                .negativeSamples(4)
                .concurrency(1)
                .build();

        LayoutResult result = new DiReLayout().run(cycle(8), config);

        assertArrayEquals(new float[]{
                1.2125305f, 0.99999994f, -1.3212014f, -0.99999994f,
                0.71045333f, 0.99999994f, 0.5286328f, -0.99999994f,
                -1.2125305f, 1.0000001f, 1.3212014f, -1.0000001f,
                -0.7104532f, 1.0000001f, -0.52863306f, -1.0000001f
        }, result.initialPositionsCopy());
        assertArrayEquals(new float[]{
                -1.3466598f, -0.048272926f, -1.0094532f, 0.88623273f,
                0.1113702f, 1.4807844f, 1.088632f, 1.0818856f,
                1.3543473f, -0.078181125f, 0.99277705f, -1.009264f,
                -0.12600213f, -1.42947f, -1.0650115f, -0.8837148f
        }, result.positionsCopy());
    }

    @Test
    void spectralConvergenceDefaultsPreserveFixed160Iterations() {
        LayoutConfig defaults = LayoutConfig.builder()
                .iterations(0)
                .randomSeed(13L)
                .build();
        LayoutConfig explicitFixed = LayoutConfig.builder()
                .iterations(0)
                .randomSeed(13L)
                .spectralTolerance(0.0f)
                .spectralMinIterations(8)
                .spectralMaxIterations(160)
                .build();

        assertEquals(0.0f, defaults.spectralTolerance());
        assertEquals(8, defaults.spectralMinIterations());
        assertEquals(160, defaults.spectralMaxIterations());

        LayoutResult defaultResult = new DiReLayout().run(cycle(32), defaults);
        LayoutResult explicitResult = new DiReLayout().run(cycle(32), explicitFixed);

        assertArrayEquals(defaultResult.initialPositionsCopy(), explicitResult.initialPositionsCopy());
        assertArrayEquals(defaultResult.positionsCopy(), explicitResult.positionsCopy());
    }

    @Test
    void spectralToleranceStopsAtConfiguredIterationFloor() {
        LayoutConfig convergenceChecked = LayoutConfig.builder()
                .iterations(0)
                .randomSeed(29L)
                .spectralTolerance(1.0f)
                .spectralMinIterations(4)
                .spectralMaxIterations(160)
                .build();
        LayoutConfig fixedFourIterations = LayoutConfig.builder()
                .iterations(0)
                .randomSeed(29L)
                .spectralTolerance(0.0f)
                .spectralMinIterations(4)
                .spectralMaxIterations(4)
                .build();

        LayoutResult converged = new DiReLayout().run(path(64), convergenceChecked);
        LayoutResult fixed = new DiReLayout().run(path(64), fixedFourIterations);

        assertFinite(converged);
        assertArrayEquals(fixed.initialPositionsCopy(), converged.initialPositionsCopy());
        assertArrayEquals(fixed.positionsCopy(), converged.positionsCopy());
    }

    @Test
    void spectralConvergenceControlsAreValidated() {
        assertThrows(IllegalArgumentException.class,
                () -> LayoutConfig.builder().spectralTolerance(Float.NaN).build());
        assertThrows(IllegalArgumentException.class,
                () -> LayoutConfig.builder().spectralTolerance(1.01f).build());
        assertThrows(IllegalArgumentException.class,
                () -> LayoutConfig.builder().spectralMinIterations(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> LayoutConfig.builder()
                        .spectralMinIterations(10)
                        .spectralMaxIterations(9)
                        .build());
    }

    @Test
    @Tag("large")
    void largeGraphLayoutSmokeTest() {
        CsrGraph graph = cycle(100_000);
        LayoutConfig config = LayoutConfig.builder()
                .initializationMode(InitializationMode.RANDOM)
                .iterations(2)
                .randomSeed(123L)
                .negativeSamples(2)
                .concurrency(4)
                .build();

        LayoutResult result = new DiReLayout().run(graph, config);

        assertEquals(100_000, result.nodeCount());
        assertFinite(result);
        assertSpread(result);
    }

    @Test
    void parallelLayoutMatchesSingleWorkerLayout() {
        CsrGraph graph = cycle(128);
        LayoutConfig singleWorker = LayoutConfig.builder()
                .iterations(15)
                .randomSeed(17L)
                .negativeSamples(6)
                .concurrency(1)
                .build();
        LayoutConfig multiWorker = LayoutConfig.builder()
                .iterations(15)
                .randomSeed(17L)
                .negativeSamples(6)
                .concurrency(4)
                .build();

        LayoutResult single = new DiReLayout().run(graph, singleWorker);
        LayoutResult parallel = new DiReLayout().run(graph, multiWorker);

        assertArrayEquals(single.positionsCopy(), parallel.positionsCopy(), 1.0e-6f);
        assertArrayEquals(single.initialPositionsCopy(), parallel.initialPositionsCopy(), 1.0e-6f);
    }

    @Test
    void injectedExecutorIsReusedAndRemainsCallerOwned() {
        CsrGraph graph = cycle(128);
        LayoutConfig config = LayoutConfig.builder()
                .iterations(3)
                .randomSeed(19L)
                .negativeSamples(2)
                .concurrency(4)
                .build();
        TrackingExecutor executor = new TrackingExecutor(2);

        try {
            DiReLayout layout = new DiReLayout(executor);
            LayoutResult first = layout.run(graph, config);
            int submissionsAfterFirstRun = executor.submissions();
            LayoutResult second = layout.run(graph, config);

            assertTrue(submissionsAfterFirstRun > 0);
            assertTrue(executor.submissions() > submissionsAfterFirstRun);
            assertFalse(executor.isShutdown());
            assertArrayEquals(first.positionsCopy(), second.positionsCopy(), 1.0e-6f);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void serialLayoutDoesNotSubmitWorkToInjectedExecutor() {
        CsrGraph graph = cycle(128);
        LayoutConfig config = LayoutConfig.builder()
                .iterations(3)
                .concurrency(1)
                .build();
        TrackingExecutor executor = new TrackingExecutor(1);

        try {
            new DiReLayout(executor).run(graph, config);

            assertEquals(0, executor.submissions());
            assertFalse(executor.isShutdown());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void workerFailureIsReportedWithoutTakingExecutorOwnership() {
        RuntimeException failure = new IllegalArgumentException("boom");
        FailingExecutor executor = new FailingExecutor(failure);
        LayoutConfig config = LayoutConfig.builder()
                .iterations(1)
                .concurrency(4)
                .build();

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> new DiReLayout(executor).run(cycle(128), config));

        assertEquals("layout worker failed", error.getMessage());
        assertSame(failure, error.getCause());
        assertFalse(executor.isShutdown());
    }

    @Test
    void workerInterruptionRestoresInterruptFlagWithoutShuttingDownExecutor() {
        BlockingExecutor executor = new BlockingExecutor();
        LayoutConfig config = LayoutConfig.builder()
                .iterations(1)
                .concurrency(4)
                .build();

        try {
            Thread.currentThread().interrupt();

            IllegalStateException error =
                    assertThrows(IllegalStateException.class, () -> new DiReLayout(executor).run(cycle(128), config));

            assertEquals("layout worker interrupted", error.getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(executor.isShutdown());
        } finally {
            Thread.interrupted();
            executor.release();
            executor.shutdownNow();
        }
    }

    @Test
    void fastKernelDefaultsFalseAndMatchesExplicitSlowPath() {
        CsrGraph graph = cycle(16);
        LayoutConfig defaultConfig = LayoutConfig.builder()
                .iterations(8)
                .randomSeed(11L)
                .negativeSamples(4)
                .concurrency(1)
                .build();
        LayoutConfig explicitSlow = LayoutConfig.builder()
                .iterations(8)
                .randomSeed(11L)
                .negativeSamples(4)
                .concurrency(1)
                .fastKernel(false)
                .build();

        assertFalse(defaultConfig.fastKernel());

        LayoutResult defaultResult = new DiReLayout().run(graph, defaultConfig);
        LayoutResult slowResult = new DiReLayout().run(graph, explicitSlow);

        assertArrayEquals(defaultResult.positionsCopy(), slowResult.positionsCopy());
        assertArrayEquals(defaultResult.initialPositionsCopy(), slowResult.initialPositionsCopy());
    }

    @Test
    void fastKernelRemainsDeterministicForNonNearLinearExponent() {
        CsrGraph graph = cycle(16);
        LayoutConfig fastRequested = LayoutConfig.builder()
                .iterations(8)
                .randomSeed(23L)
                .negativeSamples(4)
                .concurrency(1)
                .fastKernel(true)
                .build();

        LayoutResult first = new DiReLayout().run(graph, fastRequested);
        LayoutResult second = new DiReLayout().run(graph, fastRequested);

        assertFinite(first);
        assertFinite(second);
        assertArrayEquals(first.positionsCopy(), second.positionsCopy(), 1.0e-6f);
        assertArrayEquals(first.initialPositionsCopy(), second.initialPositionsCopy(), 1.0e-6f);
    }

    @Test
    void fastKernelApproximationStaysCloseToExactPath() {
        CsrGraph graph = cycle(128);
        LayoutConfig slow = LayoutConfig.builder()
                .minDist(0.2f)
                .spread(1.0f)
                .iterations(12)
                .randomSeed(31L)
                .negativeSamples(6)
                .concurrency(1)
                .build();
        LayoutConfig singleWorker = LayoutConfig.builder()
                .minDist(0.2f)
                .spread(1.0f)
                .iterations(12)
                .randomSeed(31L)
                .negativeSamples(6)
                .concurrency(1)
                .fastKernel(true)
                .build();

        LayoutResult exact = new DiReLayout().run(graph, slow);
        LayoutResult approximate = new DiReLayout().run(graph, singleWorker);

        assertFinite(exact);
        assertFinite(approximate);
        assertTrue(maxDelta(exact.positionsCopy(), approximate.positionsCopy()) < 0.35f);
        assertTrue(maxDelta(exact.initialPositionsCopy(), approximate.initialPositionsCopy()) < 0.35f);
    }

    @Test
    void fastKernelApproximationIsDeterministicAndParallelSafe() {
        CsrGraph graph = cycle(128);
        LayoutConfig singleWorker = LayoutConfig.builder()
                .minDist(0.2f)
                .spread(1.0f)
                .iterations(12)
                .randomSeed(31L)
                .negativeSamples(6)
                .concurrency(1)
                .fastKernel(true)
                .build();
        LayoutConfig multiWorker = LayoutConfig.builder()
                .minDist(0.2f)
                .spread(1.0f)
                .iterations(12)
                .randomSeed(31L)
                .negativeSamples(6)
                .concurrency(4)
                .fastKernel(true)
                .build();

        LayoutResult single = new DiReLayout().run(graph, singleWorker);
        LayoutResult parallel = new DiReLayout().run(graph, multiWorker);

        assertFinite(single);
        assertFinite(parallel);
        assertArrayEquals(single.positionsCopy(), parallel.positionsCopy(), 1.0e-6f);
        assertArrayEquals(single.initialPositionsCopy(), parallel.initialPositionsCopy(), 1.0e-6f);
    }

    @Test
    void warmStartUsesProvidedCoordinates() {
        CsrGraph graph = path(3);
        LayoutConfig config = LayoutConfig.builder()
                .initializationMode(InitializationMode.WARM_START)
                .iterations(0)
                .build();
        float[] warmStart = new float[]{
                -1.0f, 0.0f,
                0.0f, 0.0f,
                1.0f, 0.0f
        };

        LayoutResult result = new DiReLayout().run(graph, config, warmStart);

        assertFinite(result);
        assertTrue(result.coordinate(0, 0) < result.coordinate(1, 0));
        assertTrue(result.coordinate(1, 0) < result.coordinate(2, 0));
    }

    @Test
    void memoryEstimateAccountsForUndirectedStorage() {
        MemoryEstimate estimate = MemoryEstimate.estimate(10, 15, 2, RelationshipMode.UNDIRECTED);

        assertEquals(10, estimate.nodeCount());
        assertEquals(15, estimate.relationshipCount());
        assertEquals(30, estimate.storedEdgeCount());
        assertTrue(estimate.bytes() > 0);
    }

    @Test
    void memoryEstimateIncludesOptionalWarmStartAndEmbeddings() {
        MemoryEstimate base = MemoryEstimate.estimate(100, 200, 2, RelationshipMode.UNDIRECTED);
        MemoryEstimate warmStart = MemoryEstimate.estimate(100, 200, 2, RelationshipMode.UNDIRECTED, true, false);
        MemoryEstimate embeddings = MemoryEstimate.estimate(100, 200, 2, RelationshipMode.UNDIRECTED, false, true);

        assertTrue(warmStart.bytes() > base.bytes());
        assertTrue(embeddings.bytes() > base.bytes());
    }

    private static CsrGraph cycle(int n) {
        long[] nodeIds = new long[n];
        long[] sources = new long[n];
        long[] targets = new long[n];
        for (int i = 0; i < n; i++) {
            nodeIds[i] = i;
            sources[i] = i;
            targets[i] = (i + 1L) % n;
        }
        return CsrGraphBuilder.fromRelationships(nodeIds, sources, targets, null, RelationshipMode.UNDIRECTED);
    }

    private static CsrGraph path(int n) {
        long[] nodeIds = new long[n];
        long[] sources = new long[n - 1];
        long[] targets = new long[n - 1];
        for (int i = 0; i < n; i++) {
            nodeIds[i] = i;
            if (i + 1 < n) {
                sources[i] = i;
                targets[i] = i + 1L;
            }
        }
        return CsrGraphBuilder.fromRelationships(nodeIds, sources, targets, null, RelationshipMode.UNDIRECTED);
    }

    private static void assertFinite(LayoutResult result) {
        float[] positions = result.positionsCopy();
        for (float value : positions) {
            assertTrue(Float.isFinite(value), "coordinate must be finite");
        }
    }

    private static void assertSpread(LayoutResult result) {
        float[] positions = result.positionsCopy();
        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        for (float value : positions) {
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        assertTrue(max - min > 0.1f, "layout should not collapse");
    }

    private static float maxDelta(float[] left, float[] right) {
        float max = 0.0f;
        for (int i = 0; i < left.length; i++) {
            max = Math.max(max, Math.abs(left[i] - right[i]));
        }
        return max;
    }

    private static final class TrackingExecutor extends AbstractExecutorService {
        private final ExecutorService delegate;
        private final AtomicInteger submissions = new AtomicInteger();

        private TrackingExecutor(int threads) {
            delegate = Executors.newFixedThreadPool(threads);
        }

        int submissions() {
            return submissions.get();
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }

        @Override
        public void execute(Runnable command) {
            submissions.incrementAndGet();
            delegate.execute(command);
        }
    }

    private static final class FailingExecutor extends AbstractExecutorService {
        private final RuntimeException failure;
        private boolean shutdown;

        private FailingExecutor(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public Future<?> submit(Runnable task) {
            return CompletableFuture.failedFuture(failure);
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            shutdown = true;
            return java.util.List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class BlockingExecutor extends AbstractExecutorService {
        private final ExecutorService delegate = Executors.newSingleThreadExecutor();
        private final CountDownLatch release = new CountDownLatch(1);

        void release() {
            release.countDown();
        }

        @Override
        public void shutdown() {
            release();
            delegate.shutdown();
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            release();
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(() -> {
                try {
                    release.await();
                    command.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
    }
}
