# Neo4j User Guide

This guide covers installing `dire-neo4j` into a Neo4j server, running DiRe on
an existing graph, and viewing the resulting coordinates in the bundled viewer.

## What You Install

`dire-neo4j` is a Neo4j server plugin. The jar provides:

- `dire.layout.write`: compute a DiRe layout and write coordinates to nodes.
- `dire.layout.stream`: compute a layout and return coordinates without writes.
- `dire.layout.stats`: run a layout and return quality/runtime metrics.
- `dire.layout.estimate`: estimate memory before running.
- `/dire/`: an unmanaged web viewer served by Neo4j.

The viewer is not a separate Neo4j Browser plugin. It is served from the same
Neo4j process after the jar is installed.

## Requirements

- Neo4j server with plugin support.
- Java compatible with your Neo4j distribution. Neo4j 2026 uses Java 21.
- Maven, if building from source.

The current build targets the Neo4j Java API declared in `pom.xml` and is tested
locally against the Homebrew Neo4j package.

Managed Neo4j services such as Aura do not allow installing arbitrary server
plugin jars.

## Get The Jar

For normal installation, download the release asset that matches your Neo4j
line from <https://github.com/sashakolpakov/dire-neo4j/releases>. Release
assets include both versions in the filename:

```text
dire-neo4j-plugin-0.1.0-neo4j-5.26.0.jar
```

To build the jar yourself, run this from the repository root:


```sh
mvn package
```

The plugin jar is produced at:

```text
neo4j-plugin/target/dire-neo4j-plugin-0.1.0-SNAPSHOT.jar
```

## Install Into Neo4j

Stop Neo4j before replacing plugins.

Copy the jar into the Neo4j plugins directory:

```sh
cp dire-neo4j-plugin-0.1.0-neo4j-5.26.0.jar "$NEO4J_HOME/plugins/dire-neo4j-plugin.jar"
```

Add these settings to `neo4j.conf`:

```properties
dbms.security.procedures.unrestricted=dire.*
dbms.security.procedures.allowlist=dire.*
server.unmanaged_extension_classes=org.dire.neo4j.plugin=/dire
```

Restart Neo4j.

## Homebrew Example

```sh
brew install neo4j

cp dire-neo4j-plugin-0.1.0-neo4j-5.26.0.jar \
  "$(brew --prefix neo4j)/libexec/plugins/dire-neo4j-plugin.jar"
```

Edit:

```text
$(brew --prefix neo4j)/libexec/conf/neo4j.conf
```

Then start Neo4j:

```sh
NEO4J_CONF="$(brew --prefix neo4j)/libexec/conf" neo4j console
```

## Docker Example

Mount the jar into `/plugins` and pass the required Neo4j settings:

```sh
docker run --rm \
  --name dire-neo4j \
  -p 7474:7474 -p 7687:7687 \
  -v "$PWD/dire-neo4j-plugin-0.1.0-neo4j-5.26.0.jar:/plugins/dire-neo4j-plugin.jar:ro" \
  -e NEO4J_AUTH=neo4j/password \
  -e 'NEO4J_dbms_security_procedures_unrestricted=dire.*' \
  -e 'NEO4J_dbms_security_procedures_allowlist=dire.*' \
  -e 'NEO4J_server_unmanaged__extension__classes=org.dire.neo4j.plugin=/dire' \
  neo4j:5.26.0
```

The same command works with a locally built jar if you replace the mounted jar
path with `neo4j-plugin/target/dire-neo4j-plugin-0.1.0-SNAPSHOT.jar`.

## Verify Installation

In Neo4j Browser or `cypher-shell`:

```cypher
SHOW PROCEDURES
YIELD name
WHERE name STARTS WITH 'dire.'
RETURN name
ORDER BY name;
```

Expected procedure names:

```text
dire.layout.estimate
dire.layout.stats
dire.layout.stream
dire.layout.write
```

Open the viewer:

```text
http://localhost:7474/dire/
```

Use your configured HTTP port if it is not `7474`.

## Run DiRe On Your Graph

The layout procedure reads a graph projection from two Cypher queries:

- `nodeQuery` must return `id`.
- `relationshipQuery` must return `source` and `target`.
- `relationshipQuery` may also return `weight`.

Example for `(:Paper)-[:CITES]->(:Paper)`:

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

This writes:

- `dire_x`, `dire_y`: final DiRe coordinates.
- `dire_initial_x`, `dire_initial_y`: initial coordinates before refinement.

## Add A Wide Layout

To compare a stronger separation setting, write a second coordinate pair:

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
YIELD nodesWritten, relationshipsRead, iterations, milliseconds, stress, meanEdgeLength
RETURN nodesWritten, relationshipsRead, iterations, milliseconds, stress, meanEdgeLength;
```

The viewer recognizes `dire_wide_x` and `dire_wide_y` as `DiRe wide`.

## Optional Viewer Metadata

The viewer can infer standard coordinate columns. Add metadata when you want
stable display names, ordering, and an active default:

```cypher
MERGE (r:EmbeddingRun {key: 'dire'})
SET r.name = 'DiRe',
    r.description = 'Default DiRe layout',
    r.xProperty = 'dire_x',
    r.yProperty = 'dire_y',
    r.rank = 1;

MERGE (r:EmbeddingRun {key: 'dire_wide'})
SET r.name = 'DiRe wide',
    r.description = 'Higher-separation DiRe layout',
    r.xProperty = 'dire_wide_x',
    r.yProperty = 'dire_wide_y',
    r.rank = 2;

MERGE (v:DireView {name: 'Current Browser View'})
SET v.run = 'dire_wide';
```

## View The Layout

Open:

```text
http://localhost:7474/dire/
```

The default viewer query randomly samples up to 20,000 nodes with stored
coordinate properties. To load a smaller or larger sample, edit the first line
in the node query:

```cypher
WITH 70000 AS sampleSize
```

The vertex slider only changes how many loaded nodes are drawn. The Cypher
sample size controls how many nodes are loaded from Neo4j.

## Viewer Query Contract

Custom node queries should return:

```cypher
RETURN id(n) AS idx,
       coalesce(n.name, n.title, toString(id(n))) AS name,
       coalesce(n.group, head(labels(n)), 'Graph') AS group
```

The viewer also reads stored coordinate properties from the returned node ids.
For custom coordinate columns, return `x` and `y` directly.

Custom edge queries should return:

```cypher
RETURN id(a) AS source,
       id(b) AS target,
       coalesce(r.weight, 1.0) AS weight,
       coalesce(r.kind, 'local') AS kind
```

`kind: 'bridge'` draws highlighted bridge edges. Other edge kinds are treated as
local edges.

## Estimate Memory

```cypher
CALL dire.layout.estimate({
  nodeQuery: '
    MATCH (n:Paper)
    RETURN id(n) AS id
  ',
  relationshipQuery: '
    MATCH (a:Paper)-[r:CITES]->(b:Paper)
    RETURN id(a) AS source, id(b) AS target
  '
})
YIELD nodeCount, relationshipCount, storedRelationshipCount, bytesMin, bytesMax
RETURN nodeCount, relationshipCount, storedRelationshipCount, bytesMin, bytesMax;
```

## Common Problems

No `dire.*` procedures:

- The jar is not in Neo4j's active `plugins` directory.
- Neo4j was not restarted after copying the jar.
- `dbms.security.procedures.allowlist` or `unrestricted` does not include
  `dire.*`.

`/dire/` returns 404:

- `server.unmanaged_extension_classes=org.dire.neo4j.plugin=/dire` is missing.
- Neo4j was not restarted after changing `neo4j.conf`.

Viewer opens but shows no graph:

- Run `dire.layout.write` first.
- Confirm nodes have `dire_x` and `dire_y`.
- Confirm the viewer node query matches the labels/properties in your graph.

Layout fails on the projection:

- Ensure `nodeQuery` returns numeric `id`.
- Ensure `relationshipQuery` returns numeric `source` and `target`.
- Ensure all relationship endpoints are included in `nodeQuery`.
- Ensure weights are finite and non-negative.
