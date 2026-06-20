# Neo4j-backed benchmarks

This JMH module measures projection, write throughput, stream materialization,
and sampled peak-heap growth against an embedded Neo4j database.

It is separate from the manual fast-kernel benchmark suite in `benchmarks/`.
Normal CI keeps only a small smoke gate; broader fast-kernel speedup and drift
analysis is intentionally manual.

Build:

```bash
mvn -pl neo4j-benchmarks -am package -DskipTests
```

The executable JAR uses dependencies copied to `target/lib`; keep that
directory next to the JAR.

Small smoke:

```bash
java -jar neo4j-benchmarks/target/neo4j-benchmarks.jar Neo4jScaleBenchmark \
  -wi 0 -i 1 -f 1 \
  -p nodeCount=1000 \
  -p identityMode=numeric \
  -p writeBatchSize=10000 \
  -p includeEmbedding=false
```

70k-node scale run:

```bash
java -Xmx4g -jar neo4j-benchmarks/target/neo4j-benchmarks.jar Neo4jScaleBenchmark \
  -p nodeCount=70000
```

One-million-node manual run:

```bash
java -Xmx16g -jar neo4j-benchmarks/target/neo4j-benchmarks.jar Neo4jScaleBenchmark.projectionPeakHeap \
  -wi 0 -i 1 -f 1 \
  -p nodeCount=1000000 \
  -p identityMode=numeric \
  -p writeBatchSize=10000 \
  -p includeEmbedding=false
```

`projectionPeakHeap` reports `peakHeapBytes` as a JMH secondary result. It is a
sampled process-heap delta, so use the same JVM, heap, and fork settings when
comparing changes.
