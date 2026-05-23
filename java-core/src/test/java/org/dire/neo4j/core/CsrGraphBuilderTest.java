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
}
