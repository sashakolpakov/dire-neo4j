package org.dire.neo4j.benchmarks;

import org.dire.neo4j.core.CsrGraph;
import org.dire.neo4j.core.CsrGraphBuilder;
import org.dire.neo4j.core.DiReLayout;
import org.dire.neo4j.core.InitializationMode;
import org.dire.neo4j.core.LayoutConfig;
import org.dire.neo4j.core.LayoutResult;
import org.dire.neo4j.core.RelationshipMode;
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

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Fork(1)
public class CoreLayoutBenchmark {
    @Benchmark
    public void buildCsr(GraphState state, Blackhole blackhole) {
        CsrGraph graph = CsrGraphBuilder.fromRelationships(
                state.fixture.nodeIds(),
                state.fixture.sources(),
                state.fixture.targets(),
                state.fixture.weights(),
                RelationshipMode.UNDIRECTED);
        blackhole.consume(graph.storedEdgeCount());
        blackhole.consume(graph.isolatedNodeCount());
    }

    @Benchmark
    public void spectralInitialization(GraphState state, Blackhole blackhole) {
        LayoutResult result = new DiReLayout().run(state.graph, state.spectralOnlyConfig);
        consumeLayout(result, blackhole);
    }

    @Benchmark
    public void randomInitLayout(GraphState state, Blackhole blackhole) {
        LayoutResult result = new DiReLayout().run(state.graph, state.randomInitConfig);
        consumeLayout(result, blackhole);
    }

    @Benchmark
    public void fullSpectralLayout(GraphState state, Blackhole blackhole) {
        LayoutResult result = new DiReLayout().run(state.graph, state.spectralLayoutConfig);
        consumeLayout(result, blackhole);
    }

    private static void consumeLayout(LayoutResult result, Blackhole blackhole) {
        blackhole.consume(result.nodeCount());
        blackhole.consume(result.coordinate(0, 0));
        blackhole.consume(result.coordinate(result.nodeCount() - 1, 1));
        blackhole.consume(result.metrics().meanEdgeLength());
        blackhole.consume(result.metrics().stress());
        blackhole.consume(usedHeapBytes());
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    @State(Scope.Benchmark)
    public static class GraphState {
        @Param({"1000"})
        public int nodeCount;

        @Param({"1"})
        public int longRangeEdgesPerNode;

        @Param({"5"})
        public int iterations;

        @Param({"1"})
        public int concurrency;

        SyntheticGraphFactory.Fixture fixture;
        CsrGraph graph;
        LayoutConfig spectralOnlyConfig;
        LayoutConfig randomInitConfig;
        LayoutConfig spectralLayoutConfig;

        @Setup(Level.Trial)
        public void setup() {
            fixture = SyntheticGraphFactory.ringWithLongRangeEdges(nodeCount, longRangeEdgesPerNode, 0xD1E_4A5EL);
            graph = SyntheticGraphFactory.buildUndirected(fixture);
            spectralOnlyConfig = baseConfig()
                    .iterations(0)
                    .initializationMode(InitializationMode.SPECTRAL)
                    .build();
            randomInitConfig = baseConfig()
                    .iterations(iterations)
                    .initializationMode(InitializationMode.RANDOM)
                    .build();
            spectralLayoutConfig = baseConfig()
                    .iterations(iterations)
                    .initializationMode(InitializationMode.SPECTRAL)
                    .build();
        }

        private LayoutConfig.Builder baseConfig() {
            return LayoutConfig.builder()
                    .randomSeed(42L)
                    .negativeSamples(4)
                    .concurrency(concurrency);
        }
    }
}
