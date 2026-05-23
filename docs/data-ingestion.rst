Data Ingestion
==============

``dire-neo4j`` lays out graph topology. It does not require a special import
format: load your dataset into Neo4j as nodes and relationships, then select the
layout projection with Cypher.

Recommended Data Model
----------------------

For a graph layout, each visible item should be a node, and each topological
connection should be a relationship.

Useful display properties:

* ``name`` or ``title``: node caption in the viewer.
* ``group``: category used for color.
* relationship ``weight``: non-negative attraction weight.
* relationship ``kind: 'bridge'``: highlighted bridge edge in the viewer.

CSV Graph Example
-----------------

Example files:

.. code-block:: text

   papers.csv
   paper_id,title,category
   p1,Graph embeddings,ML
   p2,Layout algorithms,Visualization
   p3,Citation networks,Networks

.. code-block:: text

   cites.csv
   source,target,weight,kind
   p1,p2,1.0,local
   p1,p3,0.4,bridge
   p3,p2,0.8,local

Place the files in Neo4j's import directory, then run:

.. code-block:: cypher

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

Check the import:

.. code-block:: cypher

   MATCH (p:Paper) RETURN count(p) AS nodes;
   MATCH (:Paper)-[r:CITES]->(:Paper) RETURN count(r) AS relationships;
   MATCH (:Paper)-[r:CITES {kind: 'bridge'}]->(:Paper) RETURN count(r) AS bridges;

Existing Neo4j Graphs
---------------------

For an existing graph, no import step is needed. Write a projection that returns
the node ids and relationship endpoints you want to lay out:

.. code-block:: cypher

   MATCH (n:Person)
   RETURN id(n) AS id;

.. code-block:: cypher

   MATCH (a:Person)-[r:KNOWS]->(b:Person)
   RETURN id(a) AS source, id(b) AS target, coalesce(r.strength, 1.0) AS weight;

Vector Or Image Datasets
------------------------

For vector datasets such as MNIST, first create graph topology. A typical path
is:

1. Store each item as a Neo4j node.
2. Compute nearest neighbors outside Neo4j, or with another graph/data tool.
3. Load one weighted relationship per neighbor pair.
4. Store the class label as ``group`` so the viewer colors categories.

Example node CSV for MNIST-like data:

.. code-block:: text

   item_id,label,name
   mnist-000001,7,7:000001
   mnist-000002,2,2:000002

Example edge CSV:

.. code-block:: text

   source,target,weight,kind
   mnist-000001,mnist-004210,0.92,local
   mnist-000001,mnist-008814,0.41,bridge

Load it with the same pattern:

.. code-block:: cypher

   CREATE CONSTRAINT item_id IF NOT EXISTS
   FOR (n:Item) REQUIRE n.itemId IS UNIQUE;

   LOAD CSV WITH HEADERS FROM 'file:///items.csv' AS row
   MERGE (n:Item {itemId: row.item_id})
   SET n.name = row.name,
       n.group = row.label;

   LOAD CSV WITH HEADERS FROM 'file:///neighbors.csv' AS row
   MATCH (a:Item {itemId: row.source})
   MATCH (b:Item {itemId: row.target})
   MERGE (a)-[r:SIMILAR_TO]->(b)
   SET r.weight = toFloat(row.weight),
       r.kind = coalesce(row.kind, 'local');

Then run DiRe over ``(:Item)-[:SIMILAR_TO]->(:Item)``.

MNIST Loader
------------

The repository includes a MNIST loader that creates the graph topology, loads it
through Neo4j's HTTP transaction endpoint, and writes DiRe coordinates.

Download the train and test files:

.. code-block:: sh

   python3 -m pip install numpy scikit-learn

   mkdir -p .tmp/mnist
   curl -L -o .tmp/mnist/train-images-idx3-ubyte.gz \
     https://storage.googleapis.com/cvdf-datasets/mnist/train-images-idx3-ubyte.gz
   curl -L -o .tmp/mnist/train-labels-idx1-ubyte.gz \
     https://storage.googleapis.com/cvdf-datasets/mnist/train-labels-idx1-ubyte.gz
   curl -L -o .tmp/mnist/t10k-images-idx3-ubyte.gz \
     https://storage.googleapis.com/cvdf-datasets/mnist/t10k-images-idx3-ubyte.gz
   curl -L -o .tmp/mnist/t10k-labels-idx1-ubyte.gz \
     https://storage.googleapis.com/cvdf-datasets/mnist/t10k-labels-idx1-ubyte.gz

Load a larger sample:

.. code-block:: sh

   python3 examples/mnist/load_mnist_graph.py \
     --endpoint http://127.0.0.1:7474/db/neo4j/tx/commit \
     --mnist-dir .tmp/mnist \
     --samples 20000 \
     --layout-runs all

Load the full 70,000-image train+test set:

.. code-block:: sh

   python3 examples/mnist/load_mnist_graph.py \
     --endpoint http://127.0.0.1:7474/db/neo4j/tx/commit \
     --mnist-dir .tmp/mnist \
     --full \
     --split all \
     --neighbor-mode clustered \
     --layout-runs wide

The loader stores MNIST items as ``:Paper:MNIST`` nodes, nearest-neighbor links
as weighted ``:CITES`` relationships, and cross-digit links as
``kind: 'bridge'``.

Preflight Checks
----------------

Before running a layout, check:

.. code-block:: cypher

   MATCH (n) RETURN labels(n) AS labels, count(*) AS nodes ORDER BY nodes DESC;

   MATCH ()-[r]->()
   RETURN type(r) AS type, count(*) AS relationships
   ORDER BY relationships DESC;

   MATCH (a:Paper)-[r:CITES]->(b:Paper)
   WHERE r.weight IS NOT NULL AND (r.weight < 0 OR NOT r.weight = r.weight)
   RETURN count(r) AS badWeights;

Weights must be finite and non-negative.
