package org.dire.neo4j.benchmarks;

import org.dire.neo4j.core.CsrGraph;
import org.dire.neo4j.core.CsrGraphBuilder;
import org.dire.neo4j.core.RelationshipMode;

import java.util.SplittableRandom;

public final class SyntheticGraphFactory {
    private SyntheticGraphFactory() {
    }

    public static Fixture ringWithLongRangeEdges(int nodeCount, int longRangeEdgesPerNode, long seed) {
        if (nodeCount < 2) {
            throw new IllegalArgumentException("nodeCount must be at least 2");
        }
        if (longRangeEdgesPerNode < 0) {
            throw new IllegalArgumentException("longRangeEdgesPerNode must be non-negative");
        }

        long[] nodeIds = new long[nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            nodeIds[i] = i;
        }

        int relationshipCount = nodeCount + nodeCount * longRangeEdgesPerNode;
        long[] sources = new long[relationshipCount];
        long[] targets = new long[relationshipCount];
        float[] weights = new float[relationshipCount];

        int cursor = 0;
        for (int i = 0; i < nodeCount; i++) {
            sources[cursor] = i;
            targets[cursor] = (i + 1L) % nodeCount;
            weights[cursor] = 1.0f;
            cursor++;
        }

        SplittableRandom random = new SplittableRandom(seed);
        for (int i = 0; i < nodeCount; i++) {
            for (int edge = 0; edge < longRangeEdgesPerNode; edge++) {
                int target = random.nextInt(nodeCount - 1);
                if (target >= i) {
                    target++;
                }
                sources[cursor] = i;
                targets[cursor] = target;
                weights[cursor] = 0.5f;
                cursor++;
            }
        }

        return new Fixture(nodeIds, sources, targets, weights);
    }

    public static CsrGraph buildUndirected(Fixture fixture) {
        return CsrGraphBuilder.fromRelationships(
                fixture.nodeIds,
                fixture.sources,
                fixture.targets,
                fixture.weights,
                RelationshipMode.UNDIRECTED);
    }

    public record Fixture(long[] nodeIds, long[] sources, long[] targets, float[] weights) {
        public int nodeCount() {
            return nodeIds.length;
        }

        public int relationshipCount() {
            return sources.length;
        }
    }
}
