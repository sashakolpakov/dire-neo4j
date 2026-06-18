# Benchmarks

This module contains JMH benchmarks for the pure Java `dire-neo4j-core`
layout engine. Neo4j projection, write-throughput, stream, and peak-heap
benchmarks live in the sibling `neo4j-benchmarks/` module.

## Build

Compile the benchmark module and its core dependency:

```bash
mvn -pl benchmarks -am test
```

Build the runnable JMH jar:

```bash
mvn -pl benchmarks -am package -DskipTests
```

The shaded benchmark jar is written to:

```text
benchmarks/target/benchmarks.jar
```

## Smoke Run

Run a tiny local benchmark before changing layout or projection code:

```bash
java -jar benchmarks/target/benchmarks.jar CoreLayoutBenchmark \
  -wi 1 -i 1 -f 1 \
  -p nodeCount=1000 \
  -p longRangeEdgesPerNode=1 \
  -p iterations=3 \
  -p concurrency=1
```

The benchmark methods cover:

- `buildCsr`: CSR construction from deterministic synthetic relationship arrays.
- `spectralInitialization`: `DiReLayout.run` with spectral init and `iterations=0`.
- `randomInitLayout`: layout iterations with random initialization, useful for kernel timing.
- `fullSpectralLayout`: spectral initialization plus layout iterations.

## Local Scale Runs

The default parameters are deliberately small. Larger runs are opt-in.

70k-node smoke:

```bash
java -jar benchmarks/target/benchmarks.jar CoreLayoutBenchmark \
  -wi 1 -i 1 -f 1 \
  -p nodeCount=70000 \
  -p longRangeEdgesPerNode=1 \
  -p iterations=5 \
  -p concurrency=1
```

1M-node manual profile:

```bash
java -Xmx16g -jar benchmarks/target/benchmarks.jar CoreLayoutBenchmark.buildCsr \
  -wi 1 -i 1 -f 1 \
  -p nodeCount=1000000 \
  -p longRangeEdgesPerNode=1
```

Run the full 1M layout only on a machine with enough heap and time budget.
Do not add large runs as normal Maven lifecycle or CI gates until baseline
numbers and thresholds are established.

## Fixture

`SyntheticGraphFactory` generates deterministic graphs:

- node ids are `0..nodeCount-1`;
- the first `nodeCount` relationships form a ring;
- optional long-range relationships are generated from a fixed seed;
- relationship order is stable.
