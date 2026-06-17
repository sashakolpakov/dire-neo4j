package org.dire.neo4j.plugin;

import org.dire.neo4j.core.CsrGraph;

final class GraphProjection {
    final CsrGraph graph;
    final long relationshipsRead;
    final float[] warmStart;
    final String[] elementIds;

    GraphProjection(CsrGraph graph, long relationshipsRead, float[] warmStart, String[] elementIds) {
        this.graph = graph;
        this.relationshipsRead = relationshipsRead;
        this.warmStart = warmStart;
        this.elementIds = elementIds;
    }

    boolean usesElementIds() {
        return elementIds != null;
    }

    String elementId(int index) {
        return elementIds == null ? null : elementIds[index];
    }
}
