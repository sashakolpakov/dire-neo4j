# dire-neo4j

Neo4j server plugin for DiRe graph layouts.

`dire-neo4j` projects a graph from Cypher, builds a primitive-array CSR
adjacency, computes a spectral initialization, runs DiRe attraction/repulsion
kernels, and writes layout coordinates back to Neo4j nodes. It also ships a
small unmanaged `/dire/` viewer served by the same Neo4j server.

The intended workflow is:

1. Load or keep your dataset in a normal Neo4j database.
2. Choose the node and relationship projection with Cypher.
3. Run `dire.layout.write`.
4. Open `/dire/` to inspect the stored coordinates.

## Repository Layout

```text
java-core/      primitive-array layout engine
neo4j-plugin/   Neo4j procedures and /dire/ viewer
benchmarks/     benchmark harness placeholder
docs/           Sphinx docs and usage notes
```

## Build

Java 21 is recommended for current Neo4j releases.

```sh
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

mvn test
mvn package
```

The server plugin jar is written to:

```text
neo4j-plugin/target/dire-neo4j-plugin-0.1.0-SNAPSHOT.jar
```

## Install Into A Normal Neo4j Server

Stop Neo4j, copy the jar into the server plugin directory, configure Neo4j, and
restart.

```sh
cp neo4j-plugin/target/dire-neo4j-plugin-0.1.0-SNAPSHOT.jar "$NEO4J_HOME/plugins/"
```

Add to `neo4j.conf`:

```properties
dbms.security.procedures.unrestricted=dire.*
dbms.security.procedures.allowlist=dire.*
server.unmanaged_extension_classes=org.dire.neo4j.plugin=/dire
```

Restart Neo4j and verify the procedures:

```cypher
SHOW PROCEDURES
YIELD name
WHERE name STARTS WITH 'dire.'
RETURN name
ORDER BY name;
```

Expected:

```text
dire.layout.estimate
dire.layout.stats
dire.layout.stream
dire.layout.write
```

The viewer is available on the same HTTP port as Neo4j:

```text
http://localhost:7474/dire/
```

## Homebrew Neo4j Example

```sh
brew install openjdk@21 neo4j

export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

mvn package
cp neo4j-plugin/target/dire-neo4j-plugin-0.1.0-SNAPSHOT.jar \
  "$(brew --prefix neo4j)/libexec/plugins/"
```

Edit:

```text
$(brew --prefix neo4j)/libexec/conf/neo4j.conf
```

Add the three `neo4j.conf` settings from the install section, then start:

```sh
NEO4J_CONF="$(brew --prefix neo4j)/libexec/conf" neo4j console
```

## Docker Example

```sh
docker run --rm \
  --name dire-neo4j \
  -p 7474:7474 -p 7687:7687 \
  -v "$PWD/neo4j-plugin/target/dire-neo4j-plugin-0.1.0-SNAPSHOT.jar:/plugins/dire-neo4j-plugin.jar" \
  -e NEO4J_AUTH=neo4j/password \
  -e 'NEO4J_dbms_security_procedures_unrestricted=dire.*' \
  -e 'NEO4J_dbms_security_procedures_allowlist=dire.*' \
  -e 'NEO4J_server_unmanaged__extension__classes=org.dire.neo4j.plugin=/dire' \
  neo4j:latest
```

Pin the Neo4j image version for repeatable production deployments.

## Ingest A Dataset

DiRe works on graph topology already stored in Neo4j. For a graph dataset, load
nodes and relationships with the standard Neo4j tools: `LOAD CSV`,
`neo4j-admin database import`, APOC, a driver script, or Neo4j Browser.

Example CSV shape:

```text
papers.csv
paper_id,title,category
p1,Graph embeddings,ML
p2,Layout algorithms,Visualization
p3,Citation networks,Networks

cites.csv
source,target,weight,kind
p1,p2,1.0,local
p1,p3,0.4,bridge
p3,p2,0.8,local
```

Place the CSV files in Neo4j's configured import directory, then run:

```cypher
CREATE CONSTRAINT paper_id IF NOT EXISTS
FOR (p:Paper) REQUIRE p.paperId IS UNIQUE;

LOAD CSV WITH HEADERS FROM 'file:///papers.csv' AS row
MERGE (p:Paper {paperId: row.paper_id})
SET p.title = row.title,
    p.name = coalesce(row.title, row.paper_id),
    p.group = coalesce(row.category, 'Paper');

LOAD CSV WITH HEADERS FROM 'file:///cites.csv' AS row
MATCH (a:Paper {paperId: row.source})
MATCH (b:Paper {paperId: row.target})
MERGE (a)-[r:CITES]->(b)
SET r.weight = CASE
      WHEN row.weight IS NULL OR row.weight = '' THEN 1.0
      ELSE toFloat(row.weight)
    END,
    r.kind = CASE
      WHEN row.kind IS NULL OR row.kind = '' THEN 'local'
      ELSE row.kind
    END;
```

Check the graph before running the layout:

```cypher
MATCH (p:Paper) RETURN count(p) AS nodes;
MATCH (:Paper)-[r:CITES]->(:Paper) RETURN count(r) AS relationships;
```

For vector datasets such as MNIST, create graph topology first. A common pattern
is to compute a k-nearest-neighbor graph outside Neo4j, then load one node per
item and one weighted relationship per neighbor pair. Use a categorical property
such as `group` or `digit` for viewer colors.

## Run A Layout

`dire.layout.write` reads a Cypher projection. The node query must return
Neo4j node ids as `id`. The relationship query must return endpoint ids as
`source` and `target`; `weight` is optional.

```cypher
CALL dire.layout.write({
  nodeQuery: '
    MATCH (n:Paper)
    RETURN id(n) AS id
  ',
  relationshipQuery: '
    MATCH (a:Paper)-[r:CITES]->(b:Paper)
    RETURN id(a) AS source,
           id(b) AS target,
           coalesce(r.weight, 1.0) AS weight
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

The procedure writes:

- `dire_x`, `dire_y`: final DiRe coordinates.
- `dire_initial_x`, `dire_initial_y`: initialization coordinates.

## Add A Wider Variant

The viewer recognizes `dire_wide_x` and `dire_wide_y` as a second layout option.
Use this for stronger separation experiments:

```cypher
CALL dire.layout.write({
  nodeQuery: 'MATCH (n:Paper) RETURN id(n) AS id',
  relationshipQuery: '
    MATCH (a:Paper)-[r:CITES]->(b:Paper)
    RETURN id(a) AS source, id(b) AS target, coalesce(r.weight, 1.0) AS weight
  ',
  initialization: 'warm_start',
  warmStartProperties: ['dire_initial_x', 'dire_initial_y'],
  writeProperties: ['dire_wide_x', 'dire_wide_y'],
  writeInitialProperties: [],
  iterations: 300,
  negativeSamples: 32,
  attractionStrength: 0.8,
  repulsionStrength: 2.0,
  spread: 1.6,
  randomSeed: 77,
  concurrency: 8
})
YIELD nodesWritten, relationshipsRead, milliseconds, stress, meanEdgeLength
RETURN nodesWritten, relationshipsRead, milliseconds, stress, meanEdgeLength;
```

## View The Result

Open:

```text
http://localhost:7474/dire/
```

The default viewer query randomly samples up to 1,000 coordinate-bearing nodes.
Edit the first line in the node query to load a different sample size:

```cypher
WITH 4000 AS sampleSize
```

The viewer uses:

- `name` or `title` for captions.
- `group` or the first node label for color.
- `kind: 'bridge'` for highlighted bridge edges.
- Local edge and bridge edge toggles as separate layers.

## Documentation

Build the Sphinx docs locally:

```sh
python3 -m sphinx -b html docs docs/_build/html
```

Start at:

```text
docs/index.rst
```

The most complete installation and data ingestion guide is:

```text
docs/installation.rst
docs/data-ingestion.rst
docs/running-layouts.rst
docs/viewer.rst
```

## Procedures

Available procedures:

- `dire.layout.stream`: run the layout and stream coordinates.
- `dire.layout.write`: run the layout and write node properties.
- `dire.layout.stats`: run the layout and return runtime/quality metrics.
- `dire.layout.estimate`: estimate memory from query counts or explicit counts.

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
