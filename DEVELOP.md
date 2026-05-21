# Development Plan

## Product Model

`dire-neo4j` is the Neo4j-native graph topology mode for DiRe.

The important distinction:

```text
dire-rapids vector mode:
  numeric matrix -> kNN graph -> DiRe layout

dire-neo4j graph mode:
  Neo4j relationships -> DiRe layout
```

For a database graph, the relationships are the topology we want to preserve.
The first native implementation should therefore avoid rebuilding kNN unless a
separate vector-property mode is explicitly requested.

## Initialization Policy

Random initialization must not be the default because it can damage topology and
reduce reproducibility.

Default initialization choices:

```text
pure graph topology -> spectral / Laplacian init
graph + node vectors -> PCA or spectral+PCA hybrid
incremental graph -> existing dire_x/dire_y warm start
random -> debug/testing only
```

## Native Plugin Base

The base algorithm should be:

1. Build CSR adjacency from Neo4j relationships.
2. Compute spectral / Laplacian initial coordinates.
3. Run DiRe-style sparse attraction over relationships.
4. Run sampled repulsion over nodes.
5. Write coordinates back to Neo4j.

Target procedure shape:

```cypher
CALL dire.layout.write({
  nodeQuery: 'MATCH (n:Paper) RETURN id(n) AS id',
  relationshipQuery: '
    MATCH (a:Paper)-[r:CITES]->(b:Paper)
    RETURN id(a) AS source, id(b) AS target, coalesce(r.weight, 1.0) AS weight
  ',
  writeProperties: ['dire_x', 'dire_y'],
  iterations: 200,
  randomSeed: 42
})
YIELD nodesWritten, relationshipsRead, iterations, milliseconds
```

## Core Data Structures

Use primitive arrays and CSR graph storage:

```java
int nodeCount;
long[] neo4jNodeIds;

int[] offsets;      // length nodeCount + 1
int[] targets;      // length edgeCount, or 2 * edgeCount for undirected mode
float[] weights;    // optional, same length as targets

float[] x;
float[] y;
float[] fx;
float[] fy;
```

Avoid in hot loops:

- boxed numbers
- Java streams
- per-edge object allocation
- maps inside the iteration loop
- synchronized force updates

## Kernel Transfer Strategy

Do not literally transfer PyTorch JIT kernels. Translate the same DiRe math into
JVM-JIT-friendly Java loops over primitive arrays.

Initial implementation:

- pure Java loops
- deterministic seeded sampling
- single-threaded reference path
- multi-threaded path behind tests

Optimization ladder:

1. Primitive-array Java implementation.
2. JIT-friendly loop structure and allocation-free iterations.
3. Per-thread force buffers with deterministic reduction.
4. Profile-guided tuning.
5. Java Vector API experiments only if profiling shows scalar math is the
   bottleneck.
6. JNI/native kernels only after Java performance is proven insufficient.

Handwritten assembler is not a first implementation target.

## Parallel Force Accumulation

Attraction over edges creates write contention:

```text
edge i -> j updates fx[i], fy[i], fx[j], fy[j]
```

The first correct parallel design should use per-thread force buffers:

```text
thread 0: fx0, fy0
thread 1: fx1, fy1
...
reduce: fx = sum(fx_thread)
```

This costs more memory but makes correctness and reproducibility much easier.
Optimize later only with benchmarks.

## Procedure Modes

Aim for Neo4j/GDS-like procedure modes:

- `dire.layout.stream`: returns `nodeId`, `x`, `y`, optional `embedding`.
- `dire.layout.write`: writes properties such as `dire_x`, `dire_y`.
- `dire.layout.stats`: runs and reports quality/runtime stats without writing.
- `dire.layout.estimate`: estimates memory before execution.

## Repository Shape

Planned layout:

```text
dire-neo4j/
  java-core/        # pure Java graph layout engine
  neo4j-plugin/     # procedure layer
  benchmarks/       # synthetic and Neo4j-backed benchmarks
  docs/             # user and design docs
```

Keep `java-core` independent from Neo4j APIs so the numerical algorithm can be
tested without a database process.
