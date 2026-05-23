# dire-neo4j

Neo4j plugin for DiRe graph layouts.

`dire-neo4j` projects a graph from Cypher, builds CSR adjacency from Neo4j
relationships, computes a spectral initialization, runs DiRe attraction and
repulsion kernels, and writes layout coordinates back to nodes. It also ships an
unmanaged `/dire/` viewer for coordinate-faithful validation.

## Modules

```text
java-core/      primitive-array layout engine
neo4j-plugin/   Neo4j procedures and /dire/ viewer
benchmarks/     benchmark harness placeholder
docs/           usage notes
```

## Build

Java 21 is required.

```sh
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

mvn test
mvn package
```

The plugin jar is written to:

```text
neo4j-plugin/target/dire-neo4j-plugin-0.1.0-SNAPSHOT.jar
```

## Install

Copy the jar into Neo4j's plugin directory:

```sh
cp neo4j-plugin/target/dire-neo4j-plugin-0.1.0-SNAPSHOT.jar "$NEO4J_HOME/plugins/"
```

Enable procedures and the viewer in `neo4j.conf`:

```properties
dbms.security.procedures.unrestricted=dire.*
dbms.security.procedures.allowlist=dire.*
server.unmanaged_extension_classes=org.dire.neo4j.plugin=/dire
```

Restart Neo4j. The viewer is then available at:

```text
http://localhost:7474/dire/
```

## Local Neo4j From Scratch

On macOS with Homebrew:

```sh
brew install openjdk@21 neo4j

export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

mvn package
cp neo4j-plugin/target/dire-neo4j-plugin-0.1.0-SNAPSHOT.jar "$(brew --prefix neo4j)/libexec/plugins/"
```

Add the `neo4j.conf` settings from [Install](#install), then start Neo4j:

```sh
NEO4J_CONF="$(brew --prefix neo4j)/libexec/conf" neo4j console
```

Open `http://localhost:7474/dire/`.

## Run DiRe On A Loaded Graph

After loading data into Neo4j, call `dire.layout.write` with a node projection
and a relationship projection. The only required columns are numeric Neo4j node
ids.

```cypher
CALL dire.layout.write({
  nodeQuery: 'MATCH (n:Paper) RETURN id(n) AS id',
  relationshipQuery: '
    MATCH (a:Paper)-[r:CITES]->(b:Paper)
    RETURN id(a) AS source, id(b) AS target, coalesce(r.weight, 1.0) AS weight
  ',
  writeProperties: ['dire_x', 'dire_y'],
  writeInitialProperties: ['dire_initial_x', 'dire_initial_y'],
  iterations: 200,
  randomSeed: 42,
  concurrency: 8
})
YIELD nodesWritten, relationshipsRead, iterations, milliseconds, stress, meanEdgeLength
RETURN nodesWritten, relationshipsRead, iterations, milliseconds, stress, meanEdgeLength;
```

The query above writes:

- `dire_x`, `dire_y`: final DiRe coordinates
- `dire_initial_x`, `dire_initial_y`: initial coordinates before DiRe refinement

Open the viewer after the procedure finishes:

```text
http://localhost:7474/dire/
```

The viewer reads stored coordinates directly from Neo4j. Use its Cypher panel to
change labels, groups, coordinate columns, and graph scope.

## Procedures

Available procedures:

- `dire.layout.stream`: run the layout and stream coordinates.
- `dire.layout.write`: run the layout and write node properties.
- `dire.layout.stats`: run the layout and return runtime/quality metrics.
- `dire.layout.estimate`: estimate memory from query counts or explicit counts.

Required projection columns:

- `nodeQuery`: `id`
- `relationshipQuery`: `source`, `target`
- optional relationship weight: `weight`

## Configuration

Common layout options:

| Option | Default | Notes |
| --- | --- | --- |
| `initialization` | `spectral` | `spectral`, `random`, or `warm_start` |
| `relationshipMode` | `undirected` | `directed` or `undirected` |
| `dimensions` | `2` | 2D or 3D coordinates |
| `writeProperties` | `['dire_x', 'dire_y']` | final coordinates |
| `writeInitialProperties` | `['dire_initial_x', 'dire_initial_y']` | initialization coordinates |
| `warmStartProperties` | `writeProperties` | used with `initialization: 'warm_start'` |
| `negativeSamples` | `16` | sampled repulsion per node |
| `concurrency` | `min(availableProcessors, 8)` | worker threads for force kernels |

Warm-start example:

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
  iterations: 50
})
YIELD nodesWritten, milliseconds
RETURN nodesWritten, milliseconds;
```

## Viewer

The `/dire/` viewer renders stored coordinate properties. It does not create a
separate visualization graph.

Default assumptions:

- Nodes with `dire_x/dire_y`, `dire_initial_x/y`, `spectral_x/y`,
  `dire_fast_x/y`, `dire_balanced_x/y`, or `dire_wide_x/y` are displayed.
- Relationships between displayed nodes are displayed.
- `name` or `title` is used as the caption when present.
- `group` or the first node label is used for color categories.
- `kind: 'bridge'` marks bridge edges; other edges are treated as local.

The default viewer query randomly samples up to 1,000 coordinate-bearing nodes.
The sample size is the `WITH 1000 AS sampleSize` line in the node Cypher. Edit
that line, or replace the query, to load a different graph slice.

Editable viewer queries use this contract:

- node query: `idx`, `name`, `group`, and one or more coordinate pairs
- edge query: `source`, `target`, optional `weight`, optional `kind`
- `source` and `target` match node `idx`

Use Cypher to choose graph size and scope. The vertex slider only changes how
many already-loaded nodes and edges are drawn on screen.

To compare a wider layout, run the same projection with a stronger repulsion
and write it to `dire_wide_x/dire_wide_y`:

```cypher
CALL dire.layout.write({
  nodeQuery: 'MATCH (n:Paper) RETURN id(n) AS id',
  relationshipQuery: '
    MATCH (a:Paper)-[r:CITES]->(b:Paper)
    RETURN id(a) AS source, id(b) AS target, coalesce(r.weight, 1.0) AS weight
  ',
  writeProperties: ['dire_wide_x', 'dire_wide_y'],
  writeInitialProperties: [],
  repulsionStrength: 1.8,
  iterations: 200,
  randomSeed: 42,
  concurrency: 8
})
YIELD nodesWritten, milliseconds
RETURN nodesWritten, milliseconds;
```

## Estimate

```cypher
CALL dire.layout.estimate({
  nodeQuery: 'MATCH (n:Paper) RETURN id(n) AS id',
  relationshipQuery: 'MATCH (:Paper)-[:CITES]->(:Paper) RETURN 1 AS relationship'
})
YIELD nodeCount, relationshipCount, storedRelationshipCount, bytesMin, bytesMax
RETURN nodeCount, relationshipCount, storedRelationshipCount, bytesMin, bytesMax;
```
