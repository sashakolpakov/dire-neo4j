# Fast Kernel Results (2026-06-18)

This note records the first benchmark sweep after generalizing `fastKernel`
from a `b ~= 1` special case to a dyadic exponent approximation path.

## Commands

Build:

```bash
mvn -B -ntp -pl benchmarks -am package -DskipTests
```

Broad matrix with drift and stress deltas:

```bash
java -cp benchmarks/target/benchmarks.jar org.dire.neo4j.core.FastKernelMatrix
```

Representative JMH timing slices:

```bash
java -jar benchmarks/target/benchmarks.jar CoreLayoutBenchmark.randomInitLayout \
  -wi 0 -i 1 -r 3s -f 1 \
  -p nodeCount=10000 \
  -p longRangeEdgesPerNode=1 \
  -p iterations=10 \
  -p concurrency=1 \
  -p minDist=0.01 \
  -p spread=1.0 \
  -p fastKernel=false,true
```

Repeat the same command for:

- `minDist=0.2`, `spread=1.0`
- `minDist=1.0`, `spread=0.5`

## Broad Matrix Summary

The matrix covered:

- `nodeCount`: `1000`, `10000`
- `longRangeEdgesPerNode`: `1`, `4`
- `iterations`: `3`, `10`
- `concurrency`: `1`, `4`
- kernel shapes:
  - `minDist=0.01`, `spread=1.0`, fitted `b ~= 0.800638`
  - `minDist=0.2`, `spread=1.0`, fitted `b ~= 1.003005`
  - `minDist=1.0`, `spread=0.5`, fitted `b ~= 3.891211`

Observed outcomes:

- Speedups were common across the whole sweep, including exponents far from `1`.
- The broad matrix ranged from a small regression of `-0.61%` to a gain of
  `34.12%`.
- For the larger and more relevant `10000`-node cases, the gains were all
  positive and ranged from `2.85%` to `33.92%`.
- Stress deltas stayed small in the sweep: roughly `-0.71%` to `+1.21%`.
- Final-coordinate drift varied by shape and iteration count:
  - low-drift cases were around `0.0039` to `0.0171` RMS
  - higher-drift cases reached `0.3224` RMS with max per-coordinate deltas up
    to about `2.81`

Interpretation:

- The dyadic approximation is fast enough to justify keeping as an opt-in path.
- It is not harmless enough to make the default exact path obsolete.
- The current default should remain `fastKernel=false`.

## Representative JMH Slices

`CoreLayoutBenchmark.randomInitLayout`, `nodeCount=10000`,
`longRangeEdgesPerNode=1`, `iterations=10`, `concurrency=1`:

| minDist | spread | exact | fast | speedup |
| --- | --- | ---: | ---: | ---: |
| `0.01` | `1.0` | `27.091 ms/op` | `9.477 ms/op` | `65.0%` |
| `0.2` | `1.0` | `25.968 ms/op` | `7.179 ms/op` | `72.4%` |
| `1.0` | `0.5` | `24.567 ms/op` | `11.399 ms/op` | `53.6%` |

These JMH numbers are materially larger than the simple wall-clock matrix
speedups, so they should be treated as a separate signal rather than merged
naively with the ad hoc matrix. They do, however, agree on the main point:
the approximation path is faster across all three tested exponent regimes.
