package org.dire.neo4j.benchmarks;

import org.dire.neo4j.core.CsrGraph;
import org.dire.neo4j.core.DiReLayout;
import org.dire.neo4j.core.InitializationMode;
import org.dire.neo4j.core.LayoutConfig;
import org.dire.neo4j.core.LayoutResult;
import org.dire.neo4j.core.Recenter;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Isolates {@link Recenter#apply} cost (P2.5) from the rest of a layout
 * iteration. {@code recenter} measures one recenter pass in isolation;
 * {@code fullLayout} measures a complete run so per-iteration kernel time can be
 * derived as {@code fullLayout / iterations} and compared against the recenter
 * pass. If the recenter pass is below per-iteration noise, P2.5 stays serial.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Fork(1)
public class RecenterBenchmark {
    @Benchmark
    public void recenter(GraphState state, Blackhole blackhole) {
        Recenter.apply(state.positions, state.nodeCount, state.dimensions);
        blackhole.consume(state.positions[0]);
    }

    @Benchmark
    public void fullLayout(GraphState state, Blackhole blackhole) {
        LayoutResult result = new DiReLayout().run(state.graph, state.layoutConfig);
        blackhole.consume(result.coordinate(0, 0));
        blackhole.consume(result.metrics().stress());
    }

    @State(Scope.Benchmark)
    public static class GraphState {
        @Param({"10000", "100000"})
        public int nodeCount;

        @Param({"1"})
        public int longRangeEdgesPerNode;

        @Param({"2", "3"})
        public int dimensions;

        @Param({"20"})
        public int iterations;

        @Param({"1", "4"})
        public int concurrency;

        CsrGraph graph;
        LayoutConfig layoutConfig;
        float[] positions;

        @Setup(Level.Trial)
        public void setup() {
            SyntheticGraphFactory.Fixture fixture =
                    SyntheticGraphFactory.ringWithLongRangeEdges(nodeCount, longRangeEdgesPerNode, 0xD1E_4A5EL);
            graph = SyntheticGraphFactory.buildUndirected(fixture);
            layoutConfig = LayoutConfig.builder()
                    .randomSeed(42L)
                    .dimensions(dimensions)
                    .iterations(iterations)
                    .negativeSamples(4)
                    .concurrency(concurrency)
                    .initializationMode(InitializationMode.SPECTRAL)
                    .build();

            positions = new float[nodeCount * dimensions];
            for (int i = 0; i < positions.length; i++) {
                // Deterministic spread so the mean is non-trivial on the first pass.
                positions[i] = ((i * 2654435761L) & 0xFFFF) / 65535.0f - 0.5f;
            }
        }
    }
}
