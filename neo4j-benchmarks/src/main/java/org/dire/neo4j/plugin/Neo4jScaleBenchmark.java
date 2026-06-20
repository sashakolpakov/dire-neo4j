package org.dire.neo4j.plugin;

import org.dire.neo4j.core.DiReLayout;
import org.dire.neo4j.core.LayoutResult;
import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilders;
import org.openjdk.jmh.annotations.AuxCounters;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@Fork(1)
public class Neo4jScaleBenchmark {
    @Benchmark
    public void projection(DatabaseState state, Blackhole blackhole) {
        try (Transaction tx = state.db.beginTx()) {
            DiReConfig config = DiReConfig.parse(state.config(false, false));
            GraphProjection projection = GraphProjectionLoader.load(tx, config, false);
            blackhole.consume(projection.graph);
            blackhole.consume(projection.relationshipsRead);
        }
    }

    @Benchmark
    public void projectionPeakHeap(DatabaseState state, MemoryCounters counters, Blackhole blackhole) {
        HeapPeakSampler sampler = new HeapPeakSampler();
        sampler.start();
        try {
            projection(state, blackhole);
        } finally {
            counters.peakHeapBytes = sampler.stop();
        }
    }

    @Benchmark
    public void write(DatabaseState state, Blackhole blackhole) {
        DiReConfig config = DiReConfig.parse(state.config(false, true));
        int nodesWritten;
        if (state.writeBatchSize == 0) {
            try (Transaction tx = state.db.beginTx()) {
                nodesWritten = DiReProcedures.writeRange(
                        tx, state.projection, state.layout, config, 0, state.layout.nodeCount());
                tx.commit();
            }
        } else {
            nodesWritten = DiReProcedures.writeBatches(
                    state.layout.nodeCount(),
                    state.writeBatchSize,
                    (start, end) -> {
                        try (Transaction tx = state.db.beginTx()) {
                            DiReProcedures.writeRange(tx, state.projection, state.layout, config, start, end);
                            tx.commit();
                        }
                    });
        }
        blackhole.consume(nodesWritten);
    }

    @Benchmark
    public void stream(DatabaseState state, Blackhole blackhole) {
        try (Transaction tx = state.db.beginTx()) {
            Map<String, Object> config = state.config(state.includeEmbedding, false);
            try (Result result = tx.execute(
                    "CALL dire.layout.stream($config) YIELD nodeId, embedding RETURN nodeId, embedding",
                    Map.of("config", config))) {
                while (result.hasNext()) {
                    Map<String, Object> row = result.next();
                    blackhole.consume(row.get("nodeId"));
                    blackhole.consume(row.get("embedding"));
                }
            }
        }
    }

    @State(Scope.Benchmark)
    public static class DatabaseState {
        @Param({"1000"})
        public int nodeCount;

        @Param({"numeric", "element"})
        public String identityMode;

        @Param({"0", "10000"})
        public int writeBatchSize;

        @Param({"false", "true"})
        public boolean includeEmbedding;

        Neo4j neo4j;
        GraphDatabaseService db;
        GraphProjection projection;
        LayoutResult layout;

        @Setup(Level.Trial)
        public void setup() {
            neo4j = Neo4jBuilders.newInProcessBuilder()
                    .withDisabledServer()
                    .withConfig(BoltConnector.enabled, false)
                    .withProcedure(DiReProcedures.class)
                    .build();
            db = neo4j.defaultDatabaseService();
            seed();
            try (Transaction tx = db.beginTx()) {
                DiReConfig config = DiReConfig.parse(config(false, false));
                projection = GraphProjectionLoader.load(tx, config, false);
                layout = new DiReLayout().run(projection.graph, config.layoutConfig, null);
            }
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            neo4j.close();
        }

        Map<String, Object> config(boolean embeddings, boolean writing) {
            boolean elementIds = identityMode.equals("element");
            Map<String, Object> config = new HashMap<>();
            config.put("nodeQuery", elementIds
                    ? "MATCH (n:Bench) RETURN elementId(n) AS id ORDER BY n.idx"
                    : "MATCH (n:Bench) RETURN id(n) AS id ORDER BY n.idx");
            config.put("relationshipQuery", elementIds
                    ? "MATCH (a:Bench)-[:LINK]->(b:Bench) RETURN elementId(a) AS source, elementId(b) AS target"
                    : "MATCH (a:Bench)-[:LINK]->(b:Bench) RETURN id(a) AS source, id(b) AS target");
            config.put("initialization", "random");
            config.put("iterations", 0);
            config.put("includeEmbedding", embeddings);
            config.put("writeProperties", java.util.List.of("bench_x", "bench_y"));
            config.put("writeInitialProperties", java.util.List.of());
            if (writing && writeBatchSize > 0) {
                config.put("writeBatchSize", writeBatchSize);
            }
            return config;
        }

        private void seed() {
            try (Transaction tx = db.beginTx()) {
                tx.execute("CREATE INDEX bench_idx IF NOT EXISTS FOR (n:Bench) ON (n.idx)");
                tx.commit();
            }
            try (Transaction tx = db.beginTx()) {
                tx.execute("CALL db.awaitIndexes()");
                tx.commit();
            }
            int batchSize = 10_000;
            for (int start = 0; start < nodeCount; start += batchSize) {
                int end = Math.min(nodeCount, start + batchSize);
                try (Transaction tx = db.beginTx()) {
                    tx.execute(
                            "UNWIND range($start, $end) AS i CREATE (:Bench {idx: i})",
                            Map.of("start", start, "end", end - 1));
                    tx.commit();
                }
            }
            for (int start = 0; start < nodeCount; start += batchSize) {
                int end = Math.min(nodeCount, start + batchSize);
                try (Transaction tx = db.beginTx()) {
                    tx.execute("""
                            UNWIND range($start, $end) AS i
                            MATCH (a:Bench {idx: i})
                            MATCH (b:Bench {idx: (i + 1) % $nodeCount})
                            CREATE (a)-[:LINK]->(b)
                            """, Map.of("start", start, "end", end - 1, "nodeCount", nodeCount));
                    tx.commit();
                }
            }
        }
    }

    @State(Scope.Thread)
    @AuxCounters(AuxCounters.Type.EVENTS)
    public static class MemoryCounters {
        public long peakHeapBytes;

        @Setup(Level.Invocation)
        public void reset() {
            peakHeapBytes = 0L;
        }
    }

    private static final class HeapPeakSampler {
        private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        private final AtomicBoolean running = new AtomicBoolean();
        private final AtomicLong peak = new AtomicLong();
        private long baseline;
        private Thread thread;

        void start() {
            baseline = usedHeap();
            peak.set(baseline);
            running.set(true);
            thread = new Thread(() -> {
                while (running.get()) {
                    peak.accumulateAndGet(usedHeap(), Math::max);
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                }
            }, "dire-benchmark-heap-sampler");
            thread.setDaemon(true);
            thread.start();
        }

        long stop() {
            running.set(false);
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            peak.accumulateAndGet(usedHeap(), Math::max);
            return Math.max(0L, peak.get() - baseline);
        }

        private long usedHeap() {
            return memory.getHeapMemoryUsage().getUsed();
        }
    }
}
