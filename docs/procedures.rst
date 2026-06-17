Procedures
==========

``dire.layout.write``
---------------------

Runs a layout and writes coordinates to node properties.

Required config:

* ``nodeQuery``
* ``relationshipQuery``

Common config:

* ``writeProperties``
* ``writeInitialProperties``
* ``iterations``
* ``randomSeed``
* ``concurrency``
* ``relationshipMode``
* ``negativeSamples``
* ``attractionStrength``
* ``repulsionStrength``

``dire.layout.stream``
----------------------

Runs a layout and streams coordinates without writing to Neo4j.

Returned columns include:

* ``nodeId``
* ``elementId`` when the projection uses ``elementId(...)`` inputs
* ``x``, ``y``, optional ``z``
* ``initialX``, ``initialY``, optional ``initialZ``
* ``embedding`` and ``initialEmbedding`` only when ``includeEmbedding: true``

``dire.layout.stats``
---------------------

Runs a layout and returns runtime/quality values without writing node
coordinates.

``dire.layout.estimate``
------------------------

Estimates memory from query counts or explicit counts.

Configuration Reference
-----------------------

.. list-table::
   :header-rows: 1

   * - Option
     - Default
     - Notes
   * - ``initialization``
     - ``spectral``
     - ``spectral``, ``random``, or ``warm_start``
   * - ``relationshipMode``
     - ``undirected``
     - ``directed`` or ``undirected``
   * - ``dimensions``
     - ``2``
     - 2D or 3D coordinates
   * - ``writeProperties``
     - ``['dire_x', 'dire_y']``
     - final coordinates
   * - ``writeInitialProperties``
     - ``['dire_initial_x', 'dire_initial_y']``
     - initialization coordinates
   * - ``warmStartProperties``
     - ``writeProperties``
     - used with ``initialization: 'warm_start'``
   * - ``negativeSamples``
     - ``16``
     - sampled repulsion per node
   * - ``concurrency``
     - ``min(availableProcessors, 8)``
     - worker threads
   * - ``includeEmbedding``
     - ``false``
     - include boxed ``embedding`` and ``initialEmbedding`` lists in stream results
   * - ``fastKernel``
     - ``false``
     - opt-in shortcut for near-linear kernels; may slightly perturb coordinates
   * - ``maxProjectionBytes``
     - unset
     - optional fail-fast projection memory cap
   * - ``attractionStrength``
     - ``1.0``
     - relationship attraction multiplier
   * - ``repulsionStrength``
     - ``1.0``
     - sampled repulsion multiplier
   * - ``spread``
     - ``1.0``
     - layout spread parameter
   * - ``learningRate``
     - ``1.0``
     - force update scale
   * - ``cutoff``
     - ``42.0``
     - force cutoff

Projection Rules
----------------

* ``nodeQuery`` must return numeric ``id`` values or string ``elementId(...)``
  values as ``id``.
* ``relationshipQuery`` must return matching numeric or string ``source`` and
  ``target`` values.
* Optional ``weight`` values must be finite and non-negative.
* Relationships whose endpoints are not present in ``nodeQuery`` cannot be used.
