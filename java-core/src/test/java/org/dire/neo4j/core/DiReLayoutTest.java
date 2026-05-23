package org.dire.neo4j.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
