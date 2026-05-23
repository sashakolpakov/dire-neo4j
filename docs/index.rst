dire-neo4j
==========

``dire-neo4j`` is a Neo4j server plugin for DiRe graph layouts. It reads a graph
projection from Cypher, computes layout coordinates, writes them back to Neo4j
nodes, and serves a built-in ``/dire/`` viewer from the same Neo4j process.

The plugin is meant for normal Neo4j servers: install the jar in the server
``plugins`` directory, enable the ``dire.*`` procedures, restart Neo4j, and run
the layout from Cypher.

.. toctree::
   :maxdepth: 2
   :caption: User Guide

   installation
   data-ingestion
   running-layouts
   viewer
   procedures
   troubleshooting

Quick Workflow
--------------

1. Load a graph into Neo4j.
2. Run ``CALL dire.layout.write(...)`` over a Cypher node/relationship
   projection.
3. Open ``http://localhost:7474/dire/``.
4. Use the viewer's editable Cypher to choose sample size, labels, coordinate
   columns, and edge scope.

Core Procedure
--------------

.. code-block:: cypher

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
