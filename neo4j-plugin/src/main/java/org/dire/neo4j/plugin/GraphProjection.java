package org.dire.neo4j.plugin;

import org.dire.neo4j.core.CsrGraph;

final class GraphProjection {
    final CsrGraph graph;
    final long relationshipsRead;
    final float[] warmStart;

    GraphProjection(CsrGraph graph, long relationshipsRead, float[] warmStart) {
        this.graph = graph;
        this.relationshipsRead = relationshipsRead;
        this.warmStart = warmStart;
    }
}
