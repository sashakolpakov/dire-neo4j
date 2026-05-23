Viewer
======

Open the installed viewer at:

.. code-block:: text

   http://localhost:7474/dire/

Use your Neo4j HTTP port if it is not ``7474``.

What The Viewer Reads
---------------------

The viewer reads coordinates already stored on Neo4j nodes. It does not run a
layout by itself and does not create a separate visualization graph.

Recognized coordinate pairs:

* ``dire_x`` / ``dire_y``
* ``dire_initial_x`` / ``dire_initial_y``
* ``dire_fast_x`` / ``dire_fast_y``
* ``dire_balanced_x`` / ``dire_balanced_y``
* ``dire_wide_x`` / ``dire_wide_y``
* ``spectral_x`` / ``spectral_y``
* custom ``x`` / ``y`` returned directly by the node query

Display properties:

* ``name`` or ``title`` for captions.
* ``group`` or the first node label for color.
* relationship ``kind: 'bridge'`` for highlighted bridge edges.

Default Sampling
----------------

The default node query randomly samples up to 1,000 nodes with ``dire_x`` and
``dire_y``. Change the first line to load a different sample size:

.. code-block:: cypher

   WITH 4000 AS sampleSize

The vertex slider controls how many loaded nodes are drawn. It does not fetch
more data from Neo4j. The Cypher sample size controls what is loaded.

Custom Node Query
-----------------

The node query should return:

.. code-block:: cypher

   MATCH (n:Paper)
   WHERE n.dire_x IS NOT NULL AND n.dire_y IS NOT NULL
   RETURN id(n) AS idx,
          coalesce(n.name, n.title, toString(id(n))) AS name,
          coalesce(n.group, head(labels(n)), 'Graph') AS group

The viewer hydrates known coordinate properties from the returned ``idx``
values. If you want to bypass stored coordinate names, return ``x`` and ``y``:

.. code-block:: cypher

   MATCH (n:Paper)
   RETURN id(n) AS idx,
          n.name AS name,
          n.group AS group,
          n.my_layout_x AS x,
          n.my_layout_y AS y

Custom Edge Query
-----------------

The edge query receives ``$visibleIds`` from the viewer and should return edges
between loaded nodes:

.. code-block:: text

   MATCH (a:Paper)-[r:CITES]->(b:Paper)
   WHERE id(a) IN $visibleIds AND id(b) IN $visibleIds
   RETURN id(a) AS source,
          id(b) AS target,
          coalesce(r.weight, 1.0) AS weight,
          coalesce(r.kind, 'local') AS kind
   ORDER BY source, target

Bridge Edges
------------

Use ``kind: 'bridge'`` for relationships you want highlighted:

.. code-block:: cypher

   MATCH (a:Paper)-[r:CITES]->(b:Paper)
   SET r.kind = CASE
     WHEN a.group = b.group THEN 'local'
     ELSE 'bridge'
   END;

The viewer has separate toggles for local edges and bridge edges, so bridge-only
inspection is possible.
