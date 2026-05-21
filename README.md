# dire-neo4j

Neo4j-native DiRe graph layout and topology-preserving embedding plugin.

This repository is for the relationship-topology-first Neo4j integration of
DiRe. Unlike the vector-cloud workflow in `dire-rapids`, this project treats
Neo4j relationships as the topology to preserve:

```text
Neo4j relationships
  -> sparse attraction graph
  -> spectral / Laplacian initialization
  -> DiRe-style attraction and sampled repulsion
  -> 2D/3D coordinates written back to Neo4j
```

The first implementation target is a Java Neo4j procedure plugin. The hot loops
should be written as JVM-JIT-friendly primitive-array code, not as direct
translations of PyTorch kernels.

## Initial Scope

- Build CSR adjacency from selected Neo4j relationships.
- Initialize layout with spectral / Laplacian coordinates by default.
- Run sparse DiRe attraction over graph relationships.
- Run sampled global repulsion over nodes.
- Expose `stream`, `write`, `stats`, and `estimate` procedure modes.
- Validate numerical behavior against `dire-rapids` reference kernels where
  applicable.

See [DEVELOP.md](DEVELOP.md) and [TEST.md](TEST.md).
