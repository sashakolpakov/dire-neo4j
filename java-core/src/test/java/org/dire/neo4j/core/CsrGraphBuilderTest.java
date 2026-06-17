package org.dire.neo4j.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CsrGraphBuilderTest {
    @Test
    void buildsUndirectedCsrFromRelationshipArrays() {
        CsrGraph graph = CsrGraphBuilder.fromRelationships(
                new long[]{10, 20, 30},
                new long[]{10, 20},
                new long[]{20, 30},
                new float[]{2.0f, 3.0f},
                RelationshipMode.UNDIRECTED);

        assertEquals(3, graph.nodeCount());
        assertEquals(4, graph.storedEdgeCount());
        assertArrayEquals(new int[]{0, 1, 3, 4}, graph.offsets());
        assertArrayEquals(new int[]{1, 0, 2, 1}, graph.targets());
        assertArrayEquals(new float[]{2.0f, 2.0f, 3.0f, 3.0f}, graph.weights());
    }

    @Test
    void rejectsRelationshipOutsideNodeProjection() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> CsrGraphBuilder.fromRelationships(
                        new long[]{10, 20},
                        new long[]{10},
                        new long[]{30},
                        null,
                        RelationshipMode.UNDIRECTED));

        assertEquals("relationshipQuery returned target id not present in nodeQuery: 30", error.getMessage());
    }

    @Test
    void buildsDirectedCsrInStableSourceOrder() {
        CsrGraph graph = CsrGraphBuilder.fromRelationships(
                new long[]{1, 2, 3},
                new long[]{1, 2, 1, 3},
                new long[]{2, 3, 3, 1},
                new float[]{1.0f, 2.0f, 3.0f, 4.0f},
                RelationshipMode.DIRECTED);

        assertEquals(4, graph.storedEdgeCount());
        assertArrayEquals(new int[]{0, 2, 3, 4}, graph.offsets());
        assertArrayEquals(new int[]{1, 2, 2, 0}, graph.targets());
        assertArrayEquals(new float[]{1.0f, 3.0f, 2.0f, 4.0f}, graph.weights());
    }

    @Test
    void lengthAwareBuildIgnoresTrailingCapacity() {
        CsrGraph graph = CsrGraphBuilder.fromRelationships(
                new long[]{10, 20, 30, 999},
                3,
                new long[]{10, 20, 999},
                new long[]{20, 30, 10},
                new float[]{2.0f, 3.0f, Float.NaN},
                2,
                RelationshipMode.UNDIRECTED);

        assertEquals(3, graph.nodeCount());
        assertEquals(4, graph.storedEdgeCount());
        assertArrayEquals(new long[]{10, 20, 30}, graph.nodeIdsCopy());
        assertArrayEquals(new int[]{0, 1, 3, 4}, graph.offsets());
        assertArrayEquals(new int[]{1, 0, 2, 1}, graph.targets());
        assertArrayEquals(new float[]{2.0f, 2.0f, 3.0f, 3.0f}, graph.weights());
    }

    @Test
    void storesUndirectedSelfLoopOnlyOnce() {
        CsrGraph graph = CsrGraphBuilder.fromRelationships(
                new long[]{10, 20},
                new long[]{10, 10},
                new long[]{10, 20},
                new float[]{5.0f, 2.0f},
                RelationshipMode.UNDIRECTED);

        assertEquals(3, graph.storedEdgeCount());
        assertArrayEquals(new int[]{0, 2, 3}, graph.offsets());
        assertArrayEquals(new int[]{0, 1, 0}, graph.targets());
        assertArrayEquals(new float[]{5.0f, 2.0f, 2.0f}, graph.weights());
    }

    @Test
    void skipsZeroWeightRelationships() {
        CsrGraph graph = CsrGraphBuilder.fromRelationships(
                new long[]{10, 20, 30},
                new long[]{10, 20},
                new long[]{20, 30},
                new float[]{0.0f, 3.0f},
                RelationshipMode.UNDIRECTED);

        assertEquals(2, graph.storedEdgeCount());
        assertArrayEquals(new int[]{0, 0, 1, 2}, graph.offsets());
        assertArrayEquals(new int[]{2, 1}, graph.targets());
        assertArrayEquals(new float[]{3.0f, 3.0f}, graph.weights());
    }

    @Test
    void rejectsAllZeroWeightRelationships() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> CsrGraphBuilder.fromRelationships(
                        new long[]{10, 20},
                        new long[]{10},
                        new long[]{20},
                        new float[]{0.0f},
                        RelationshipMode.UNDIRECTED));

        assertEquals("relationshipQuery returned no usable relationships", error.getMessage());
    }

    @Test
    void rejectsDuplicateNodeIds() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> CsrGraphBuilder.fromRelationships(
                        new long[]{10, 10},
                        new long[]{10},
                        new long[]{10},
                        null,
                        RelationshipMode.UNDIRECTED));

        assertEquals("nodeQuery returned duplicate id: 10", error.getMessage());
    }

    @Test
    void rejectsInvalidWeights() {
        assertInvalidWeight(Float.NaN);
        assertInvalidWeight(Float.POSITIVE_INFINITY);
        assertInvalidWeight(-1.0f);
    }

    private static void assertInvalidWeight(float weight) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> CsrGraphBuilder.fromRelationships(
                        new long[]{10, 20},
                        new long[]{10},
                        new long[]{20},
                        new float[]{weight},
                        RelationshipMode.UNDIRECTED));

        assertEquals("relationship weight must be finite and non-negative", error.getMessage());
    }
}
