# dire-neo4j usage

`dire-neo4j` projects a Neo4j graph directly from Cypher, builds primitive-array
CSR storage, initializes coordinates spectrally, runs sparse DiRe layout forces,
and streams or writes the resulting coordinates.

## Install

Build and copy the plugin jar:

```sh
mvn package
cp neo4j-plugin/target/dire-neo4j-plugin-0.1.0-SNAPSHOT.jar "$NEO4J_HOME/plugins/"
```

Enable procedures and the installable viewer in `neo4j.conf`:

```properties
dbms.security.procedures.unrestricted=dire.*
dbms.security.procedures.allowlist=dire.*
server.unmanaged_extension_classes=org.dire.neo4j.plugin=/dire
```

Restart Neo4j, then open:

```text
http://localhost:7474/dire/
```

The viewer is served by the plugin jar. It renders stored coordinate properties
directly and includes editable node/edge Cypher queries.

## Required query columns

`nodeQuery` must return:

```cypher
RETURN id(n) AS id
```

`relationshipQuery` must return:

```cypher
RETURN id(a) AS source, id(b) AS target
```

It may also return:

```cypher
RETURN coalesce(r.weight, 1.0) AS weight
```

Weights must be finite and non-negative. Zero-weight relationships are ignored.

## Stream

```cypher
CALL dire.layout.stream({
  nodeQuery: 'MATCH (n:Paper) RETURN id(n) AS id',
  relationshipQuery: '
    MATCH (a:Paper)-[r:CITES]->(b:Paper)
    RETURN id(a) AS source, id(b) AS target, coalesce(r.weight, 1.0) AS weight
  ',
  iterations: 200,
  randomSeed: 42
})
YIELD nodeId, initialX, initialY, x, y, initialEmbedding, embedding
RETURN nodeId, initialX, initialY, x, y, initialEmbedding, embedding;
```

## Write

```cypher
CALL dire.layout.write({
  nodeQuery: 'MATCH (n:Paper) RETURN id(n) AS id',
  relationshipQuery: '
    MATCH (a:Paper)-[r:CITES]->(b:Paper)
    RETURN id(a) AS source, id(b) AS target
  ',
  writeProperties: ['dire_x', 'dire_y'],
  writeInitialProperties: ['dire_initial_x', 'dire_initial_y'],
  iterations: 200,
  randomSeed: 42,
  concurrency: 8
})
YIELD nodesWritten, relationshipsRead, iterations, milliseconds
RETURN nodesWritten, relationshipsRead, iterations, milliseconds;
```

By default, `writeInitialProperties` is `['dire_initial_x', 'dire_initial_y']`,
so the database contains both the spectral/Laplian starting point and the final
DiRe-refined layout:

```cypher
MATCH (n:Paper)
RETURN n.name, n.dire_initial_x, n.dire_initial_y, n.dire_x, n.dire_y
ORDER BY n.name;
```

`concurrency` controls the number of worker threads used by the attraction and
repulsion kernels. The default is `min(availableProcessors, 8)`. Use
`concurrency: 1` when comparing exact single-thread timings.

## Warm start

Warm start is explicit. It reads existing coordinate properties and refines from
there:

```cypher
CALL dire.layout.write({
  nodeQuery: 'MATCH (n:Paper) RETURN id(n) AS id',
  relationshipQuery: '
    MATCH (a:Paper)-[:CITES]->(b:Paper)
    RETURN id(a) AS source, id(b) AS target
  ',
  initialization: 'warm_start',
  warmStartProperties: ['dire_x', 'dire_y'],
  writeProperties: ['dire_x', 'dire_y'],
  writeInitialProperties: ['dire_initial_x', 'dire_initial_y'],
  iterations: 50
})
YIELD nodesWritten, relationshipsRead, iterations, milliseconds
RETURN nodesWritten, relationshipsRead, iterations, milliseconds;
```

## Viewer

Open the installable plugin viewer after writing coordinates:

```text
http://localhost:7474/dire/
```

The viewer renders stored coordinates directly. Its default node query reads the
standard DiRe coordinate columns from any node, and its default edge query reads
relationships between displayed nodes. The default query loads a bridge-aware
random sample of up to 1,000 nodes. Edit either query in the page and press
`Run` to load a filtered or alternate graph.

The viewer recognizes `dire_wide_x` / `dire_wide_y` as the `DiRe wide` variant
when those properties are present.

## Estimate

`estimate` can count query rows:

```cypher
CALL dire.layout.estimate({
  nodeQuery: 'MATCH (n:Paper) RETURN id(n) AS id',
  relationshipQuery: 'MATCH (:Paper)-[:CITES]->(:Paper) RETURN 1 AS relationship'
})
YIELD nodeCount, relationshipCount, storedRelationshipCount, bytesMin, bytesMax
RETURN nodeCount, relationshipCount, storedRelationshipCount, bytesMin, bytesMax;
```

Or it can use explicit counts:

```cypher
CALL dire.layout.estimate({
  nodeCount: 1000000,
  relationshipCount: 10000000,
  relationshipMode: 'undirected'
})
YIELD bytesMin, bytesMax
RETURN bytesMin, bytesMax;
```
