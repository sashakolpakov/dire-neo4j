# Validation Plan

The hard part of `dire-neo4j` is not basic plugin wiring. The hard part is
proving that the native graph-mode DiRe preserves topology, scales, and matches
the reference math where it should.

## Test Philosophy

Test the project in layers:

1. Reference numerical kernels.
2. Graph fixtures and topology behavior.
3. Initialization behavior.
4. Determinism and stability.
5. Scale and memory.
6. Neo4j procedure behavior.

Every optimization must preserve the reference tests before it is benchmarked.

## Force Equivalence

Goal: same graph, same positions, same parameters should produce matching forces
between Java and Python reference implementations.

Tests:

- attraction force equivalence on small weighted graphs
- sampled repulsion equivalence with fixed seed and fixed negative samples
- force clipping / cutoff equivalence
- directed vs undirected attraction behavior
- float tolerance checks for Java `float` and optional `double` reference paths

Suggested fixtures:

- single edge
- path of length 3
- triangle
- weighted triangle
- disconnected pair of edges

## Layout Invariants

Every layout run should satisfy:

- all coordinates are finite
- coordinates do not collapse to one point
- disconnected components separate enough to inspect
- weighted edges pull harder than unweighted edges
- higher iteration counts should not increase obvious stress catastrophically
- output dimensionality matches requested `2D` or `3D`

## Topology Fixtures

Build explicit graph fixtures and track expected qualitative behavior:

- cycle
- two cycles
- grid
- tree
- barbell graph
- star graph
- disconnected components
- scale-free graph
- graph with weighted bridges

For each fixture, compare:

- edge length distribution
- graph-distance vs embedding-distance correlation
- component separation
- neighborhood preservation
- stress or stress-like score
- Betti-style checks where applicable

## Spectral / Laplacian Init

Random init is debug-only. Default topology mode should use spectral /
Laplacian init.

Tests:

- cycle initializes with ordered/circular structure
- disconnected components do not start collapsed
- spectral init is deterministic for fixed graph and seed
- fallback path behaves sensibly when eigensolver does not converge
- warm-start mode accepts existing `dire_x` / `dire_y`

## Stability

Embeddings can rotate, reflect, or scale without changing meaning. Stability
tests should compare after Procrustes alignment or other shape-normalized
comparison.

Tests:

- fixed seed gives reproducible layout up to alignment
- same graph with reordered node IDs produces equivalent layout after remapping
- parallel and single-threaded paths match within tolerance
- changing weights changes layout in expected direction

## Quality Metrics

Track quality metrics in benchmarks and regression tests:

- edge length distribution
- stress-like graph layout score
- graph-distance vs embedding-distance correlation
- neighborhood preservation
- component separation
- crossing-ish proxy for 2D drawings where useful
- Betti-style checks for cycle and multi-cycle fixtures where applicable

## Scale Tests

Scale tests should report wall time, peak memory, and coordinate quality.

Targets:

- 100k edges
- 1M edges
- 10M edges

For each scale:

- CSR build time
- init time
- iteration time
- total runtime
- peak heap usage
- off-heap/native usage if any
- output write time

## Neo4j Procedure Tests

Procedure coverage:

- `stream` returns `nodeId`, `x`, `y`
- `write` writes configured properties
- `stats` reports counts, iterations, timing, and quality summaries
- `estimate` reports memory before execution
- invalid config fails with clear errors
- empty graph fails clearly
- graph with isolated nodes is handled explicitly
- relationship weights are optional
- property writes are transactional and count nodes correctly

## Performance Gates

Initial gates should be modest and explicit:

- no per-edge allocation inside layout iterations
- no boxed numeric hot paths
- memory estimate within acceptable error of actual heap usage
- Java reference path and optimized path agree within tolerance
- multi-threaded path is measurably faster before it becomes default

## Regression Rule

Any future optimization, including Java Vector API or native kernels, must first
pass:

1. force equivalence
2. layout invariants
3. spectral init tests
4. stability tests
5. fixture quality checks

Only then should it be considered for scale benchmarks.
