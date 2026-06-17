package org.dire.neo4j.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void fastKernelFallsBackToSlowPathWhenExponentIsNotNearOne() {
        assertFalse(KernelParameters.fit(1.0e-2f, 1.0f).isNearLinearExponent());

        CsrGraph graph = cycle(16);
        LayoutConfig slow = LayoutConfig.builder()
                .iterations(8)
                .randomSeed(23L)
                .negativeSamples(4)
                .concurrency(1)
                .build();
        LayoutConfig fastRequested = LayoutConfig.builder()
                .iterations(8)
                .randomSeed(23L)
                .negativeSamples(4)
                .concurrency(1)
                .fastKernel(true)
                .build();

        LayoutResult slowResult = new DiReLayout().run(graph, slow);
        LayoutResult fallbackResult = new DiReLayout().run(graph, fastRequested);

        assertArrayEquals(slowResult.positionsCopy(), fallbackResult.positionsCopy());
        assertArrayEquals(slowResult.initialPositionsCopy(), fallbackResult.initialPositionsCopy());
    }

    @Test
    void fastKernelNearLinearPathIsDeterministicAndParallelSafe() {
        assertTrue(KernelParameters.fit(0.2f, 1.0f).isNearLinearExponent());

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
}
