Running Layouts
===============

Projection Contract
-------------------

``dire.layout.write`` and ``dire.layout.stream`` read a graph from two Cypher
queries.

``nodeQuery`` must return:

.. code-block:: cypher

   RETURN id(n) AS id

``relationshipQuery`` must return:

.. code-block:: cypher

   RETURN id(a) AS source, id(b) AS target

It may also return:

.. code-block:: cypher

   RETURN coalesce(r.weight, 1.0) AS weight

All relationship endpoints must be included by ``nodeQuery``.

Write Coordinates
-----------------

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

The output properties are normal Neo4j node properties:

.. code-block:: cypher

   MATCH (n:Paper)
   RETURN n.name, n.dire_x, n.dire_y
   LIMIT 10;

Wide Variant
------------

Write a second coordinate pair when you want a stronger separation view:

.. code-block:: cypher

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

Warm Start
----------

Warm start refines from existing coordinate properties:

.. code-block:: cypher

   CALL dire.layout.write({
     nodeQuery: 'MATCH (n:Paper) RETURN id(n) AS id',
     relationshipQuery: '
       MATCH (a:Paper)-[:CITES]->(b:Paper)
       RETURN id(a) AS source, id(b) AS target
     ',
     initialization: 'warm_start',
     warmStartProperties: ['dire_x', 'dire_y'],
     writeProperties: ['dire_x', 'dire_y'],
     writeInitialProperties: [],
     iterations: 50
   })
   YIELD nodesWritten, relationshipsRead, iterations, milliseconds
   RETURN nodesWritten, relationshipsRead, iterations, milliseconds;

Stream Coordinates
------------------

Use ``stream`` when you do not want to write properties:

.. code-block:: cypher

   CALL dire.layout.stream({
     nodeQuery: 'MATCH (n:Paper) RETURN id(n) AS id',
     relationshipQuery: '
       MATCH (a:Paper)-[r:CITES]->(b:Paper)
       RETURN id(a) AS source, id(b) AS target, coalesce(r.weight, 1.0) AS weight
     ',
     iterations: 200,
     randomSeed: 42
   })
   YIELD nodeId, initialX, initialY, x, y
   RETURN nodeId, initialX, initialY, x, y
   LIMIT 20;

Estimate Memory
---------------

.. code-block:: cypher

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
