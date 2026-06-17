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

## Improvement Plan

This section reviews the implemented codebase against the three project goals
and lays out a prioritized roadmap. It supersedes the forward-looking notes
above where they conflict; the sections above describe intent, this section
describes what to change next.

### Current Progress

Completed implementation slices:

- **P0.1 done:** `POST /api/query` no longer executes user-supplied Cypher;
  the viewer loads only the built-in payload, and the stale custom-query UI was
  removed.
- **P0.2 done:** `maxProjectionBytes` is an opt-in projection preflight that
  counts rows and fails before materializing projection arrays when the padded
  estimate exceeds the effective cap.
- **P0.3 done:** procedure projections accept numeric `id()` values and string
  `elementId(...)` values. ElementId mode uses deterministic surrogate longs in
  node-query row order for the core engine, while write and warm-start paths use
  `tx.getNodeByElementId(...)`.
- **P4.1a done:** `benchmarks/` is now a JMH module over `java-core`, with
  deterministic synthetic fixtures and smoke/scale commands.
- **P1.1a done:** `CsrGraphBuilder` no longer allocates transient
  `sourceIndex`/`targetIndex`/`arcWeights` arrays and uses a primitive internal
  `long -> int` node index. The public builder API and CSR ordering are
  unchanged.
- **P1.4a done:** `GraphProjectionLoader` now hands relationship backing arrays
  plus logical lengths to `CsrGraphBuilder`, avoiding `toArray()` copies for
  sources, targets, and weights.
- **P1.2a done:** `dire.layout.stream` returns a lazy `IntStream`-backed result
  stream. Boxed `embedding` and `initialEmbedding` lists are no longer allocated
  by default; set `includeEmbedding: true` to request them.
- **P1.5a done:** `MemoryEstimate` now accounts for loader buffers, the
  primitive node-index map, optional warm-start coordinates, and optional stream
  embedding lists.
- **P2.1 done:** attraction and repulsion kernels reuse per-pair delta locals
  instead of recomputing deltas for force updates. The default deterministic
  path remains covered by existing serial/parallel tests.

Current verification:

```text
mvn test
mvn -pl benchmarks -am package -DskipTests
java -jar benchmarks/target/benchmarks.jar CoreLayoutBenchmark -wi 1 -i 1 -f 1 \
  -p nodeCount=1000 -p longRangeEdgesPerNode=1 -p iterations=3 -p concurrency=1
```

Next implementation decision:

```text
P2.2/P2.5 -> P3.1 -> P2.4 -> P2.3
```

Rationale:

- **P2.2** should add an opt-in fast kernel path for `b ~= 1` so the scalar
  deterministic default remains unchanged.
- **P2.5** should only proceed if benchmark/profiler data shows `recenter` is
  material; deterministic reduction order is mandatory.
- **P3.1** becomes more important after kernel cleanup because every layout run
  still creates a fresh fixed thread pool.

### Conformance Verdict

```text
Goal                                  Verdict   Core reason
------------------------------------  --------  ----------------------------------------
Idiomatic, easy-to-install plugin     Partial   Viewer arbitrary Cypher is locked down
                                                 and elementId is supported; no GDS
                                                 catalog integration yet.
Uses kernels to speed up computation  Partial   Kernels are scalar double math with
                                                 Math.pow/sqrt/exp per sample, delta vector
                                                 computed twice per edge, no SIMD, fixed
                                                 160 spectral iterations. ~2-4x headroom.
Memory-sparing at scale               Partial   Core CSR/transient loader copies and
                                                 stream materialization are reduced;
                                                 write tx batching, elementId overhead,
                                                 and peak-heap validation remain.
```

The core engine's steady state (CSR `int[]/float[]` + two `float[]` buffers) is
lean. The remaining memory problems are concentrated around write transaction
atomicity/batching, optional elementId storage for Neo4j 5 compatibility, and
future validation of the conservative memory estimate model against peak heap.

### Determinism Contract

The `parallel == serial` and seed-determinism tests pass because the kernels
partition by **source-node range** and each worker writes only its own
`forces[sourceBase + dim]` slots (no cross-thread contention, identical float
summation order), and the repulsion sample target is derived purely from
`(randomSeed, iteration, i, sample)`, independent of thread/chunk.

Any kernel or parallelization change **must preserve per-source-node
accumulation order and the RNG key derivation**, or
`DiReLayoutTest.parallelLayoutMatchesSingleWorkerLayout` and
`spectralLayoutIsDeterministicAndFinite` break. Items that perturb this are
marked below and must ship opt-in (default path stays bit-exact) or with
regenerated golden vectors.

### P0 - Safety (do first; small, high-impact)

- **P0.1 Done - lock down the viewer's arbitrary-Cypher endpoint.**
  `POST /api/query` now returns a forbidden JSON error instead of executing
  user-supplied Cypher, `executeRows` no longer commits viewer read
  transactions, and the viewer UI no longer exposes a custom-query form.
  *Backward-incompatible* security fix.
- **P0.2 Done - bound projection size before allocation.** In `GraphProjectionLoader.load`,
  the optional `maxProjectionBytes` config triggers row-count preflight,
  `MemoryEstimate` padding, and a fail-fast cap against JVM max heap before
  projection arrays are materialized.
- **P0.3 Done - accept `elementId` alongside numeric `id()`.** Procedure queries
  may return string `elementId(...) AS id/source/target`; elementId mode maps
  nodes to deterministic surrogate longs for `java-core`, and write/warm-start
  lookups use `tx.getNodeByElementId(...)`.

### P1 - Memory at scale (largest payoff for 70k-millions)

- **P1.1a Done - direct CSR build.** Replaced the boxed node-index map and
  transient `sourceIndex`/`targetIndex`/`arcWeights` arrays with a primitive
  internal `long -> int` map and direct two-pass CSR construction. Public API and
  CSR ordering are unchanged.
- **P1.1b/P1.4a Done - length-aware CSR builder entry point.** Exposed an
  internal builder overload that accepts backing arrays plus logical lengths,
  reuses the direct two-pass CSR path, and preserves adjacency order. This
  unlocks P1.4 without copying loader buffers through `toArray()`.
- **P1.2 Done - stream results lazily.** `DireProcedures.stream` now returns a
  lazy stream and gates redundant `embedding`/`initialEmbedding` list allocation
  behind `includeEmbedding: true`.
- **P1.3 Batch the write transaction.** `dire.layout.write` writes every node in
  the caller's single transaction. Add a configurable `writeBatchSize` and
  commit in chunks. Effort M. Batched commits change atomicity (opt-in /
  documented).
- **P1.4 Done - reduce loader copies.** `GraphProjectionLoader` now hands
  `PrimitiveLongList`/`PrimitiveFloatList` backing arrays + sizes directly to a
  length-aware CSR builder entry point instead of `toArray()`. Combined with
  P1.1, this removes one full copy each of sources/targets/weights.
- **P1.5 Done - make `MemoryEstimate` more honest.** It now includes loader
  backing arrays, the primitive node-index map, optional warm-start coordinates,
  and optional stream embeddings. Future benchmark work should compare this
  conservative estimate against measured peak heap.

### P2 - Kernel performance (the explicit "kernels" goal)

- **P2.1 Done - fuse the double delta pass.** `accumulate*Range` now computes
  per-pair delta locals once, reuses them for distance and force updates, and
  keeps the default deterministic path covered by serial/parallel tests.
- **P2.2 Special-case `b ~= 1`.** When `KernelParameters.b` is near 1,
  `Math.pow(distSq, b)` collapses to `distSq`. Branch once outside the loop.
  Effort S-M. Perturbs determinism - ship opt-in (`fastKernel`).
- **P2.3 Vectorize the repulsion kernel** (`jdk.incubator.vector`) over its
  uniform O(n * negativeSamples) structure. Effort L, high risk: incubator API
  + reordered float reductions break bit-exactness. Ship as opt-in
  `kernelMode: 'vector'`; scalar stays the deterministic default. Do *after*
  P4.1 so it is measured.
- **P2.4 Convergence-checked spectral iterations.** `SpectralInitializer` runs a
  fixed 160 power iterations; add a Rayleigh/subspace-angle convergence check
  with a floor and the 160 cap. Effort M. Changes results - gate behind
  `spectralTolerance` (default = current fixed behavior).
- **P2.5 Parallelize `recenter`** (currently serial every iteration, O(n*d)).
  Only if profiling justifies; a parallel double mean must use a deterministic
  reduction order. Effort S-M. Likely leave serial.

### P3 - Integration / packaging / threading

- **P3.1 Stop creating a thread pool per `run()`.** `DiReLayout` calls
  `Executors.newFixedThreadPool` per invocation. Inject Neo4j's `JobScheduler`
  or a shared bounded pool. Effort M. Keep a no-arg constructor for
  compatibility; preserve source-range partitioning.
- **P3.2 GDS graph-catalog projection support.** Optional second loader reading a
  named GDS graph instead of re-running Cypher (ease + memory reuse). Effort L.
  Ship as a separate optional artifact - GDS is a heavy `provided` dependency
  with its own version matrix.
- **P3.3 Neo4j version matrix + `javax`->`jakarta` fix.** POM builds against a
  single Neo4j 5.26.0 though the README promises a jar per Neo4j line.
  Parameterize `neo4j.version` via profiles and a CI matrix. Note:
  `neo4j-plugin/pom.xml` declares `jakarta.ws.rs-api` but `DireViewResource`
  imports `javax.ws.rs.*` - this only works on lines that still provide `javax`
  and will break otherwise. Effort M. Non-breaking to users; fixes a latent
  incompatibility.

### P4 - Quality / benchmarks

- **P4.1a Done - core benchmark harness.** `benchmarks/` now contains a JMH
  module for deterministic synthetic `java-core` benchmarks covering CSR build,
  spectral initialization through public layout APIs, random-init layout, and
  full spectral layout.
- **P4.1b Remaining - projection/write/peak-heap benchmarks.** Add a
  JMH module over synthetic graphs (70k and ~1M nodes) measuring projection
  time + peak heap, per-iteration kernel time, spectral-init time, and write
  throughput, with a no-regression check in CI. Effort M-L. **Sequence before
  P2.3/P2.4** so kernel changes are measured, not guessed.
- **P4.2 Large-graph + determinism regression tests.** Add a ~100k-node memory/
  throughput smoke test, a golden-vector determinism test pinning exact
  positions for a fixed seed/config, and a viewer read-only test. Effort M.

### Suggested Sequencing

```text
Done: P0.1 -> P0.2/P0.3 -> P4.1a -> P1.1a/P1.4a/P1.2a/P1.5a -> P2.1

Next: P2.2/P2.5 -> P3.1 -> P2.4 -> P2.3 (vector, last)
Later: P4.1b/P4.2, P3.3, P3.2 (GDS)
```

Backward-incompatible items to call out in release notes: P0.1 is already a
security-breaking viewer change; P1.2 only if embeddings are dropped by default;
P1.3 if batched writes change atomicity; P3.1 if `DiReLayout` construction
changes; P3.3 for `javax`->`jakarta` on newer Neo4j lines.

### Next Task Details

1. **P2.2 - Opt-in fast kernel for `b ~= 1`.**
   Add a config flag such as `fastKernel`, defaulting to false so the existing
   deterministic path remains the default. Branch outside attraction/repulsion
   loops when `Math.abs(kernel.b - 1.0)` is below a small epsilon and use
   `distSq` instead of `Math.pow(distSq, kernel.b)`. Tests must prove
   `fastKernel: false` preserves current output.

2. **P2.5 - Profile `recenter` before changing it.**
   Use the JMH harness or a profiler to determine whether serial recentering is
   material at target graph sizes. Only implement deterministic parallel
   recentering if the profile shows it matters; reduction order must be fixed.

3. **P3.1 - Shared executor / thread-pool ownership.**
   Stop creating a fresh fixed thread pool per `DiReLayout.run()` while keeping
   a no-arg compatibility path. Preserve source-range partitioning and current
   deterministic reduction behavior.

4. **P2.4/P2.3 - Later kernel work.**
   Add spectral convergence gating before vector experiments. Keep vector mode
   opt-in and last because it is most likely to perturb floating-point order and
   depends on benchmark evidence.
