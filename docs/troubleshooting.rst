Troubleshooting
===============

No ``dire.*`` Procedures
------------------------

Check:

* The jar is in Neo4j's active ``plugins`` directory.
* Neo4j was restarted after copying the jar.
* ``dbms.security.procedures.unrestricted=dire.*`` is set.
* ``dbms.security.procedures.allowlist=dire.*`` is set.

Use:

.. code-block:: cypher

   SHOW PROCEDURES
   YIELD name
   WHERE name STARTS WITH 'dire.'
   RETURN name
   ORDER BY name;

Viewer Returns 404
------------------

Check:

* ``server.unmanaged_extension_classes=org.dire.neo4j.plugin=/dire`` is set.
* Neo4j was restarted after changing ``neo4j.conf``.
* You are using the Neo4j HTTP port, usually ``7474``.

Viewer Opens But Shows No Graph
-------------------------------

Check:

.. code-block:: cypher

   MATCH (n)
   WHERE n.dire_x IS NOT NULL AND n.dire_y IS NOT NULL
   RETURN count(n) AS coordinateNodes;

If this returns zero, run ``dire.layout.write`` first or edit the viewer node
query to use the coordinate properties you have.

Layout Projection Fails
-----------------------

Common causes:

* ``nodeQuery`` does not return ``id``.
* ``relationshipQuery`` does not return ``source`` and ``target``.
* Relationship endpoints are missing from the node projection.
* Some weights are negative or non-finite.

Check whether the relationship pattern you intend to project has endpoints
outside the node label you intend to use:

.. code-block:: cypher

   MATCH (a)-[r:CITES]->(b)
   WHERE NOT a:Paper OR NOT b:Paper
   RETURN count(r) AS badRelationships;

Slow Or Large Layouts
---------------------

Use ``dire.layout.estimate`` first. Then consider:

* narrower Cypher projections;
* fewer relationships;
* fewer iterations while tuning;
* setting ``concurrency`` to match available cores;
* adding a second ``dire_wide`` run only after the base run is acceptable.

Bridge Edges Are Not Visible
----------------------------

Bridge edges are controlled by relationship ``kind``:

.. code-block:: cypher

   MATCH ()-[r]->()
   RETURN coalesce(r.kind, 'local') AS kind, count(*) AS relationships
   ORDER BY relationships DESC;

Only ``kind = 'bridge'`` is drawn as a highlighted bridge layer in the viewer.
