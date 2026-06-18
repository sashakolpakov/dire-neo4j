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
- **P2.2 done:** `fastKernel` is an opt-in near-linear kernel shortcut. The
  default path still uses the scalar `Math.pow` kernel; when explicitly enabled
  and fitted `b` is close to `1.0`, attraction/repulsion reuse `distSq` directly.
- **N1 done:** plugin classes renamed to the `DiRe*` convention (`DiReProcedures`,
  `DiReConfig`, `DiReViewResource`, `DiReProceduresTest`) to match `DiReLayout`.
  Safe rename: the unmanaged extension is registered by package
  (`org.dire.neo4j.plugin`) and procedures by `@Procedure(name="dire.layout.*")`,
  so neither server config nor Cypher procedure names change. The persisted
  `:DireView` node label is data, not a code identifier, and is left unchanged.
- **P2.5 done:** `recenter` extracted into `Recenter.apply`
  (bit-identical, fixed reduction order) and a `RecenterBenchmark` JMH harness
  added isolating the recenter pass from a full layout run. Measurements at
  10k/100k nodes, 2D/3D, and concurrency 1/4 put recenter at 0.4-0.8% of
  per-iteration layout time, so it remains serial.
- **P3.1 done:** `DiReLayout` now uses a process-wide bounded shared executor
  instead of creating and shutting down a fixed thread pool per run. Callers
  can inject an externally owned `ExecutorService`; `DiReLayout` never shuts it
  down. The no-arg constructor remains compatible.

Current verification:

```text
mvn test
mvn -pl benchmarks -am package -DskipTests
java -jar benchmarks/target/benchmarks.jar CoreLayoutBenchmark -wi 1 -i 1 -f 1 \
  -p nodeCount=1000 -p longRangeEdgesPerNode=1 -p iterations=3 -p concurrency=1
```

Next implementation decision:

```text
S1 complete: P2.5 -> P3.1
S2: P1.3 -> P4.1b -> P4.2
S3: P3.3 -> P2.4
S4: P2.3 -> P3.2
```

Rationale:

- **P2.5** profiling showed `recenter` is immaterial, so the deterministic
  serial reduction remains.
- **P3.1** removed per-run fixed thread-pool creation in favor of shared or
  injected executor ownership.
- **P1.3/P4.1b/P4.2** should follow before deeper kernel changes because write
  batching and peak-heap benchmarks determine whether memory-at-scale is
  actually fixed.
- **P3.3** should precede packaging/release work and any GDS integration because
  the Neo4j API line and `javax`/`jakarta` boundary define supported artifacts.
- **P2.3** stays late: Vector API work is high-risk and only justified after
  scalar/profile work and benchmark baselines exist.

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
- **P1.2 Done - stream results lazily.** `DiReProcedures.stream` now returns a
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
- **P2.2 Done - special-case `b ~= 1`.** `fastKernel` defaults to false. When
  explicitly enabled and the fitted exponent is close to `1.0`, attraction and
  repulsion use `distSq` directly instead of `Math.pow(distSq, b)`, with branch
  selection outside hot loops.
- **P2.3 Vectorize the repulsion kernel** (`jdk.incubator.vector`) over its
  uniform O(n * negativeSamples) structure. Effort L, high risk: incubator API
  + reordered float reductions break bit-exactness. Ship as opt-in
  `kernelMode: 'vector'`; scalar stays the deterministic default. Do *after*
  P4.1 so it is measured.
- **P2.4 Convergence-checked spectral iterations.** `SpectralInitializer` runs a
  fixed 160 power iterations; add a Rayleigh/subspace-angle convergence check
  with a floor and the 160 cap. Effort M. Changes results - gate behind
  `spectralTolerance` (default = current fixed behavior).
- **P2.5 done - profile `recenter`** (serial every iteration, O(n*d)).
  `recenter` was extracted to `Recenter.apply` and measured with a dedicated
  JMH harness. A parallel double mean would require deterministic reduction,
  but profiling did not justify that complexity.
  Isolated recenter pass (AverageTime, JDK default, 1 fork):

  ```text
  nodeCount  dims  recenter us/op
  10000      2     19.3 ± 2.8
  10000      3     32.2 ± 13.4
  100000     2     197.6 ± 52.8
  100000     3     332.0 ± 82.0
  ```

  Matching full-layout measurements (20 iterations) produced:

  ```text
  nodes   dims  conc  recenter us  layout us  recenter/iteration
  10000   2     1     18.456       89738.986  0.41%
  10000   3     1     27.601      106803.038  0.52%
  10000   2     4     18.456       65714.724  0.56%
  10000   3     4     27.601       80268.458  0.69%
  100000  2     1    213.401      914346.156  0.47%
  100000  3     1    320.378     1075925.834  0.60%
  100000  2     4    213.401      641562.042  0.67%
  100000  3     4    320.378      819989.813  0.78%
  ```

  Verdict: below per-iteration noise; keep the deterministic serial reduction.

### P3 - Integration / packaging / threading

- **P3.1 done - stop creating a thread pool per `run()`.** `DiReLayout` uses a
  daemon, fixed-size process-wide executor with bounded queueing and caller-runs
  backpressure. An `ExecutorService` constructor supports host-managed
  execution; executors remain caller-owned. The no-arg constructor and
  source-range partitioning are preserved.
- **P3.2 GDS graph-catalog projection support.** Optional second loader reading a
  named GDS graph instead of re-running Cypher (ease + memory reuse). Effort L.
  Ship as a separate optional artifact - GDS is a heavy `provided` dependency
  with its own version matrix.
- **P3.3 Neo4j version matrix + `javax`->`jakarta` fix.** POM builds against a
  single Neo4j 5.26.0 though the README promises a jar per Neo4j line.
  Parameterize `neo4j.version` via profiles and a CI matrix. Note:
  `neo4j-plugin/pom.xml` declares `jakarta.ws.rs-api` but `DiReViewResource`
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
Done: P0.1 -> P0.2/P0.3 -> P4.1a -> P1.1a/P1.4a/P1.2a/P1.5a -> P2.1/P2.2 -> N1
Done: P2.5 (profiled; recenter remains serial) -> P3.1 (shared/injected executor)

S1 Immediate: complete
S2 Scale validation: P1.3 -> P4.1b -> P4.2
S3 Packaging + safe algorithm controls: P3.3 -> P2.4
S4 Optional/high-risk: P2.3 -> P3.2 (GDS)
```

Backward-incompatible items to call out in release notes: P0.1 is already a
security-breaking viewer change; P1.2 only if embeddings are dropped by default;
P1.3 if batched writes change atomicity; P3.3 for `javax`->`jakarta` on newer
Neo4j lines. P3.1 preserved the no-arg `DiReLayout` construction path.

### Next Task Details

1. **S2.1 / P1.3 - Batched write mode.**
   Add an opt-in `writeBatchSize` for `dire.layout.write`; default preserves the
   current single caller transaction. Document the atomicity tradeoff, test
   batch boundaries and failures, and keep `writeInitialProperties` behavior
   consistent with final coordinate writes.

2. **S2.2 / P4.1b - Projection/write/peak-heap benchmarks.**
   Extend `benchmarks/` with projection, write-throughput, and peak-heap
   scenarios for 70k and larger synthetic graphs. Include runs that compare
   numeric id mode, elementId mode, streaming with/without embeddings, and
   batched write mode. These measurements decide whether P1/P2 changes met the
   scale goal.

3. **S2.3 / P4.2 - Regression gates.**
   Add a large-graph smoke test, golden-vector determinism fixture, and viewer
   read-only regression. Keep the large graph test opt-in or profile-gated if it
   is too slow for default `mvn test`.

4. **S3.1 / P3.3 - Neo4j version matrix and `javax`/`jakarta` cleanup.**
   Parameterize supported Neo4j versions via Maven profiles and CI matrix.
   Resolve the `jakarta.ws.rs-api` dependency versus `javax.ws.rs.*` imports so
   the unmanaged extension builds intentionally for each supported Neo4j line.

5. **S3.2 / P2.4 - Convergence-checked spectral iterations.**
   Add `spectralTolerance` and related floor/cap controls with defaults that
   preserve the current fixed 160-iteration behavior. Gate changed output behind
   explicit config and compare quality/runtime with the P4.1b benchmark suite.

6. **S4.1 / P2.3 - Optional vector repulsion kernel.**
   Only start after P4.1b/P4.2 baselines exist. Ship as opt-in
   `kernelMode: 'vector'`, keep scalar deterministic default, and expect golden
   vector differences because reduction/order changes are likely.

7. **S4.2 / P3.2 - Optional GDS graph-catalog loader.**
   Defer until the plugin packaging matrix is stable. Implement as a separate
   optional artifact or profile because GDS introduces a heavy provided
   dependency and version matrix.
